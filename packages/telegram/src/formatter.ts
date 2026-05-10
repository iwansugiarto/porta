/**
 * Step data → Telegram message formatter.
 *
 * Converts Antigravity step objects into Telegram-friendly text.
 * Handles the 4096-character message limit by splitting long content.
 *
 * Uses Telegram HTML parse mode for reliability — MarkdownV2 has too
 * many special-character escaping pitfalls with code content.
 */

/** Maximum chars per Telegram message (with buffer for formatting). */
const MAX_MSG_LEN = 4000;

/** Escape HTML special characters for Telegram HTML parse mode. */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

/** Truncate text to a max length, adding ellipsis if needed. */
function truncate(text: string, max: number): string {
  if (text.length <= max) return text;
  return text.slice(0, max - 3) + "...";
}

/** Extract the planner response text from a step. */
function getPlannerText(step: Record<string, unknown>): string | null {
  const resp = step.plannerResponse as Record<string, unknown> | undefined;
  if (!resp) return null;

  // Current LS format: response / modifiedResponse string fields
  const text = (resp.modifiedResponse as string) ?? (resp.response as string);
  if (text) return text;

  // Legacy format: items[].textContent
  const items = resp.items as { textContent?: string }[] | undefined;
  if (items && items.length > 0) {
    return items.map((i) => i.textContent ?? "").join("\n");
  }

  return null;
}

/** Format a single step into Telegram HTML text. */
export function formatStep(step: Record<string, unknown>): string | null {
  const status = step.status as string | undefined;

  // Planner response (the main AI text)
  // Only render when DONE — during GENERATING the text is still being streamed
  // and will produce duplicates when re-polled with new content.
  if (status === "CORTEX_STEP_STATUS_DONE") {
    const plannerText = getPlannerText(step);
    if (plannerText) {
      return escapeHtml(plannerText);
    }
  }

  // Code action (file edit) — only render when DONE
  const codeAction = step.codeAction as Record<string, unknown> | undefined;
  if (codeAction) {
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;
    const file = codeAction.filePath as string | undefined;
    const desc = codeAction.description as string | undefined;
    const name = file ? file.split("/").pop() : "file";
    let text = `📝 <b>Edit: ${escapeHtml(name ?? "file")}</b>`;
    if (desc) text += `\n${escapeHtml(truncate(desc, 200))}`;
    return text;
  }

  // Run command
  const runCommand = step.runCommand as Record<string, unknown> | undefined;
  if (runCommand) {
    // Skip commands that are still in-progress (no output yet).
    // WAITING commands are handled by the approval system (getApprovalInfo).
    // Only render when DONE so we get the complete output in one message.
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;

    const cmd = (runCommand.commandLine as string) ?? "";
    let text = `⚡ <b>Command:</b> <code>${escapeHtml(truncate(cmd, 300))}</code>`;

    // Show exit code if non-zero
    const exitCode = runCommand.exitCode as number | undefined;
    if (exitCode !== undefined && exitCode !== 0) {
      text += ` <i>(exit ${exitCode})</i>`;
    }

    // Show output if available
    const output = runCommand.output as string | undefined;
    if (output) {
      const MAX_OUTPUT = 2000;
      let displayOutput: string;
      if (output.length > MAX_OUTPUT) {
        const truncatedLines = output.slice(-MAX_OUTPUT);
        displayOutput = `[...${output.length - MAX_OUTPUT} chars truncated]\n${truncatedLines}`;
      } else {
        displayOutput = output;
      }
      text += `\n<pre>${escapeHtml(displayOutput)}</pre>`;
    }
    return text;
  }

  // View file — skip (noise for Telegram)
  if (step.viewFile) return null;

  // Grep search — skip (noise for Telegram)
  if (step.grepSearch) return null;

  // List directory — skip (noise for Telegram)
  if (step.listDirectory) return null;

  // File permission request (waiting)
  const fpr = step.filePermissionRequest as Record<string, unknown> | undefined;
  if (fpr && status === "CORTEX_STEP_STATUS_WAITING") {
    const path = (fpr.absolutePathUri as string) ?? "unknown";
    return `🔐 <b>File access:</b> ${escapeHtml(path)}\n\n⏳ <i>Menunggu persetujuan...</i>`;
  }

  // Placeholder / error steps (from step recovery)
  const placeholder = step._placeholder as boolean | undefined;
  if (placeholder) {
    const reason = (step._reason as string) ?? "Skipped";
    return `⚠️ <i>${escapeHtml(reason)}</i>`;
  }

  // Unknown step type with a status — show status
  if (status === "CORTEX_STEP_STATUS_WAITING") {
    return "⏳ <i>Menunggu persetujuan...</i>";
  }
  if (status === "CORTEX_STEP_STATUS_ERROR") {
    const errorObj = step.error as Record<string, unknown> | undefined;
    const errorMsg = (errorObj?.message as string) ?? (errorObj?.errorMessage as string) ?? "";
    const errorCode = errorObj?.code as string | undefined;
    if (errorMsg) {
      const codeStr = errorCode ? ` [${escapeHtml(errorCode)}]` : "";
      return `❌ <b>Error${codeStr}:</b> ${escapeHtml(truncate(errorMsg, 300))}`;
    }
    return "❌ <i>Step gagal (error)</i>";
  }

  // Unknown or empty step — skip
  return null;
}

/**
 * Split a long message into multiple Telegram-safe chunks.
 * Splits at newlines when possible, falling back to hard splits.
 */
export function splitMessage(text: string): string[] {
  if (text.length <= MAX_MSG_LEN) return [text];

  const chunks: string[] = [];
  let remaining = text;
  
  // Track open tags across splits
  const openTags: string[] = [];
  const tagPattern = /<\/?(b|i|code|pre)>/g;

  while (remaining.length > 0) {
    // If the remaining text is small enough, plus the closing tags, we can just finish
    const closingTagsStr = openTags.map(t => `</${t}>`).reverse().join('');
    if (remaining.length + closingTagsStr.length <= MAX_MSG_LEN) {
      chunks.push(remaining + closingTagsStr);
      break;
    }

    // Try to split at a newline near the limit, reserving space for closing tags
    const targetLimit = MAX_MSG_LEN - closingTagsStr.length;
    let splitIdx = remaining.lastIndexOf("\n", targetLimit);
    
    if (splitIdx < targetLimit * 0.5) {
      // No good newline — hard split
      splitIdx = targetLimit;
    }

    let chunk = remaining.slice(0, splitIdx);
    
    // Parse tags in this chunk to update the stack
    let match;
    const chunkTagPattern = /<\/?(b|i|code|pre)>/g;
    while ((match = chunkTagPattern.exec(chunk)) !== null) {
      const tagStr = match[0];
      const isClosing = tagStr.startsWith("</");
      const tagName = match[1];
      
      if (isClosing) {
        // Pop from stack if it matches
        const lastIdx = openTags.lastIndexOf(tagName);
        if (lastIdx !== -1) {
          openTags.splice(lastIdx, 1);
        }
      } else {
        openTags.push(tagName);
      }
    }

    // Append closing tags for currently open tags
    const chunkClosingStr = openTags.map(t => `</${t}>`).reverse().join('');
    chunks.push(chunk + chunkClosingStr);
    
    // Prepare remaining text with opening tags
    const chunkOpeningStr = openTags.map(t => `<${t}>`).join('');
    remaining = chunkOpeningStr + remaining.slice(splitIdx).replace(/^\n/, "");
  }

  return chunks;
}

/**
 * Check if a step is in WAITING status (needs user approval).
 * Returns approval metadata if found, null otherwise.
 */
export function getApprovalInfo(
  step: Record<string, unknown>,
): {
  type: "command" | "file";
  trajectoryId: string;
  stepIndex: number;
  detail: string;
} | null {
  if (step.status !== "CORTEX_STEP_STATUS_WAITING") return null;

  const meta = step.metadata as
    | { sourceTrajectoryStepInfo?: { trajectoryId?: string; stepIndex?: number } }
    | undefined;
  const trajectoryId = meta?.sourceTrajectoryStepInfo?.trajectoryId;
  const stepIndex = meta?.sourceTrajectoryStepInfo?.stepIndex;
  if (!trajectoryId || stepIndex === undefined) return null;

  // Command approval
  const runCommand = step.runCommand as Record<string, unknown> | undefined;
  if (runCommand) {
    const cmd = (runCommand.commandLine as string) ?? "command";
    return { type: "command", trajectoryId, stepIndex, detail: cmd };
  }

  // File permission approval — check all possible locations
  const fpr = (
    step.filePermissionRequest ??
    (step.viewFile as Record<string, unknown> | undefined)?.filePermissionRequest ??
    (step.listDirectory as Record<string, unknown> | undefined)?.filePermissionRequest ??
    (step.codeAction as Record<string, unknown> | undefined)?.filePermissionRequest ??
    (step.grepSearch as Record<string, unknown> | undefined)?.filePermissionRequest
  ) as { absolutePathUri?: string } | undefined;

  if (fpr?.absolutePathUri) {
    return {
      type: "file",
      trajectoryId,
      stepIndex,
      detail: fpr.absolutePathUri,
    };
  }

  return null;
}
