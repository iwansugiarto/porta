/**
 * Batched streaming response handler.
 *
 * Collects step updates from the WebSocket and sends them to Telegram
 * in batches. Uses `editMessageText` to update a single message as
 * content streams in, sending new messages when the content exceeds
 * Telegram's 4096-char limit.
 *
 * Batching interval: 1.5s — balances responsiveness vs Telegram rate limits.
 */

import type { Api } from "grammy";
import type { ChatSession } from "./session.js";
import type { WSMessage } from "./porta-client.js";
import { formatStep, splitMessage, getApprovalInfo, escapeHtml } from "./formatter.js";
import type { InlineKeyboardMarkup } from "grammy/types";

/** Minimum interval between Telegram message edits (ms). */
const FLUSH_INTERVAL_MS = 1500;

/** Max length before sending a new message instead of editing. */
const MAX_MSG_LEN = 4000;

export class ResponseStreamer {
  private readonly api: Api;
  private readonly chatId: number;
  private readonly session: ChatSession;
  private readonly cascadeId: string;

  /** Pending text not yet flushed to Telegram. */
  private pendingText = "";
  /** Whether the flush timer is scheduled. */
  private flushScheduled = false;
  /** Whether finalization has occurred. */
  private finalized = false;
  /** Steps that need approval (tracked to avoid duplicate keyboards). */
  private approvalsSent = new Set<string>();

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

  /**
   * Process incoming WebSocket messages.
   * Called by the WS connection handler for each received message.
   */
  async onMessage(msg: WSMessage): Promise<void> {
    if (msg.type === "steps") {
      const steps = (msg as unknown as Record<string, unknown>).steps as Record<string, unknown>[];
      for (const step of steps) {
        await this.processStep(step);
      }
    } else if (msg.type === "status") {
      const statusMsg = msg as { type: "status"; running: boolean };
      if (!statusMsg.running && !this.finalized) {
        await this.finalize();
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
          const sent = await this.api.sendMessage(this.chatId, chunks[i], {
            parse_mode: "HTML",
          });
          // Track only the last message for future edits
          if (i === chunks.length - 1) {
            this.session.streamMessageId = sent.message_id;
            this.session.streamBuffer = chunks[i];
          }
        } catch (err) {
          console.error(`[streamer] Send error: ${(err as Error).message}`);
          // Retry without parse mode (content may have unbalanced HTML tags)
          try {
            const sent = await this.api.sendMessage(this.chatId, chunks[i]);
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
        await this.api.editMessageText(
          this.chatId,
          this.session.streamMessageId,
          newText,
          { parse_mode: "HTML" },
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

  /** Finalize the stream — flush remaining content and clean up. */
  async finalize(): Promise<void> {
    if (this.finalized) return;
    this.finalized = true;

    if (this.session.flushTimer) {
      clearTimeout(this.session.flushTimer);
      this.session.flushTimer = null;
    }

    await this.flush();

    // Close WebSocket
    if (this.session.wsConnection) {
      this.session.wsConnection.close();
      this.session.wsConnection = null;
    }

    // Reset stream state for next message
    this.session.streamMessageId = null;
    this.session.streamBuffer = "";
  }
}
