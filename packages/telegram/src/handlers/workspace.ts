import { InlineKeyboard } from "grammy";
import type { Bot } from "grammy";
import type { TelegramConfig } from "../config.js";
import type { PortaClient } from "../porta-client.js";
import { getSession, createSession } from "../session.js";
import { escapeHtml } from "../formatter.js";
import { getPathId, pathCache } from "./shared.js";
import * as os from "node:os";

export function registerWorkspaceHandlers(
  bot: Bot,
  client: PortaClient,
  config: TelegramConfig,
): void {
  bot.command("workspace", async (ctx) => {
    const chatId = ctx.chat.id;
    const wsArg = ctx.match?.trim();

    if (wsArg) {
      let session = getSession(chatId);
      if (!session) {
        try {
          const result = await client.createConversation(wsArg);
          session = createSession(chatId, result.cascadeId);
        } catch (err) {
          await ctx.reply(
            `❌ Gagal membuat conversation: ${escapeHtml((err as Error).message)}`,
            { parse_mode: "HTML" },
          );
          return;
        }
      }
      session.workspaceUri = wsArg;
      const shortPath = wsArg
        .replace(/^file:\/\//, "")
        .replace(os.homedir(), "~");
      await ctx.reply(
        `✅ Workspace diubah ke: <code>${escapeHtml(shortPath)}</code>\n\n` +
          "<i>Conversation baru akan menggunakan workspace ini.</i>",
        { parse_mode: "HTML" },
      );
      return;
    }

    const statusMsg = await ctx.reply("⏳ Memuat daftar workspaces...");

    try {
      const workspaces = await client.listWorkspaces();
      const activeUris = new Set(workspaces.map((w) => w.workspaceUri));

      const conversations = await client.listConversations();
      const recentWsUris = new Set<string>();
      for (const conv of conversations) {
        const wsUri = conv.workspaces?.[0]?.workspaceFolderAbsoluteUri;
        if (wsUri && !activeUris.has(wsUri)) {
          recentWsUris.add(wsUri);
        }
      }

      if (workspaces.length === 0 && recentWsUris.size === 0) {
        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          "📭 Tidak ada workspace tersedia.",
        );
        return;
      }

      const session = getSession(chatId);
      const currentWs = session?.workspaceUri ?? config.workspaceUri ?? "";

      let text = "📂 <b>Workspaces</b>\n\n";
      const keyboard = new InlineKeyboard();

      if (workspaces.length > 0) {
        text += "<b>🟢 Active:</b>";
        for (const ws of workspaces) {
          const uri = ws.workspaceUri;
          const shortPath = uri
            .replace(/^file:\/\//, "")
            .replace(os.homedir(), "~");
          const isActive = uri === currentWs;
          const marker = isActive ? " 👈" : "";
          const wsId = getPathId(uri);
          keyboard.text(`🟢 ${shortPath}${marker}`, `ws:${wsId}`).row();
        }
      }

      if (recentWsUris.size > 0) {
        text += (workspaces.length > 0 ? "\n" : "") + "<b>⚪ Recent:</b>";
        for (const uri of [...recentWsUris].slice(0, 5)) {
          const shortPath = uri
            .replace(/^file:\/\//, "")
            .replace(os.homedir(), "~");
          const wsId = getPathId(uri);
          keyboard.text(`⚪ ${shortPath} ▶`, `wl:${wsId}`).row();
        }
      }

      await ctx.api.editMessageText(chatId, statusMsg.message_id, text, {
        parse_mode: "HTML",
        reply_markup: keyboard,
      });
    } catch (err) {
      await ctx.api.editMessageText(
        chatId,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  bot.callbackQuery(/^ws:(.+)$/, async (ctx) => {
    const wsPathId = ctx.match[1];
    const wsUri = pathCache.get(wsPathId);
    if (!wsUri) {
      await ctx.answerCallbackQuery({ text: "Expired — gunakan /workspace lagi" });
      return;
    }
    const chatId = ctx.chat!.id;
    const shortPath = wsUri
      .replace(/^file:\/\//, "")
      .replace(os.homedir(), "~");

    await ctx.answerCallbackQuery({ text: `Workspace: ${shortPath}` });

    let session = getSession(chatId);
    if (session) {
      session.workspaceUri = wsUri;
    }

    try {
      const conversations = await client.listConversations();
      const filtered = conversations.filter((c) => {
        const cWs = c.workspaces?.[0]?.workspaceFolderAbsoluteUri;
        return cWs && cWs === wsUri;
      }).slice(0, 8);

      const keyboard = new InlineKeyboard();

      for (const conv of filtered) {
        const shortId = conv.id.slice(0, 8);
        const statusIcon = conv.status === "CASCADE_RUN_STATUS_RUNNING" ? "🟢" : "⚪";
        const summary = conv.summary.length > 30
          ? conv.summary.slice(0, 27) + "..."
          : conv.summary;
        keyboard.text(
          `${statusIcon} ${shortId} ${summary}`,
          `use:${conv.id}`,
        ).row();
      }

      keyboard.text("➕ New conversation", `wn:${wsPathId}`).row();

      const convCountText = filtered.length > 0
        ? `${filtered.length} conversation ditemukan:`
        : "Tidak ada conversation.";

      await ctx.editMessageText(
        `✅ Workspace: <code>${escapeHtml(shortPath)}</code>\n\n` +
          `${convCountText}`,
        { parse_mode: "HTML", reply_markup: keyboard },
      );
    } catch (err) {
      await ctx.editMessageText(
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  bot.callbackQuery(/^wl:(.+)$/, async (ctx) => {
    const wsPathId = ctx.match[1];
    const wsUri = pathCache.get(wsPathId);
    if (!wsUri) {
      await ctx.answerCallbackQuery({ text: "Expired — gunakan /workspace lagi" });
      return;
    }
    const chatId = ctx.chat!.id;
    const folderPath = wsUri.replace(/^file:\/\//, "");
    const shortPath = folderPath.replace(os.homedir(), "~");

    await ctx.answerCallbackQuery({ text: "Launching Antigravity..." });

    await ctx.editMessageText(
      `🚀 Membuka <code>${escapeHtml(shortPath)}</code> di Antigravity...\n\n` +
        "<i>Menunggu Language Server ready...</i>",
      { parse_mode: "HTML" },
    );

    const antigravityCli = config.antigravityCliPath;
    try {
      const { exec } = await import("node:child_process");
      exec(`"${antigravityCli}" -n "${folderPath}"`, (err) => {
        if (err) console.warn("[workspace] launch error:", err.message);
      });
    } catch (e) {
      await ctx.editMessageText(
        `❌ Gagal launch Antigravity: ${escapeHtml((e as Error).message)}`,
        { parse_mode: "HTML" },
      );
      return;
    }

    const maxWaitMs = 30_000;
    const pollIntervalMs = 3_000;
    const start = Date.now();
    let found = false;

    while (Date.now() - start < maxWaitMs) {
      await new Promise((r) => setTimeout(r, pollIntervalMs));
      try {
        const workspaces = await client.listWorkspaces();
        if (workspaces.some((w) => w.workspaceUri === wsUri)) {
          found = true;
          break;
        }
      } catch { /* retry */ }
    }

    if (!found) {
      await ctx.editMessageText(
        `⏰ Timeout: Antigravity belum ready untuk <code>${escapeHtml(shortPath)}</code>.\n\n` +
          "<i>Coba /workspace lagi setelah Antigravity selesai loading.</i>",
        { parse_mode: "HTML" },
      );
      return;
    }

    try {
      const conversations = await client.listConversations();
      const filtered = conversations.filter((c) => {
        const cWs = c.workspaces?.[0]?.workspaceFolderAbsoluteUri;
        return cWs && cWs === wsUri;
      }).slice(0, 8);

      let session = getSession(chatId);
      if (session) session.workspaceUri = wsUri;

      const keyboard = new InlineKeyboard();
      for (const conv of filtered) {
        const shortId = conv.id.slice(0, 8);
        const statusIcon = conv.status === "CASCADE_RUN_STATUS_RUNNING" ? "🟢" : "⚪";
        const summary = conv.summary.length > 30
          ? conv.summary.slice(0, 27) + "..."
          : conv.summary;
        keyboard.text(
          `${statusIcon} ${shortId} ${summary}`,
          `use:${conv.id}`,
        ).row();
      }
      keyboard.text("➕ New conversation", `wn:${wsPathId}`).row();

      const convCountText = filtered.length > 0
        ? `${filtered.length} conversation ditemukan:`
        : "Tidak ada conversation.";

      await ctx.editMessageText(
        `✅ Workspace ready: <code>${escapeHtml(shortPath)}</code>\n\n` +
          convCountText,
        { parse_mode: "HTML", reply_markup: keyboard },
      );
    } catch (err) {
      await ctx.editMessageText(
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  bot.callbackQuery(/^wn:(.+)$/, async (ctx) => {
    const wsPathId = ctx.match[1];
    const wsUri = pathCache.get(wsPathId);
    if (!wsUri) {
      await ctx.answerCallbackQuery({ text: "Expired — gunakan /workspace lagi" });
      return;
    }
    const chatId = ctx.chat!.id;
    const shortPath = wsUri
      .replace(/^file:\/\//, "")
      .replace(os.homedir(), "~");

    await ctx.answerCallbackQuery({ text: "Creating..." });

    try {
      const result = await client.createConversation(wsUri);
      const session = createSession(chatId, result.cascadeId);
      session.workspaceUri = wsUri;

      await ctx.editMessageText(
        `✅ Workspace: <code>${escapeHtml(shortPath)}</code>\n` +
          `📝 Conversation <code>${result.cascadeId.slice(0, 8)}</code> dibuat\n\n` +
          "<i>Kirim pesan untuk mulai chat.</i>",
        { parse_mode: "HTML" },
      );
    } catch (err) {
      await ctx.editMessageText(
        `❌ Gagal membuat conversation: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });
}
