/**
 * Batched streaming response handler.
 *
 * Collects step updates from the WebSocket and sends them to Telegram
 * in batches. Uses `editMessageText` to update a single message as
 * content streams in, sending new messages when the content exceeds
 * Telegram's 4096-char limit.
 *
 * Batching interval: 1.5s — balances responsiveness vs Telegram rate limits.
 *
 * Deduplication strategy:
 *  - The WS replays ALL steps from the conversation on connect.
 *  - The "ready" message tells us how many steps existed before our message.
 *  - We skip all steps with offset < historicalStepCount.
 *  - We also track rendered offsets in session.renderedStepOffsets to
 *    prevent re-rendering across streamer instances.
 */

import type { Api } from "grammy";
import type { ChatSession } from "./session.js";
import type { WSMessage } from "./porta-client.js";
import { formatStep, splitMessage, getApprovalInfo, escapeHtml } from "./formatter.js";
import { withRetry } from "./retry.js";
import type { InlineKeyboardMarkup } from "grammy/types";

/** Minimum interval between Telegram message edits (ms). */
const FLUSH_INTERVAL_MS = 1500;

/** Max length before sending a new message instead of editing. */
const MAX_MSG_LEN = 4000;

/** Interval for resending typing indicator (Telegram expires it after ~5s). */
const TYPING_INTERVAL_MS = 4000;

export class ResponseStreamer {
  private readonly api: Api;
  private readonly chatId: number;
  private readonly session: ChatSession;
  private readonly cascadeId: string;

  /** Pending text not yet flushed to Telegram. */
  private pendingText = "";
  /** Whether the flush timer is scheduled. */
  private flushScheduled = false;
  /** Whether finalize() has been called. */
  private finalized = false;
  /** Track approval keys already sent. */
  private approvalsSent = new Set<string>();
  /** Accumulated text for error pattern detection. */
  private allRenderedText = "";

  /** Whether the streamed content contained a permission/trust error. */
  public hasPermissionError = false;
  /** Whether the streamed content contained any general error. */
  public hasError = false;
  /** Whether any new (non-historical) content was actually processed. */
  public hasNewContent = false;
  /** Timer for periodic typing indicator. */
  private typingTimer: ReturnType<typeof setInterval> | null = null;
  /** Current progress status message ID (separate from content messages). */
  private progressMessageId: number | null = null;
  /** Last progress text shown (to avoid redundant edits). */
  private lastProgressText = "";
  /** Timestamp when the stream started. */
  private startTime = Date.now();
  /** Lock to prevent concurrent updateProgress from creating duplicate messages. */
  private progressSending = false;
  /** Timer for periodic progress message refresh (updates elapsed time). */
  private progressRefreshTimer: ReturnType<typeof setInterval> | null = null;
  /** Last step data used for progress (for periodic refresh). */
  private lastProgressStep: Record<string, unknown> | null = null;
  /**
   * Whether we have received the "ready" message from the WS.
   * Until we know the historical boundary, we buffer step messages.
   */
  private ready = false;
  /** Buffered step messages received before "ready". */
  private preReadyBuffer: WSMessage[] = [];

  constructor(
    api: Api,
    chatId: number,
    session: ChatSession,
    cascadeId: string,
  ) {
    this.api = api;
    this.chatId = chatId;
    this.session = session;
    this.cascadeId = cascadeId;
  }

  /** Start sending periodic "typing..." chat action. */
  private startTyping(): void {
    if (this.typingTimer || this.finalized) return;
    // Send immediately, then repeat
    this.sendTypingAction();
    this.typingTimer = setInterval(() => this.sendTypingAction(), TYPING_INTERVAL_MS);
  }

  /** Stop the typing indicator. */
  private stopTyping(): void {
    if (this.typingTimer) {
      clearInterval(this.typingTimer);
      this.typingTimer = null;
    }
  }

  /** Start periodic progress message refresh (updates elapsed time). */
  private startProgressRefresh(): void {
    if (this.progressRefreshTimer || this.finalized) return;
    this.progressRefreshTimer = setInterval(() => {
      if (this.lastProgressStep && this.progressMessageId) {
        // Reset lastProgressText to force re-render with new elapsed time
        this.lastProgressText = "";
        void this.updateProgress(this.lastProgressStep);
      }
    }, 10_000);
  }

  /** Stop the progress refresh timer. */
  private stopProgressRefresh(): void {
    if (this.progressRefreshTimer) {
      clearInterval(this.progressRefreshTimer);
      this.progressRefreshTimer = null;
    }
  }

  /** Send a single typing action (fire-and-forget). */
  private sendTypingAction(): void {
    withRetry(() => this.api.sendChatAction(this.chatId, "typing"), 1, 500).catch(() => {});
  }

  /**
   * Process incoming WebSocket messages.
   * Called by the WS connection handler for each received message.
   */
  async onMessage(msg: WSMessage): Promise<void> {
    if (msg.type === "ready") {
      // The "ready" message tells us how many steps existed before our turn.
      // All steps with offset < stepCount are historical and should be skipped.
      const readyMsg = msg as { type: "ready"; stepCount: number };
      this.session.historicalStepCount = readyMsg.stepCount;
      this.ready = true;
      console.log(
        `[streamer:${this.cascadeId.slice(0, 8)}] ready: historicalStepCount=${readyMsg.stepCount}`,
      );

      // Process any buffered messages now that we know the boundary
      for (const buffered of this.preReadyBuffer) {
        await this.processStepMessage(buffered);
      }
      this.preReadyBuffer = [];
      return;
    }

    if (msg.type === "steps") {
      if (!this.ready) {
        // Buffer until we know the historical boundary
        this.preReadyBuffer.push(msg);
        return;
      }
      await this.processStepMessage(msg);
    } else if (msg.type === "status") {
      const statusMsg = msg as { type: "status"; running: boolean };
      if (!statusMsg.running && !this.finalized) {
        await this.finalize();
      }
    }
  }

  /** Process a steps-type WS message, skipping historical steps. */
  private async processStepMessage(msg: WSMessage): Promise<void> {
    const raw = msg as unknown as Record<string, unknown>;
    const steps = raw.steps as Record<string, unknown>[];
    const offset = (raw.offset as number) ?? 0;

    // Start typing indicator on first step data
    this.startTyping();

    for (let i = 0; i < steps.length; i++) {
      const stepOffset = offset + i;

      // ── Skip historical steps ──
      // Steps that existed before the current user message are replayed
      // by the WS but should NOT be rendered again.
      if (stepOffset < this.session.historicalStepCount) {
        continue;
      }

      // ── Skip already-rendered steps ──
      // Even within the current turn, avoid rendering the same step twice
      // (e.g. if the WS re-sends steps with updated status).
      const step = steps[i];
      const status = (step.status as string) ?? "";

      // Only mark as "fully rendered" when the step is DONE.
      // In-progress steps may update (e.g. command output arriving).
      if (this.session.renderedStepOffsets.has(stepOffset)) {
        // Already rendered this step in its DONE state — skip entirely
        continue;
      }

      // Build a fingerprint to detect meaningful changes
      const hasOutput = !!(step.runCommand as Record<string, unknown> | undefined)?.output;
      const isDone = status === "CORTEX_STEP_STATUS_DONE";
      const isGenerating = status === "CORTEX_STEP_STATUS_GENERATING";
      const isError = status === "CORTEX_STEP_STATUS_ERROR";

      if (isError) {
        this.hasError = true;
      }

      // For GENERATING steps, update the progress indicator instead of rendering content
      if (isGenerating) {
        this.hasNewContent = true;
        await this.updateProgress(step);
        this.lastProgressStep = step;
        this.startProgressRefresh();
        continue;
      }

      this.hasNewContent = true;
      await this.processStep(step);

      // Mark as rendered once it reaches DONE
      if (isDone) {
        this.session.renderedStepOffsets.add(stepOffset);
      }
    }
  }

  /** Process a single step. */
  private async processStep(step: Record<string, unknown>): Promise<void> {
    // Check for approval needs first
    const approval = getApprovalInfo(step);
    if (approval) {
      const key = `${approval.trajectoryId}:${approval.stepIndex}`;
      if (!this.approvalsSent.has(key)) {
        this.approvalsSent.add(key);
        await this.sendApprovalRequest(approval);
      }
      return;
    }

    // Format step to text
    const text = formatStep(step);
    if (!text) return;

    this.pendingText += (this.pendingText ? "\n\n" : "") + text;
    this.allRenderedText += text + "\n";
    this.scheduleFlush();
  }

  /** Schedule a batched flush. */
  private scheduleFlush(): void {
    if (this.flushScheduled || this.finalized) return;
    this.flushScheduled = true;

    this.session.flushTimer = setTimeout(async () => {
      this.flushScheduled = false;
      this.session.flushTimer = null;
      await this.flush();
    }, FLUSH_INTERVAL_MS);
  }

  /** Flush pending text to Telegram. */
  async flush(): Promise<void> {
    if (!this.pendingText) return;

    const combinedText = this.session.streamBuffer + 
      (this.session.streamBuffer ? "\n\n" : "") + 
      this.pendingText;
    this.pendingText = "";

    // Check if we need to split into a new message
    if (combinedText.length > MAX_MSG_LEN && this.session.streamMessageId) {
      // Current message is full — start a new one
      this.session.streamMessageId = null;
      this.session.streamBuffer = "";
    }

    if (!this.session.streamMessageId) {
      // Send a new message
      const chunks = splitMessage(combinedText);
      for (let i = 0; i < chunks.length; i++) {
        try {
          const sent = await withRetry(() =>
            this.api.sendMessage(this.chatId, chunks[i], {
              parse_mode: "HTML",
            }),
          );
          // Track only the last message for future edits
          if (i === chunks.length - 1) {
            this.session.streamMessageId = sent.message_id;
            this.session.streamBuffer = chunks[i];
          }
        } catch (err) {
          console.error(`[streamer] Send error: ${(err as Error).message}`);
          // Retry without parse mode (content may have unbalanced HTML tags)
          try {
            const sent = await withRetry(() =>
              this.api.sendMessage(this.chatId, chunks[i]),
            );
            if (i === chunks.length - 1) {
              this.session.streamMessageId = sent.message_id;
              this.session.streamBuffer = chunks[i];
            }
          } catch {
            // Give up on this chunk
          }
        }
      }
    } else {
      // Edit existing message with accumulated content
      const newText = combinedText.length > MAX_MSG_LEN
        ? combinedText.slice(combinedText.length - MAX_MSG_LEN)
        : combinedText;

      try {
        await withRetry(() =>
          this.api.editMessageText(
            this.chatId,
            this.session.streamMessageId!,
            newText,
            { parse_mode: "HTML" },
          ),
        );
        this.session.streamBuffer = newText;
      } catch (err) {
        const errMsg = (err as Error).message;
        // "message is not modified" is expected when content hasn't changed
        if (!errMsg.includes("message is not modified")) {
          // Try without HTML parse mode
          try {
            await this.api.editMessageText(
              this.chatId,
              this.session.streamMessageId,
              newText,
            );
            this.session.streamBuffer = newText;
          } catch {
            // Start fresh with a new message next time
            this.session.streamMessageId = null;
            this.session.streamBuffer = "";
          }
        }
      }
    }
  }

  /** Send an inline keyboard for step approval. */
  private async sendApprovalRequest(approval: {
    type: "command" | "file";
    trajectoryId: string;
    stepIndex: number;
    detail: string;
  }): Promise<void> {
    // Flush any pending text first
    await this.flush();

    const emoji = approval.type === "command" ? "⚡" : "🔐";
    const label = approval.type === "command" ? "Command" : "File Access";
    const text =
      `${emoji} <b>${label} Approval Required</b>\n\n` +
      `<code>${escapeHtml(approval.detail.length > 500 ? approval.detail.slice(0, 500) + "..." : approval.detail)}</code>`;

    const callbackPrefix = `${approval.type}:${this.cascadeId}:${approval.trajectoryId}:${approval.stepIndex}`;
    const keyboard: InlineKeyboardMarkup = {
      inline_keyboard: [
        [
          { text: "✅ Approve", callback_data: `approve:${callbackPrefix}` },
          { text: "❌ Reject", callback_data: `reject:${callbackPrefix}` },
        ],
      ],
    };

    try {
      await this.api.sendMessage(this.chatId, text, {
        parse_mode: "HTML",
        reply_markup: keyboard,
      });
      // Reset stream state — approval messages are standalone
      this.session.streamMessageId = null;
      this.session.streamBuffer = "";
    } catch (err) {
      console.error(`[streamer] Approval send error: ${(err as Error).message}`);
    }
  }

  /** Update a progress status line showing what the agent is doing. */
  private async updateProgress(step: Record<string, unknown>): Promise<void> {
    const elapsed = Math.round((Date.now() - this.startTime) / 1000);
    const mins = Math.floor(elapsed / 60);
    const secs = elapsed % 60;
    const timeStr = mins > 0 ? `${mins}m ${secs}s` : `${secs}s`;

    // Determine activity from step type + metadata
    const meta = step.metadata as Record<string, unknown> | undefined;
    const toolAction = (meta?.toolAction as string) ?? "";
    const toolSummary = (meta?.toolSummary as string) ?? "";
    const taskName = (meta?.taskName as string) ?? "";

    let activity = "🤔 Thinking...";
    if (step.runCommand) {
      const cmd = (step.runCommand as Record<string, unknown>).commandLine as string ?? "";
      const shortCmd = cmd.length > 60 ? cmd.slice(0, 57) + "..." : cmd;
      activity = `⚡ Running: <code>${escapeHtml(shortCmd)}</code>`;
    } else if (step.codeAction) {
      const file = (step.codeAction as Record<string, unknown>).filePath as string ?? "";
      const name = file.split("/").pop() ?? "file";
      const desc = toolAction || (step.codeAction as Record<string, unknown>).description as string || "";
      activity = `📝 Editing: <code>${escapeHtml(name)}</code>`;
      if (desc) activity += `\n   <i>${escapeHtml(desc.slice(0, 80))}</i>`;
    } else if (step.viewFile) {
      const file = (step.viewFile as Record<string, unknown>).filePath as string ?? "";
      const name = file.split("/").pop() ?? "file";
      activity = `👁 Reading: <code>${escapeHtml(name)}</code>`;
    } else if (step.grepSearch) {
      const query = (step.grepSearch as Record<string, unknown>).query as string ?? "";
      activity = `🔍 Searching: <code>${escapeHtml(query.slice(0, 40))}</code>`;
    } else if (step.listDirectory) {
      activity = "📂 Browsing files...";
    } else if (step.plannerResponse) {
      // Show snippet of what the agent is thinking about
      const resp = step.plannerResponse as Record<string, unknown>;
      const text = (resp.modifiedResponse as string) ?? (resp.response as string) ?? "";
      if (text.length > 20) {
        // Show first meaningful line (skip empty lines)
        const lines = text.split("\n").filter(l => l.trim().length > 0);
        const snippet = lines[0]?.slice(0, 60) ?? "";
        activity = `💭 ${escapeHtml(snippet)}${snippet.length >= 60 ? "..." : ""}`;
      } else {
        activity = "💭 Generating response...";
      }
    } else if (step.sendCommandInput) {
      activity = "⌨️ Sending input...";
    } else if (toolAction) {
      activity = `🔧 ${escapeHtml(toolAction.slice(0, 60))}`;
    } else if (toolSummary) {
      activity = `🔧 ${escapeHtml(toolSummary.slice(0, 60))}`;
    }

    // Add task context if available
    let taskLine = "";
    if (taskName && !activity.includes(taskName)) {
      taskLine = `\n📋 <i>${escapeHtml(taskName.slice(0, 50))}</i>`;
    }

    const progressText = `⏳ <b>${timeStr}</b> — ${activity}${taskLine}`;

    // Don't update if text hasn't changed
    if (progressText === this.lastProgressText) return;
    this.lastProgressText = progressText;

    const keyboard: InlineKeyboardMarkup = {
      inline_keyboard: [[{ text: "🛑 Cancel Task", callback_data: `stop:${this.cascadeId}` }]],
    };

    try {
      if (!this.progressMessageId) {
        // Guard against concurrent sends creating duplicates
        if (this.progressSending) return;
        this.progressSending = true;
        try {
          // Send new progress message
          const sent = await withRetry(() =>
            this.api.sendMessage(this.chatId, progressText, { parse_mode: "HTML", reply_markup: keyboard }),
          );
          this.progressMessageId = sent.message_id;
        } finally {
          this.progressSending = false;
        }
      } else {
        // Edit existing progress message
        await withRetry(() =>
          this.api.editMessageText(
            this.chatId,
            this.progressMessageId!,
            progressText,
            { parse_mode: "HTML", reply_markup: keyboard },
          ),
        );
      }
    } catch (err) {
      const msg = (err as Error).message;
      if (!msg.includes("message is not modified")) {
        // Log but keep the message ID — resetting causes duplicate messages
        console.log(`[streamer] progress update failed: ${msg.slice(0, 100)}`);
      }
    }
  }

  /** Finalize the stream — flush remaining content and clean up. */
  async finalize(): Promise<void> {
    if (this.finalized) return;
    this.finalized = true;

    // Stop typing indicator
    this.stopTyping();
    this.stopProgressRefresh();

    if (this.session.flushTimer) {
      clearTimeout(this.session.flushTimer);
      this.session.flushTimer = null;
    }

    await this.flush();

    // Update progress message to show completion
    const elapsed = Math.round((Date.now() - this.startTime) / 1000);
    const mins = Math.floor(elapsed / 60);
    const secs = elapsed % 60;
    const timeStr = mins > 0 ? `${mins}m ${secs}s` : `${secs}s`;
    if (this.progressMessageId) {
      let finalMsg = `✅ <b>Task selesai</b> (${timeStr})`;
      if (this.hasError || this.hasPermissionError) {
        finalMsg = `❌ <b>Task berhenti dengan error</b> (${timeStr})`;
      }
      
      try {
        await withRetry(() =>
          this.api.editMessageText(
            this.chatId,
            this.progressMessageId!,
            finalMsg,
            { parse_mode: "HTML" },
          ),
        );
      } catch {
        // Best effort
      }
    }

    // Close WebSocket
    if (this.session.wsConnection) {
      this.session.wsConnection.close();
      this.session.wsConnection = null;
    }

    // Reset stream state for next message
    this.session.streamMessageId = null;
    this.session.streamBuffer = "";

    // Check for permission-related errors in rendered content
    const permPatterns = [
      "not permission",
      "unexpected user interaction",
      "workspace trust",
      "permission denied",
      "allWorkspaceTrustGranted",
    ];
    const lower = this.allRenderedText.toLowerCase();
    this.hasPermissionError = permPatterns.some((p) => lower.includes(p));
  }
}
