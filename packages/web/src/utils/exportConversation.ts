import type { TrajectoryStep } from "../types";
import { api } from "../api/client";

/**
 * Export a conversation as Markdown or JSON.
 * Fetches all steps from the API and formats them.
 */

/** Fetch all steps for a conversation. */
async function fetchAllSteps(chatId: string): Promise<TrajectoryStep[]> {
  const allSteps: TrajectoryStep[] = [];
  let offset = 0;
  const limit = 100;

  // eslint-disable-next-line no-constant-condition
  while (true) {
    const page = await api.getSteps(chatId, offset, limit);
    allSteps.push(...page.steps);
    if (page.steps.length < limit) break;
    offset += limit;
  }

  return allSteps;
}

/** Extract displayable text from a step. */
function extractStepContent(step: TrajectoryStep): {
  role: "user" | "assistant";
  text: string;
  isCode: boolean;
} | null {
  // User input
  if (step.userInput?.items?.length) {
    const text = step.userInput.items.map((it) => it.text ?? "").join("\n");
    if (text.trim()) return { role: "user", text, isCode: false };
  }

  // Planner / LLM response
  if (step.plannerResponse) {
    const text =
      step.plannerResponse.modifiedResponse ??
      step.plannerResponse.items?.map((it) => it.text ?? "").join("\n") ??
      "";
    if (text.trim()) return { role: "assistant", text, isCode: false };
  }

  // Code action
  if (step.codeAction?.description) {
    return {
      role: "assistant",
      text: step.codeAction.description,
      isCode: false,
    };
  }

  // Run command
  if (step.runCommand?.commandLine ?? step.runCommand?.proposedCommandLine) {
    const cmd =
      step.runCommand.commandLine ?? step.runCommand.proposedCommandLine ?? "";
    return { role: "assistant", text: `$ ${cmd}`, isCode: true };
  }

  return null;
}

/** Convert steps to Markdown format. */
function stepsToMarkdown(
  title: string,
  steps: TrajectoryStep[],
  exportDate: string,
): string {
  const lines: string[] = [
    `# ${title}`,
    "",
    `*Exported on ${exportDate}*`,
    "",
    "---",
    "",
  ];

  for (const step of steps) {
    const content = extractStepContent(step);
    if (!content) continue;

    lines.push(`### ${content.role === "user" ? "👤 User" : "🤖 Assistant"}`);
    lines.push("");

    if (content.isCode) {
      lines.push("```");
      lines.push(content.text);
      lines.push("```");
    } else {
      lines.push(content.text);
    }

    lines.push("");
  }

  return lines.join("\n");
}

/** Convert steps to JSON format. */
function stepsToJson(
  title: string,
  chatId: string,
  steps: TrajectoryStep[],
  exportDate: string,
): string {
  const messages = steps
    .map((step) => extractStepContent(step))
    .filter(Boolean);

  const exportData = {
    title,
    chatId,
    exportDate,
    messageCount: messages.length,
    messages,
  };

  return JSON.stringify(exportData, null, 2);
}

/** Trigger a file download in the browser. */
function downloadFile(content: string, filename: string, type: string) {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export type ExportFormat = "markdown" | "json";

/** Export a conversation and trigger download. */
export async function exportConversation(
  chatId: string,
  title: string,
  format: ExportFormat,
): Promise<void> {
  const steps = await fetchAllSteps(chatId);
  const exportDate = new Date().toISOString();
  const safeTitle = title.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 50);

  if (format === "markdown") {
    const content = stepsToMarkdown(title, steps, exportDate);
    downloadFile(content, `${safeTitle}.md`, "text/markdown");
  } else {
    const content = stepsToJson(title, chatId, steps, exportDate);
    downloadFile(content, `${safeTitle}.json`, "application/json");
  }
}
