/**
 * Bot command and message handlers.
 *
 * Registers all Telegram bot commands and the free-text message handler.
 * Each command validates the session state and delegates to PortaClient.
 */

import { InlineKeyboard } from "grammy";
import type { Bot, Context } from "grammy";
import type { TelegramConfig } from "./config.js";
import { PortaClient } from "./porta-client.js";
import {
  getSession,
  createSession,
  switchSession,
  destroySession,
} from "./session.js";
import { ResponseStreamer } from "./streamer.js";
import { escapeHtml } from "./formatter.js";

export function registerHandlers(bot: Bot, config: TelegramConfig): void {
  const client = new PortaClient(config.proxyBaseUrl, config.authToken);

  // ── /start ──

  bot.command("start", async (ctx) => {
    await ctx.reply(
      "🚀 <b>Porta Telegram Bridge</b>\n\n" +
        "Akses Antigravity langsung dari Telegram.\n\n" +
        "<b>Commands:</b>\n" +
        "/new — Buat conversation baru\n" +
        "/list — Daftar conversations\n" +
        "/use <code>id</code> — Switch ke conversation\n" +
        "/model <code>name</code> — Pilih model (e.g. gemini-2.5-pro)\n" +
        "/models — Daftar model tersedia\n" +
        "/stop — Stop agent yang sedang berjalan\n" +
        "/status — Status proxy &amp; Language Server\n" +
        "/end — Akhiri session saat ini\n" +
        "/help — Tampilkan bantuan ini\n\n" +
        "<i>Kirim pesan teks atau foto untuk chat dengan Antigravity.</i>",
      { parse_mode: "HTML" },
    );
  });

  // ── /help ──

  bot.command("help", async (ctx) => {
    await ctx.reply(
      "📖 <b>Bantuan Porta Bot</b>\n\n" +
        "<b>Mulai baru:</b>\n" +
        "1. Ketik /new untuk buat conversation\n" +
        "2. Kirim pesan — bot akan meneruskan ke Antigravity\n" +
        "3. Response akan di-stream secara real-time\n\n" +
        "<b>Model:</b>\n" +
        "• /models — lihat daftar model tersedia\n" +
        "• /model gemini-2.5-pro — pilih model\n" +
        "• /model — reset ke model default\n\n" +
        "<b>Gambar:</b>\n" +
        "Kirim foto dengan caption untuk mengirim screenshot/gambar\n" +
        "ke Antigravity (e.g. \"fix this UI bug\").\n\n" +
        "<b>Approval:</b>\n" +
        "Saat Antigravity perlu izin (jalankan command, akses file),\n" +
        "bot akan menampilkan tombol ✅ Approve / ❌ Reject.\n\n" +
        "<b>Tips:</b>\n" +
        "• Gunakan /list untuk melihat conversation lama\n" +
        "• Gunakan /use &lt;id&gt; untuk melanjutkan conversation\n" +
        "• Gunakan /stop untuk menghentikan agent\n" +
        "• Gunakan /end untuk menutup session",
      { parse_mode: "HTML" },
    );
  });

  // ── /new ──

  bot.command("new", async (ctx) => {
    const chatId = ctx.chat.id;
    const statusMsg = await ctx.reply("⏳ Membuat conversation baru...");

    try {
      const result = await client.createConversation(config.workspaceUri);
      const session = createSession(chatId, result.cascadeId);
      const shortId = result.cascadeId.slice(0, 8);

      await ctx.api.editMessageText(
        chatId,
        statusMsg.message_id,
        `✅ Conversation baru dibuat: <code>${shortId}</code>\n\n` +
          "<i>Kirim pesan untuk mulai chat.</i>",
        { parse_mode: "HTML" },
      );
    } catch (err) {
      await ctx.api.editMessageText(
        chatId,
        statusMsg.message_id,
        `❌ Gagal membuat conversation: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /list ──

  bot.command("list", async (ctx) => {
    const chatId = ctx.chat.id;
    const statusMsg = await ctx.reply("⏳ Memuat daftar conversations...");

    try {
      const conversations = await client.listConversations();

      if (conversations.length === 0) {
        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          "📭 Tidak ada conversation. Ketik /new untuk membuat.",
        );
        return;
      }

      // Show latest 10
      const items = conversations.slice(0, 10);
      const currentSession = getSession(chatId);

      let text = "📋 <b>Conversations</b>\n\n";
      text += "<i>Tap untuk switch:</i>";

      if (conversations.length > 10) {
        text += `\n<i>(${conversations.length - 10} lainnya tidak ditampilkan)</i>`;
      }

      const keyboard = new InlineKeyboard();
      for (const conv of items) {
        const shortId = conv.id.slice(0, 8);
        const isActive = currentSession?.cascadeId === conv.id;
        const statusIcon = conv.status === "CASCADE_RUN_STATUS_RUNNING" ? "🟢" : "⚪";
        const marker = isActive ? " 👈" : "";
        const summary = conv.summary.length > 35
          ? conv.summary.slice(0, 32) + "..."
          : conv.summary;

        keyboard.text(
          `${statusIcon} ${shortId} ${summary}${marker}`,
          `use:${conv.id}`,
        ).row();
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

  // ── /use <id> ──

  bot.command("use", async (ctx) => {
    const chatId = ctx.chat.id;
    const idArg = ctx.match?.trim();

    if (!idArg) {
      await ctx.reply(
        "💡 Gunakan: /use <code>id</code>\n\n" +
          "Lihat daftar ID dengan /list",
        { parse_mode: "HTML" },
      );
      return;
    }

    const statusMsg = await ctx.reply("⏳ Mencari conversation...");

    try {
      // Find conversation matching the short ID
      const conversations = await client.listConversations();
      const match = conversations.find(
        (c) => c.id.startsWith(idArg) || c.id === idArg,
      );

      if (!match) {
        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `❌ Conversation <code>${escapeHtml(idArg)}</code> tidak ditemukan.`,
          { parse_mode: "HTML" },
        );
        return;
      }

      switchSession(chatId, match.id);
      const shortId = match.id.slice(0, 8);

      await ctx.api.editMessageText(
        chatId,
        statusMsg.message_id,
        `✅ Switched ke conversation: <code>${shortId}</code>\n` +
          `📝 ${escapeHtml(match.summary)}\n\n` +
          "<i>Kirim pesan untuk melanjutkan chat.</i>",
        { parse_mode: "HTML" },
      );
    } catch (err) {
      await ctx.api.editMessageText(
        chatId,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /stop ──

  bot.command("stop", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif.");
      return;
    }

    try {
      await client.stopConversation(session.cascadeId);
      await ctx.reply("🛑 Agent dihentikan.");
    } catch (err) {
      await ctx.reply(
        `❌ Gagal stop: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /end ──

  bot.command("end", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif.");
      return;
    }

    destroySession(chatId);
    await ctx.reply("👋 Session diakhiri. Ketik /new untuk buat baru.");
  });

  // ── /status ──

  bot.command("status", async (ctx) => {
    const chatId = ctx.chat.id;

    try {
      const health = await client.getHealth();
      const proxy = health.proxy as { port?: number; uptime?: number } | undefined;
      const lsList = health.languageServers as Record<string, unknown>[] | undefined;
      const autoApprove = health.autoApprove as boolean | undefined;

      let text = "📊 <b>Porta Status</b>\n\n";
      text += `<b>Proxy:</b> ✅ Online (port ${proxy?.port ?? "?"})\n`;
      text += `<b>Uptime:</b> ${formatUptime(proxy?.uptime ?? 0)}\n`;
      text += `<b>Auto-approve:</b> ${autoApprove ? "✅ On" : "❌ Off"}\n`;
      text += `<b>Language Servers:</b> ${lsList?.length ?? 0}\n`;

      if (lsList && lsList.length > 0) {
        for (const ls of lsList) {
          text += `  • PID ${ls.pid} — port ${ls.httpsPort}`;
          if (ls.workspaceId) text += ` — ${escapeHtml(String(ls.workspaceId))}`;
          text += "\n";
        }
      }

      const session = getSession(chatId);
      if (session) {
        text += `\n<b>Active session:</b> <code>${session.cascadeId.slice(0, 8)}</code>`;
        text += session.wsConnection ? " (streaming 🟢)" : "";
      }

      await ctx.reply(text, { parse_mode: "HTML" });
    } catch (err) {
      await ctx.reply(
        `❌ Proxy tidak terjangkau: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /models ──

  bot.command("models", async (ctx) => {
    const statusMsg = await ctx.reply("⏳ Memuat daftar model...");

    try {
      const models = await client.listModels();
      if (models.length === 0) {
        await ctx.api.editMessageText(
          ctx.chat.id,
          statusMsg.message_id,
          "📭 Tidak ada model tersedia.",
        );
        return;
      }

      const session = getSession(ctx.chat.id);
      let text = "🤖 <b>Model Tersedia</b>\n\n";
      text += "<i>Tap untuk memilih:</i>";

      const keyboard = new InlineKeyboard();
      for (const m of models) {
        const isSelected = session?.selectedModel === m.name;
        const marker = isSelected ? " 👈" : "";
        keyboard.text(
          `${m.displayName}${marker}`,
          `model:${m.name}`,
        ).row();
      }

      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        text,
        { parse_mode: "HTML", reply_markup: keyboard },
      );
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /model <name> ──

  bot.command("model", async (ctx) => {
    const chatId = ctx.chat.id;
    const modelArg = ctx.match?.trim();
    let session = getSession(chatId);

    if (!modelArg) {
      // Reset to default
      if (session) {
        session.selectedModel = undefined;
      }
      await ctx.reply("🔄 Model di-reset ke default.");
      return;
    }

    // Try to match by display name (case-insensitive partial match)
    let resolvedModel = modelArg;
    let resolvedLabel = modelArg;
    try {
      const models = await client.listModels();
      const lowerArg = modelArg.toLowerCase();
      const match = models.find(
        (m) =>
          m.displayName.toLowerCase() === lowerArg ||
          m.displayName.toLowerCase().includes(lowerArg) ||
          m.name.toLowerCase() === lowerArg ||
          m.name.toLowerCase().includes(lowerArg),
      );
      if (match) {
        resolvedModel = match.name;
        resolvedLabel = match.displayName;
      }
    } catch {
      // If listing fails, use the raw arg
    }

    // Create a session if none exists (model can be set before conversation)
    if (!session) {
      try {
        const result = await client.createConversation(config.workspaceUri);
        session = createSession(chatId, result.cascadeId);
      } catch (err) {
        await ctx.reply(
          `❌ Gagal: ${escapeHtml((err as Error).message)}`,
          { parse_mode: "HTML" },
        );
        return;
      }
    }

    session.selectedModel = resolvedModel;
    await ctx.reply(
      `✅ Model diubah ke: <b>${escapeHtml(resolvedLabel)}</b>`,
      { parse_mode: "HTML" },
    );
  });

  // ── Callback queries (inline keyboard: approve/reject) ──

  bot.on("callback_query:data", async (ctx) => {
    const data = ctx.callbackQuery.data;

    // ── Handle "use:<cascadeId>" (conversation switch) ──
    if (data.startsWith("use:")) {
      const cascadeId = data.slice(4);
      const chatId = ctx.chat!.id;
      switchSession(chatId, cascadeId);
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

    // ── Handle "model:<modelId>" (model selection) ──
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
      // Find display name
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

    // ── Handle approve/reject callbacks ──
    // Format: "approve:type:cascadeId:trajectoryId:stepIndex"
    // or:     "reject:type:cascadeId:trajectoryId:stepIndex"

    const parts = data.split(":");
    if (parts.length < 5) {
      await ctx.answerCallbackQuery({ text: "Invalid action" });
      return;
    }

    const [action, approvalType, cascadeId, trajectoryId, stepIndexStr] = parts;
    const stepIndex = parseInt(stepIndexStr, 10);

    if (!["approve", "reject"].includes(action)) {
      await ctx.answerCallbackQuery({ text: "Unknown action" });
      return;
    }

    try {
      if (action === "approve") {
        if (approvalType === "command") {
          await client.approveCommand(cascadeId, trajectoryId, stepIndex);
        } else if (approvalType === "file") {
          // Extract file path from the message text
          const msgText = ctx.callbackQuery.message?.text ?? "";
          const pathMatch = msgText.match(/`([^`]+)`/);
          const filePath = pathMatch?.[1] ?? "";
          await client.approveFileAccess(
            cascadeId,
            trajectoryId,
            stepIndex,
            filePath,
          );
        }
        await ctx.answerCallbackQuery({ text: "✅ Approved" });
        await ctx.editMessageReplyMarkup({ reply_markup: undefined });
        // Append approval status to message
        const currentText = ctx.callbackQuery.message?.text ?? "";
        try {
          await ctx.editMessageText(currentText + "\n\n✅ <b>Approved</b>", {
            parse_mode: "HTML",
          });
        } catch {
          // Message may not be editable
        }
      } else {
        if (approvalType === "command") {
          await client.rejectCommand(cascadeId, trajectoryId, stepIndex);
        } else if (approvalType === "file") {
          const msgText = ctx.callbackQuery.message?.text ?? "";
          const pathMatch = msgText.match(/`([^`]+)`/);
          const filePath = pathMatch?.[1] ?? "";
          await client.rejectFileAccess(
            cascadeId,
            trajectoryId,
            stepIndex,
            filePath,
          );
        }
        await ctx.answerCallbackQuery({ text: "❌ Rejected" });
        await ctx.editMessageReplyMarkup({ reply_markup: undefined });
        const currentText = ctx.callbackQuery.message?.text ?? "";
        try {
          await ctx.editMessageText(currentText + "\n\n❌ <b>Rejected</b>", {
            parse_mode: "HTML",
          });
        } catch {
          // Message may not be editable
        }
      }
    } catch (err) {
      await ctx.answerCallbackQuery({
        text: `Error: ${(err as Error).message.slice(0, 100)}`,
      });
    }
  });

  // ── Free-text messages → send to Antigravity ──

  bot.on("message:text", async (ctx) => {
    const chatId = ctx.chat.id;
    const text = ctx.message.text;

    // Ignore commands (already handled above)
    if (text.startsWith("/")) return;

    let session = getSession(chatId);

    // Auto-create a conversation if none exists
    if (!session) {
      const statusMsg = await ctx.reply("⏳ Membuat conversation baru...");

      try {
        const result = await client.createConversation(config.workspaceUri);
        session = createSession(chatId, result.cascadeId);
        const shortId = result.cascadeId.slice(0, 8);

        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `✅ Conversation <code>${shortId}</code> dibuat`,
          { parse_mode: "HTML" },
        );
      } catch (err) {
        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `❌ Gagal membuat conversation: ${escapeHtml((err as Error).message)}`,
          { parse_mode: "HTML" },
        );
        return;
      }
    }

    // Close existing WebSocket if any (new user message = new streaming session)
    if (session.wsConnection) {
      session.wsConnection.close();
      session.wsConnection = null;
    }
    session.streamMessageId = null;
    session.streamBuffer = "";

    // Connect WebSocket FIRST so it catches the "activate" signal
    // emitted by the proxy when the message is processed.
    connectStreamer(ctx.api, chatId, session, client, config);

    // Send the message to Porta (triggers activate → WS transitions to ACTIVE)
    try {
      await client.sendMessage(session.cascadeId, text, session.selectedModel);
    } catch (err) {
      // Clean up the WS we just opened
      if (session.wsConnection) {
        session.wsConnection.close();
        session.wsConnection = null;
      }
      const errMsg = (err as Error).message;
      if (errMsg.includes("not_found") || errMsg.includes("502")) {
        destroySession(chatId);
        await ctx.reply(
          "⚠️ Conversation sudah tidak aktif di Language Server.\n\n" +
            "<i>Ketik /new untuk buat conversation baru, atau kirim pesan langsung.</i>",
          { parse_mode: "HTML" },
        );
      } else {
        await ctx.reply(
          `❌ Gagal mengirim pesan: ${escapeHtml(errMsg)}`,
          { parse_mode: "HTML" },
        );
      }
      return;
    }
  });

  // ── Photo/image messages → send with media to Antigravity ──

  bot.on("message:photo", async (ctx) => {
    const chatId = ctx.chat.id;
    const caption = ctx.message.caption ?? "Describe this image";

    let session = getSession(chatId);

    // Auto-create a conversation if none exists
    if (!session) {
      const statusMsg = await ctx.reply("⏳ Membuat conversation baru...");
      try {
        const result = await client.createConversation(config.workspaceUri);
        session = createSession(chatId, result.cascadeId);
        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `✅ Conversation <code>${result.cascadeId.slice(0, 8)}</code> dibuat`,
          { parse_mode: "HTML" },
        );
      } catch (err) {
        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `❌ Gagal membuat conversation: ${escapeHtml((err as Error).message)}`,
          { parse_mode: "HTML" },
        );
        return;
      }
    }

    // Close existing WebSocket
    if (session.wsConnection) {
      session.wsConnection.close();
      session.wsConnection = null;
    }
    session.streamMessageId = null;
    session.streamBuffer = "";

    // Download the photo (pick highest resolution)
    const photos = ctx.message.photo;
    const bestPhoto = photos[photos.length - 1];

    // Connect WebSocket FIRST
    connectStreamer(ctx.api, chatId, session, client, config);

    try {
      const file = await ctx.api.getFile(bestPhoto.file_id);
      const fileUrl = `https://api.telegram.org/file/bot${config.botToken}/${file.file_path}`;

      // Fetch the image data
      const response = await fetch(fileUrl);
      if (!response.ok) {
        throw new Error(`Failed to download photo: HTTP ${response.status}`);
      }
      const buffer = await response.arrayBuffer();
      const base64Data = Buffer.from(buffer).toString("base64");

      // Determine MIME type from file extension
      const ext = file.file_path?.split(".").pop()?.toLowerCase() ?? "jpg";
      const mimeMap: Record<string, string> = {
        jpg: "image/jpeg",
        jpeg: "image/jpeg",
        png: "image/png",
        gif: "image/gif",
        webp: "image/webp",
      };
      const mimeType = mimeMap[ext] ?? "image/jpeg";

      await client.sendMessage(
        session.cascadeId,
        caption,
        session.selectedModel,
        [{ mimeType, data: base64Data }],
      );
    } catch (err) {
      // Clean up the WS on error
      if (session.wsConnection) {
        session.wsConnection.close();
        session.wsConnection = null;
      }
      await ctx.reply(
        `❌ Gagal mengirim gambar: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
      return;
    }
  });
}

/**
 * Connect a WebSocket streamer for a conversation session.
 * Sends a completion notification when the agent finishes.
 */
function connectStreamer(
  api: import("grammy").Api,
  chatId: number,
  session: import("./session.js").ChatSession,
  client: PortaClient,
  config: TelegramConfig,
): void {
  // Close existing WebSocket if any
  if (session.wsConnection) {
    session.wsConnection.close();
    session.wsConnection = null;
  }
  session.streamMessageId = null;
  session.streamBuffer = "";

  const startTime = Date.now();

  const streamer = new ResponseStreamer(
    api,
    chatId,
    session,
    session.cascadeId,
  );

  let receivedContent = false;

  const wsConn = client.connectWebSocket(
    session.cascadeId,
    (msg) => {
      receivedContent = true;
      void streamer.onMessage(msg);
    },
  );

  session.wsConnection = wsConn;

  wsConn.ws.on("close", () => {
    if (session.wsConnection === wsConn) {
      session.wsConnection = null;
    }
    void streamer.finalize().then(() => {
      const elapsed = Date.now() - startTime;

      if (!receivedContent && elapsed < 10_000) {
        // WS closed quickly without any content — agent didn't start
        void api.sendMessage(
          chatId,
          "⚠️ Agent tidak merespon. Conversation mungkin sedang aktif di Antigravity desktop.\n\n" +
            "<i>Coba /new untuk buat conversation baru.</i>",
          { parse_mode: "HTML" },
        ).catch(() => {});
      } else if (elapsed > 30_000) {
        // Proactive completion notification for long tasks
        const duration = formatUptime(elapsed / 1000);
        void api.sendMessage(
          chatId,
          `✅ <b>Task selesai</b> (${duration})`,
          { parse_mode: "HTML" },
        ).catch(() => {});
      }
    });
  });
}

/** Format seconds into a human-readable uptime string. */
function formatUptime(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);

  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}
