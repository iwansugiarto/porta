import type { Bot } from "grammy";
import type { TelegramConfig } from "../config.js";
import type { PortaClient } from "../porta-client.js";
import { getSession, createSession, switchSession, getBookmarks } from "../session.js";
import { escapeHtml, splitMessage } from "../formatter.js";

export function registerCallbackHandlers(
  bot: Bot,
  client: PortaClient,
  config: TelegramConfig,
): void {
  // ── noop handler — for workspace group headers in /list ──
  bot.callbackQuery(/^noop:/, async (ctx) => {
    await ctx.answerCallbackQuery();
  });

  // ── Delete confirmation handlers ──
  bot.callbackQuery(/^del_confirm:(.+)$/, async (ctx) => {
    const cascadeId = ctx.match[1];
    const chatId = ctx.chat!.id;
    const shortId = cascadeId.slice(0, 8);

    try {
      await client.deleteConversation(cascadeId);

      // If deleted conversation was active, clear session
      const session = getSession(chatId);
      if (session?.cascadeId === cascadeId) {
        // Try to switch to another conversation
        const conversations = await client.listConversations();
        const next = conversations.find(c => c.id !== cascadeId);
        if (next) {
          switchSession(chatId, next.id);
        }
      }

      await ctx.answerCallbackQuery({ text: "✅ Deleted" });
      await ctx.editMessageText(
        `🗑 Conversation <code>${shortId}</code> telah dihapus.`,
        { parse_mode: "HTML" },
      );
    } catch (err) {
      await ctx.answerCallbackQuery({
        text: `Error: ${(err as Error).message.slice(0, 100)}`,
      });
    }
  });

  bot.callbackQuery(/^del_cancel:/, async (ctx) => {
    await ctx.answerCallbackQuery({ text: "Dibatalkan" });
    try {
      await ctx.editMessageText("🗑 Delete dibatalkan.", { parse_mode: "HTML" });
    } catch { /* message unchanged */ }
  });

  // ── Settings panel toggle callbacks ──
  bot.callbackQuery(/^set:(.+)$/, async (ctx) => {
    const action = ctx.match[1];
    const chatId = ctx.chat!.id;
    const session = getSession(chatId);

    if (action === "planner") {
      if (!session) {
        await ctx.answerCallbackQuery({ text: "Tidak ada session aktif" });
        return;
      }
      session.plannerType = session.plannerType === "planning" ? "conversational" : "planning";
      const label = session.plannerType === "planning" ? "📋 Plan" : "⚡ Fast";
      await ctx.answerCallbackQuery({ text: `Mode: ${label}` });

      // Rebuild settings message
      await rebuildSettingsMessage(ctx, session, client, config);
    } else if (action === "quiet") {
      if (!session) {
        await ctx.answerCallbackQuery({ text: "Tidak ada session aktif" });
        return;
      }
      session.quietMode = !session.quietMode;
      const label = session.quietMode ? "🔇 ON" : "🔔 OFF";
      await ctx.answerCallbackQuery({ text: `Quiet: ${label}` });
      await rebuildSettingsMessage(ctx, session, client, config);
    } else if (action === "autoapprove") {
      try {
        const health = await client.getHealth();
        const newState = !health.autoApprove;
        await client.setAutoApprove(newState);
        await ctx.answerCallbackQuery({ text: `Auto-approve: ${newState ? "ON" : "OFF"}` });
        if (session) await rebuildSettingsMessage(ctx, session, client, config);
      } catch (err) {
        await ctx.answerCallbackQuery({ text: `Error: ${(err as Error).message.slice(0, 80)}` });
      }
    } else if (action === "compact") {
      if (!session) {
        await ctx.answerCallbackQuery({ text: "Tidak ada session aktif" });
        return;
      }
      session.compactMode = !session.compactMode;
      const label = session.compactMode ? "📦 ON" : "📋 OFF";
      await ctx.answerCallbackQuery({ text: `Compact: ${label}` });
      await rebuildSettingsMessage(ctx, session, client, config);
    } else if (action === "models") {
      await ctx.answerCallbackQuery({ text: "Gunakan /models untuk memilih model" });
    } else if (action === "workspace") {
      await ctx.answerCallbackQuery({ text: "Gunakan /workspace untuk switch" });
    } else if (action === "refresh") {
      if (session) {
        await ctx.answerCallbackQuery({ text: "Refreshed ✅" });
        await rebuildSettingsMessage(ctx, session, client, config);
      } else {
        await ctx.answerCallbackQuery({ text: "Tidak ada session aktif" });
      }
    }
  });

  /** Rebuild the settings message in-place after a toggle. */
  async function rebuildSettingsMessage(
    ctx: any,
    session: ReturnType<typeof getSession>,
    portaClient: PortaClient,
    portaConfig: TelegramConfig,
  ) {
    if (!session) return;
    const model = session.selectedModel ?? "(default)";
    const planner = session.plannerType ?? "conversational";
    const plannerLabel = planner === "planning" ? "📋 Plan" : "⚡ Fast";
    const quietLabel = session.quietMode ? "🔇 ON" : "🔔 OFF";
    const wsUri = session.workspaceUri ?? portaConfig.workspaceUri ?? "(none)";
    const shortWs = wsUri.replace(/^file:\/\//, "").split("/").pop() ?? wsUri;
    const convId = session.cascadeId?.slice(0, 8) ?? "(none)";

    let autoApproveLabel = "❓ Unknown";
    try {
      const health = await portaClient.getHealth();
      autoApproveLabel = health.autoApprove ? "✅ ON" : "❌ OFF";
    } catch { /* ignore */ }

    const compactLabel = session.compactMode ? "📦 ON" : "📋 OFF";

    const text =
      `⚙️ <b>Settings</b>\n\n` +
      `🤖 <b>Model:</b> <code>${escapeHtml(model)}</code>\n` +
      `${plannerLabel} <b>Mode:</b> ${planner === "planning" ? "Plan (multi-step)" : "Fast (single-step)"}\n` +
      `📂 <b>Workspace:</b> <code>${escapeHtml(shortWs)}</code>\n` +
      `${quietLabel.split(" ")[0]} <b>Quiet:</b> ${quietLabel}\n` +
      `${compactLabel.split(" ")[0]} <b>Compact:</b> ${compactLabel}\n` +
      `🔓 <b>Auto-approve:</b> ${autoApproveLabel}\n` +
      `💬 <b>Conversation:</b> <code>${convId}</code>\n\n` +
      `<i>Tap tombol di bawah untuk mengubah:</i>`;

    const { InlineKeyboard } = await import("grammy");
    const keyboard = new InlineKeyboard()
      .text(`🤖 Model`, `set:models`)
      .text(`${plannerLabel}`, `set:planner`)
      .row()
      .text(`${quietLabel.split(" ")[0]} Quiet: ${session.quietMode ? "ON" : "OFF"}`, `set:quiet`)
      .text(`${compactLabel.split(" ")[0]} Compact: ${session.compactMode ? "ON" : "OFF"}`, `set:compact`)
      .row()
      .text(`🔓 Auto: ${autoApproveLabel.includes("ON") ? "ON" : "OFF"}`, `set:autoapprove`)
      .text(`🔄 Refresh`, `set:refresh`)
      .row()
      .text(`📂 Workspace`, `set:workspace`);

    try {
      await ctx.editMessageText(text, { parse_mode: "HTML", reply_markup: keyboard });
    } catch { /* message unchanged */ }
  }

  bot.on("callback_query:data", async (ctx, next) => {
    const data = ctx.callbackQuery.data;

    if (data.startsWith("ws:") || data.startsWith("wl:") || data.startsWith("wn:") || 
        data.startsWith("fd:") || data.startsWith("ff:") || data.startsWith("fp:")) {
      return next(); // handled by workspace and file handlers
    }

    // Bookmark view callback
    if (data.startsWith("bm:view:")) {
      const idx = parseInt(data.slice(8), 10);
      const chatId = ctx.chat!.id;
      const list = getBookmarks(chatId);
      if (idx < 0 || idx >= list.length) {
        await ctx.answerCallbackQuery({ text: "Bookmark tidak ditemukan" });
        return;
      }
      await ctx.answerCallbackQuery();
      const bm = list[idx];
      const chunks = splitMessage(`📌 <b>Bookmark #${idx + 1}</b>\n\n${escapeHtml(bm.text)}`);
      for (const chunk of chunks) {
        await ctx.reply(chunk, { parse_mode: "HTML" });
      }
      return;
    }

    // Quick-action callbacks from task completion buttons
    if (data.startsWith("qa:")) {
      const [, action, cascadeId] = data.split(":");
      const chatId = ctx.chat!.id;
      const session = getSession(chatId);

      if (!session || session.cascadeId !== cascadeId) {
        await ctx.answerCallbackQuery({ text: "⚠️ Session berbeda. Gunakan /use." });
        return;
      }

      await ctx.answerCallbackQuery();
      // Remove buttons from the completion message
      try { await ctx.editMessageReplyMarkup({ reply_markup: undefined }); } catch {}

      if (action === "continue") {
        const { connectStreamer, cleanupWs } = await import("./utils.js");
        cleanupWs(session);
        session.streamMessageId = null;
        session.streamBuffer = "";
        connectStreamer(ctx.api, chatId, session, client, config);
        try {
          await client.sendMessage(session.cascadeId, "please continue", session.selectedModel, undefined, session.plannerType);
        } catch (err) {
          await ctx.reply(`❌ Gagal: ${escapeHtml((err as Error).message)}`, { parse_mode: "HTML" });
        }
      } else if (action === "retry") {
        const { connectStreamer, cleanupWs } = await import("./utils.js");
        cleanupWs(session);
        session.streamMessageId = null;
        session.streamBuffer = "";
        connectStreamer(ctx.api, chatId, session, client, config);
        try {
          await client.sendMessage(session.cascadeId, "please retry the last task", session.selectedModel, undefined, session.plannerType);
        } catch (err) {
          await ctx.reply(`❌ Gagal: ${escapeHtml((err as Error).message)}`, { parse_mode: "HTML" });
        }
      } else if (action === "export") {
        // Trigger export logic
        try {
          const steps = await client.getSteps(session.cascadeId, 0, 1000);
          if (!steps || steps.length === 0) {
            await ctx.reply("📭 Tidak ada steps untuk diexport.");
            return;
          }
          const shortId = session.cascadeId.slice(0, 8);
          let md = `# Conversation ${shortId}\n\n**Exported:** ${new Date().toISOString()}\n**Steps:** ${steps.length}\n\n---\n\n`;
          for (const step of steps) {
            if ((step.status as string) !== "CORTEX_STEP_STATUS_DONE") continue;
            const resp = step.plannerResponse as Record<string, unknown> | undefined;
            if (resp) {
              const text = (resp.modifiedResponse as string) ?? (resp.response as string) ?? "";
              if (text) md += `## 💬 Agent Response\n\n${text}\n\n---\n\n`;
            }
            const cmd = step.runCommand as Record<string, unknown> | undefined;
            if (cmd) {
              const cmdLine = (cmd.commandLine as string) ?? "";
              md += `## ⚡ Command\n\n\`\`\`\n${cmdLine}\n\`\`\`\n\n---\n\n`;
            }
          }
          const { InputFile } = await import("grammy");
          const tmpPath = (await import("node:path")).join((await import("node:os")).tmpdir(), `porta-export-${shortId}.md`);
          (await import("node:fs")).writeFileSync(tmpPath, md, "utf-8");
          await ctx.reply("📄 Exporting...");
          await ctx.api.sendDocument(chatId, new InputFile(tmpPath, `conversation-${shortId}.md`), {
            caption: `📄 Export <code>${shortId}</code> (${steps.length} steps)`,
            parse_mode: "HTML",
          });
          (await import("node:fs")).unlinkSync(tmpPath);
        } catch (err) {
          await ctx.reply(`❌ Export gagal: ${escapeHtml((err as Error).message)}`, { parse_mode: "HTML" });
        }
      }
      return;
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
        
        const chatId = ctx.chat?.id;
        if (chatId) {
          const session = getSession(chatId);
          if (session) {
            session.isCancelled = true;
          }
        }

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
