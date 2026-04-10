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
  const items = resp.items as { textContent?: string }[] | undefined;
  if (!items || items.length === 0) return null;
  return items.map((i) => i.textContent ?? "").join("\n");
}

/** Format a single step into Telegram HTML text. */
export function formatStep(step: Record<string, unknown>): string | null {
  const status = step.status as string | undefined;

  // Planner response (the main AI text)
  const plannerText = getPlannerText(step);
  if (plannerText) {
    return escapeHtml(plannerText);
  }

  // Code action (file edit)
  const codeAction = step.codeAction as Record<string, unknown> | undefined;
  if (codeAction) {
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
    const cmd = (runCommand.commandLine as string) ?? "";
    let text = `⚡ <b>Command:</b> <code>${escapeHtml(truncate(cmd, 300))}</code>`;

    // If waiting for approval
    if (status === "CORTEX_STEP_STATUS_WAITING") {
      text += "\n\n⏳ <i>Menunggu persetujuan...</i>";
    }

    // Show output if available
    const output = runCommand.output as string | undefined;
    if (output) {
      text += `\n<pre>${escapeHtml(truncate(output, 1000))}</pre>`;
    }
    return text;
  }

  // View file
  const viewFile = step.viewFile as Record<string, unknown> | undefined;
  if (viewFile) {
    const file = viewFile.filePath as string | undefined;
    const name = file ? file.split("/").pop() : "file";
    return `👁 <b>View:</b> ${escapeHtml(name ?? "file")}`;
  }

  // Grep search
  const grepSearch = step.grepSearch as Record<string, unknown> | undefined;
  if (grepSearch) {
    const query = (grepSearch.query as string) ?? "";
    return `🔍 <b>Search:</b> <code>${escapeHtml(truncate(query, 200))}</code>`;
  }

  // List directory
  const listDir = step.listDirectory as Record<string, unknown> | undefined;
  if (listDir) {
    const dir = (listDir.directoryPath as string) ?? "";
    const name = dir.split("/").pop() || dir;
    return `📂 <b>List:</b> ${escapeHtml(name)}`;
  }

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

  while (remaining.length > 0) {
    if (remaining.length <= MAX_MSG_LEN) {
      chunks.push(remaining);
      break;
    }

    // Try to split at a newline near the limit
    let splitIdx = remaining.lastIndexOf("\n", MAX_MSG_LEN);
    if (splitIdx < MAX_MSG_LEN * 0.5) {
      // No good newline — hard split
      splitIdx = MAX_MSG_LEN;
    }

    chunks.push(remaining.slice(0, splitIdx));
    remaining = remaining.slice(splitIdx).replace(/^\n/, "");
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
