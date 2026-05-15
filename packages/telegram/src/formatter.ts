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
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** Truncate text to a max length, adding ellipsis if needed. */
function truncate(text: string, max: number): string {
  if (text.length <= max) return text;
  return text.slice(0, max - 3) + "...";
}

/**
 * Convert markdown text to Telegram-safe HTML.
 * Handles: code blocks, inline code, bold, italic, strikethrough, links, headers.
 * Protects code content from being processed by other formatters.
 */
export function markdownToTelegramHtml(md: string): string {
  // 1. Extract code blocks and inline code first (protect from other formatting)
  const codeBlocks: string[] = [];
  const CODE_PLACEHOLDER = "\x00CB";

  // Fenced code blocks: ```lang\n...\n```
  let text = md.replace(/```(\w*)\n?([\s\S]*?)```/g, (_match, lang, code) => {
    const idx = codeBlocks.length;
    const langAttr = lang ? ` class="language-${escapeHtml(lang)}"` : "";
    codeBlocks.push(`<pre><code${langAttr}>${escapeHtml(code.trimEnd())}</code></pre>`);
    return `${CODE_PLACEHOLDER}${idx}${CODE_PLACEHOLDER}`;
  });

  // Inline code: `...`
  text = text.replace(/`([^`\n]+)`/g, (_match, code) => {
    const idx = codeBlocks.length;
    codeBlocks.push(`<code>${escapeHtml(code)}</code>`);
    return `${CODE_PLACEHOLDER}${idx}${CODE_PLACEHOLDER}`;
  });

  // 2. Escape remaining HTML entities
  text = escapeHtml(text);

  // 3. Apply markdown formatting (order matters!)
  // Headers → bold (Telegram doesn't support headers natively)
  text = text.replace(/^#{1,6}\s+(.+)$/gm, "<b>$1</b>");

  // Bold: **text** or __text__
  text = text.replace(/\*\*(.+?)\*\*/g, "<b>$1</b>");
  text = text.replace(/__(.+?)__/g, "<b>$1</b>");

  // Italic: *text* or _text_ (but not inside words like file_name)
  text = text.replace(/(?<!\w)\*([^*\n]+?)\*(?!\w)/g, "<i>$1</i>");
  text = text.replace(/(?<!\w)_([^_\n]+?)_(?!\w)/g, "<i>$1</i>");

  // Strikethrough: ~~text~~
  text = text.replace(/~~(.+?)~~/g, "<s>$1</s>");

  // Links: [text](url)
  text = text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');

  // Horizontal rules → newline
  text = text.replace(/^---+$/gm, "");

  // Bullet points: clean up markdown list markers  
  text = text.replace(/^(\s*)[*-]\s+/gm, "$1• ");

  // Numbered lists: keep as-is (already readable)

  // 4. Restore code blocks
  text = text.replace(new RegExp(`${CODE_PLACEHOLDER}(\\d+)${CODE_PLACEHOLDER}`, "g"), (_match, idx) => {
    return codeBlocks[parseInt(idx, 10)] ?? "";
  });

  return text;
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

/** Extract basename from a URI or path. */
function basename(uriOrPath: string): string {
  const cleaned = uriOrPath.replace(/^file:\/\//, "");
  return cleaned.split("/").pop() ?? cleaned;
}

/**
 * Format a single step into Telegram HTML text.
 *
 * Mirrors the Porta Web frontend's `stepsToMessages.ts` — every step type
 * that the web shows as a card gets a formatted Telegram representation.
 *
 * Return value meanings:
 *   string       → renderable text (may be "content" or "tool" type)
 *   null         → skip this step (not renderable yet or empty)
 */
export function formatStep(step: Record<string, unknown>): string | null {
  const status = step.status as string | undefined;

  // ── Planner Response (main AI text) ──
  // Only render when DONE — during GENERATING the text is still being streamed.
  if (status === "CORTEX_STEP_STATUS_DONE") {
    const resp = step.plannerResponse as Record<string, unknown> | undefined;
    if (resp) {
      const plannerText = getPlannerText(step);
      const parts: string[] = [];

      // Thinking block — mirrors web's collapsible <details> ThinkingBlock
      const thinking = resp.thinking as string | undefined;
      const thinkingDuration = resp.thinkingDuration as string | undefined;
      if (thinking && thinking.trim()) {
        let durationLabel = "";
        if (thinkingDuration) {
          const match = thinkingDuration.match(/([\d.]+)s/);
          if (match) durationLabel = ` (${parseFloat(match[1]).toFixed(1)}s)`;
        }
        const snippet = thinking.trim().slice(0, 150).replace(/\n/g, " ");
        parts.push(
          `💭 <b>Thinking${durationLabel}</b>\n` +
          `<tg-spoiler><i>${escapeHtml(snippet)}${thinking.length > 150 ? "..." : ""}</i></tg-spoiler>`
        );
      }

      if (plannerText) {
        parts.push(markdownToTelegramHtml(plannerText));
      }

      return parts.length > 0 ? parts.join("\n\n") : null;
    }
  }

  // ── Code Action (file edit) ──
  // Mirrors web's CodeActionCard: icon, diff stats, file name, description, inline diff
  const codeAction = step.codeAction as Record<string, unknown> | undefined;
  if (codeAction) {
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;

    // File info
    const actionResult = codeAction.actionResult as Record<string, unknown> | undefined;
    const edit = actionResult?.edit as Record<string, unknown> | undefined;
    const fileUri = (edit?.absoluteUri as string) ?? (codeAction.filePath as string) ?? "";
    const name = fileUri ? basename(fileUri) : "file";
    const desc = codeAction.description as string | undefined;
    const isCreate = edit?.createFile as boolean | undefined;

    // Full path for context (mirrors web showing full path in diff header)
    const fullPath = fileUri ? fileUri.replace("file://", "") : "";
    const shortPath = fullPath.length > 60 ? "…" + fullPath.slice(-55) : fullPath;

    // Diff stats — mirrors web's +N/-N
    const diff = edit?.diff as Record<string, unknown> | undefined;
    const unifiedDiff = diff?.unifiedDiff as Record<string, unknown> | undefined;
    const diffLines = (unifiedDiff?.lines as { type?: string; text?: string }[]) ?? [];
    const additions = diffLines.filter(l => l.type === "UNIFIED_DIFF_LINE_TYPE_INSERT").length;
    const deletions = diffLines.filter(l => l.type === "UNIFIED_DIFF_LINE_TYPE_DELETE").length;

    const icon = isCreate ? "📄" : "📝";
    let text = `${icon} <b>${isCreate ? "Create" : "Edit"}:</b> <code>${escapeHtml(name)}</code>`;
    if (additions > 0 || deletions > 0) {
      text += `  <code>+${additions} -${deletions}</code>`;
    }
    if (shortPath && shortPath !== name) {
      text += `\n📁 <code>${escapeHtml(shortPath)}</code>`;
    }
    if (desc) text += `\n<i>${escapeHtml(truncate(desc, 200))}</i>`;

    // Inline diff preview — mirrors web's expandable diff view
    // Show changed lines only (inserts + deletes), max 15 lines
    const changedLines = diffLines.filter(
      l => l.type === "UNIFIED_DIFF_LINE_TYPE_INSERT" || l.type === "UNIFIED_DIFF_LINE_TYPE_DELETE"
    );
    if (changedLines.length > 0) {
      const MAX_DIFF_LINES = 15;
      const previewLines = changedLines.slice(0, MAX_DIFF_LINES);
      const diffText = previewLines.map(l => {
        const prefix = l.type === "UNIFIED_DIFF_LINE_TYPE_INSERT" ? "+" : "-";
        return `${prefix} ${l.text ?? ""}`;
      }).join("\n");
      const truncatedNote = changedLines.length > MAX_DIFF_LINES
        ? `\n... +${changedLines.length - MAX_DIFF_LINES} more lines`
        : "";
      text += `\n<tg-spoiler><pre>${escapeHtml(diffText + truncatedNote)}</pre></tg-spoiler>`;
    }

    return text;
  }

  // ── Run Command ──
  // Mirrors web's CommandCard: command, CWD, exit code, collapsible output
  const runCommand = step.runCommand as Record<string, unknown> | undefined;
  if (runCommand) {
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;

    const cmd = (runCommand.commandLine as string) ?? "";
    let text = `⚡ <b>Command:</b> <code>${escapeHtml(truncate(cmd, 300))}</code>`;

    // CWD — mirrors web's step-card-cwd
    const cwd = runCommand.cwd as string | undefined;
    if (cwd) {
      const cwdShort = basename(cwd);
      text += `\n📁 <code>${escapeHtml(cwdShort)}</code>`;
    }

    // Exit code
    const exitCode = runCommand.exitCode as number | undefined;
    if (exitCode !== undefined && exitCode !== 0) {
      text += `  ❌ <i>exit ${exitCode}</i>`;
    }

    // Output
    const output = runCommand.output as string | undefined;
    if (output) {
      const MAX_OUTPUT = 2000;
      const SPOILER_THRESHOLD = 500;
      let displayOutput: string;
      if (output.length > MAX_OUTPUT) {
        const truncatedLines = output.slice(-MAX_OUTPUT);
        displayOutput = `[...${output.length - MAX_OUTPUT} chars truncated]\n${truncatedLines}`;
      } else {
        displayOutput = output;
      }
      if (displayOutput.length > SPOILER_THRESHOLD) {
        text += `\n<tg-spoiler><pre>${escapeHtml(displayOutput)}</pre></tg-spoiler>`;
        text += `\n<i>📎 Tap untuk lihat output (${output.length} chars)</i>`;
      } else {
        text += `\n<pre>${escapeHtml(displayOutput)}</pre>`;
      }
    }
    return text;
  }

  // ── View File ──
  // Mirrors web: "Viewed filename #L5-L20" with eye icon
  const viewFile = step.viewFile as Record<string, unknown> | undefined;
  if (viewFile) {
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;
    const uri = (viewFile.absolutePathUri as string) ?? "";
    const name = uri ? basename(uri) : "file";
    const startLine = viewFile.startLine as number | undefined;
    const endLine = viewFile.endLine as number | undefined;
    const range = startLine && endLine ? ` #L${startLine}-${endLine}` : "";
    return `👁 <b>Read:</b> <code>${escapeHtml(name)}${range}</code>`;
  }

  // ── Grep Search ──
  // Mirrors web: "Searched `query` in dir — N results"
  const grepSearch = step.grepSearch as Record<string, unknown> | undefined;
  if (grepSearch) {
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;
    const query = (grepSearch.query as string) ?? "";
    const results = (grepSearch.results as unknown[]) ?? [];
    const searchPath = ((grepSearch.searchPathUri as string) ?? "").replace("file://", "");
    const pathLabel = searchPath ? basename(searchPath) : "";
    let text = `🔍 <b>Search:</b> <code>${escapeHtml(truncate(query, 40))}</code>`;
    if (pathLabel) text += ` in <code>${escapeHtml(pathLabel)}</code>`;
    text += ` — ${results.length} result${results.length !== 1 ? "s" : ""}`;
    return text;
  }

  // ── List Directory ──
  // Mirrors web: "Listed dir/ — N items"
  const listDirectory = step.listDirectory as Record<string, unknown> | undefined;
  if (listDirectory) {
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;
    const uri = ((listDirectory.directoryPathUri as string) ?? "").replace("file://", "");
    const name = uri ? basename(uri) : "dir";
    const results = (listDirectory.results as unknown[]) ?? [];
    return `📂 <b>Listed:</b> <code>${escapeHtml(name)}/</code> — ${results.length} items`;
  }

  // ── View File Outline ──
  // Mirrors web: "Outlined filename"
  const viewFileOutline = step.viewFileOutline as Record<string, unknown> | undefined;
  if (viewFileOutline) {
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;
    const uri = ((viewFileOutline.absolutePathUri as string) ?? "").replace("file://", "");
    const name = uri ? basename(uri) : "file";
    return `📋 <b>Outline:</b> <code>${escapeHtml(name)}</code>`;
  }

  // ── View Code Item ──
  // Mirrors web: "Analyzed filename → nodes"
  const viewCodeItem = step.viewCodeItem as Record<string, unknown> | undefined;
  if (viewCodeItem) {
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;
    const uri = ((viewCodeItem.absoluteUri as string) ?? "").replace("file://", "");
    const name = uri ? basename(uri) : "file";
    const nodes = (viewCodeItem.nodePaths as string[]) ?? [];
    let text = `🔎 <b>Analyzed:</b> <code>${escapeHtml(name)}</code>`;
    if (nodes.length > 0) text += ` → ${escapeHtml(nodes.slice(0, 3).join(", "))}${nodes.length > 3 ? "..." : ""}`;
    return text;
  }

  // ── Find ──
  // Mirrors web: "Find `pattern` — N results"
  const find = step.find as Record<string, unknown> | undefined;
  if (find) {
    if (status !== "CORTEX_STEP_STATUS_DONE") return null;
    const pattern = (find.pattern as string) ?? "*";
    const results = (find.results as unknown[]) ?? [];
    return `🔍 <b>Find:</b> <code>${escapeHtml(pattern)}</code> — ${results.length} result${results.length !== 1 ? "s" : ""}`;
  }

  // ── Send Command Input ──
  // Mirrors web: termination or input indicator
  const sendCommandInput = step.sendCommandInput as Record<string, unknown> | undefined;
  if (sendCommandInput) {
    if (sendCommandInput.terminate) {
      return `⏹ <i>Command terminated</i>`;
    }
    return `⌨️ <i>Input sent</i>`;
  }

  // ── File Permission Request (waiting) ──
  const fpr = step.filePermissionRequest as Record<string, unknown> | undefined;
  if (fpr && status === "CORTEX_STEP_STATUS_WAITING") {
    const path = (fpr.absolutePathUri as string) ?? "unknown";
    const blockReason = fpr.blockReason as string | undefined;
    let text = `🔐 <b>File access:</b> <code>${escapeHtml(basename(path))}</code>`;
    if (blockReason) {
      const reason = blockReason.replace("BLOCK_REASON_", "").replace(/_/g, " ").toLowerCase();
      text += `\n<i>${escapeHtml(reason)}</i>`;
    }
    text += `\n\n⏳ <i>Menunggu persetujuan...</i>`;
    return text;
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
 * Check if a formatted step is a "tool action" (compact one-liner)
 * vs "content" (main response text that should be in the stream buffer).
 * Tool actions are: viewFile, grepSearch, listDir, codeAction, command, etc.
 */
export function isToolStep(step: Record<string, unknown>): boolean {
  return !!(
    step.viewFile ||
    step.grepSearch ||
    step.listDirectory ||
    step.viewFileOutline ||
    step.viewCodeItem ||
    step.find ||
    step.sendCommandInput ||
    step.codeAction ||
    step.runCommand
  );
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
  const tagPattern = /<\/?(b|i|code|pre|s|a|tg-spoiler)\b[^>]*>/g;

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

    // Avoid splitting inside <pre> blocks — find safe split before <pre> if we're mid-block
    const preBeforeSplit = remaining.lastIndexOf("<pre>", splitIdx);
    const preEndBeforeSplit = remaining.lastIndexOf("</pre>", splitIdx);
    if (preBeforeSplit > preEndBeforeSplit && preBeforeSplit < splitIdx) {
      // We're inside a <pre> block — try to split before it
      const saferIdx = remaining.lastIndexOf("\n", preBeforeSplit);
      if (saferIdx > targetLimit * 0.3) {
        splitIdx = saferIdx;
      }
      // If no safe point, just proceed with the original split (better than infinite loop)
    }

    let chunk = remaining.slice(0, splitIdx);
    
    // Parse tags in this chunk to update the stack
    let match;
    const chunkTagPattern = /<\/?(b|i|code|pre|s|a|tg-spoiler)\b[^>]*>/g;
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
