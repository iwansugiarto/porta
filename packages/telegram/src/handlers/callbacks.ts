import type { Bot } from "grammy";
import type { TelegramConfig } from "../config.js";
import type { PortaClient } from "../porta-client.js";
import { getSession, createSession, switchSession } from "../session.js";
import { escapeHtml } from "../formatter.js";

export function registerCallbackHandlers(
  bot: Bot,
  client: PortaClient,
  config: TelegramConfig,
): void {
  bot.on("callback_query:data", async (ctx, next) => {
    const data = ctx.callbackQuery.data;

    if (data.startsWith("ws:") || data.startsWith("wl:") || data.startsWith("wn:") || 
        data.startsWith("fd:") || data.startsWith("ff:") || data.startsWith("fp:")) {
      return next(); // handled by workspace and file handlers
    }

    if (data.startsWith("use:")) {
      const cascadeId = data.slice(4);
      const chatId = ctx.chat!.id;

      const prevSession = getSession(chatId);
      const prevWorkspace = prevSession?.workspaceUri;

      switchSession(chatId, cascadeId);

      const newSession = getSession(chatId);
      if (newSession && prevWorkspace) {
        newSession.workspaceUri = prevWorkspace;
      }

      const shortId = cascadeId.slice(0, 8);
      await ctx.answerCallbackQuery({ text: `Switched ke ${shortId}` });
      try {
        await ctx.editMessageText(
          `✅ Switched ke conversation: <code>${shortId}</code>\n\n` +
            "<i>Kirim pesan untuk melanjutkan chat.</i>",
          { parse_mode: "HTML" },
        );
      } catch { /* message unchanged */ }
      return;
    }

    if (data.startsWith("model:")) {
      const modelId = data.slice(6);
      const chatId = ctx.chat!.id;
      let session = getSession(chatId);
      if (!session) {
        try {
          const result = await client.createConversation(config.workspaceUri);
          session = createSession(chatId, result.cascadeId);
        } catch {
          await ctx.answerCallbackQuery({ text: "Gagal membuat session" });
          return;
        }
      }
      session.selectedModel = modelId;
      let label = modelId;
      try {
        const models = await client.listModels();
        const match = models.find((m) => m.name === modelId);
        if (match) label = match.displayName;
      } catch { /* use raw id */ }
      await ctx.answerCallbackQuery({ text: `Model: ${label}` });
      try {
        await ctx.editMessageText(
          `✅ Model diubah ke: <b>${escapeHtml(label)}</b>`,
          { parse_mode: "HTML" },
        );
      } catch { /* message unchanged */ }
      return;
    }

    if (data.startsWith("stop:")) {
      const cascadeId = data.slice(5);
      try {
        await client.stopConversation(cascadeId);
        await ctx.answerCallbackQuery({ text: "🛑 Task cancelled" });
        const currentText = ctx.callbackQuery.message?.text ?? "";
        await ctx.editMessageText(currentText + "\n\n<i>🛑 Task cancelled.</i>", {
          parse_mode: "HTML",
          reply_markup: undefined,
        });
      } catch (err) {
        await ctx.answerCallbackQuery({
          text: `Error: ${(err as Error).message.slice(0, 100)}`,
        });
      }
      return;
    }

    // Handle approve/reject
    const parts = data.split(":");
    if (parts.length >= 5) {
      const [action, approvalType, cascadeId, trajectoryId, stepIndexStr] = parts;
      const stepIndex = parseInt(stepIndexStr, 10);

      if (action === "approve" || action === "reject") {
        // Check if already handled (prevent double-click)
        const msgText = ctx.callbackQuery.message?.text ?? "";
        if (msgText.includes("✅ Approved") || msgText.includes("❌ Rejected")) {
          await ctx.answerCallbackQuery({ text: "⚠️ Already handled" });
          return;
        }

        try {
          const timestamp = new Date().toLocaleTimeString("id-ID", { hour: "2-digit", minute: "2-digit" });

          if (action === "approve") {
            if (approvalType === "command") {
              await client.approveCommand(cascadeId, trajectoryId, stepIndex);
            } else if (approvalType === "file") {
              const pathMatch = msgText.match(/`([^`]+)`/);
              const filePath = pathMatch?.[1] ?? "";
              await client.approveFileAccess(cascadeId, trajectoryId, stepIndex, filePath);
            }
            await ctx.answerCallbackQuery({ text: "✅ Approved" });
            try {
              await ctx.editMessageText(
                msgText + `\n\n✅ <b>Approved</b> <i>(${timestamp})</i>`,
                { parse_mode: "HTML", reply_markup: undefined },
              );
            } catch { /* message unchanged */ }
          } else {
            if (approvalType === "command") {
              await client.rejectCommand(cascadeId, trajectoryId, stepIndex);
            } else if (approvalType === "file") {
              const pathMatch = msgText.match(/`([^`]+)`/);
              const filePath = pathMatch?.[1] ?? "";
              await client.rejectFileAccess(cascadeId, trajectoryId, stepIndex, filePath);
            }
            await ctx.answerCallbackQuery({ text: "❌ Rejected" });
            try {
              await ctx.editMessageText(
                msgText + `\n\n❌ <b>Rejected</b> <i>(${timestamp})</i>`,
                { parse_mode: "HTML", reply_markup: undefined },
              );
            } catch { /* message unchanged */ }
          }
        } catch (err) {
          await ctx.answerCallbackQuery({
            text: `Error: ${(err as Error).message.slice(0, 100)}`,
          });
        }
      }
    }
  });
}
