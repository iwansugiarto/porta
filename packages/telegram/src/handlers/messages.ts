import type { Bot } from "grammy";
import type { TelegramConfig } from "../config.js";
import type { PortaClient } from "../porta-client.js";
import { getSession, createSession, destroySession } from "../session.js";
import { escapeHtml } from "../formatter.js";
import { connectStreamer, cleanupWs } from "./utils.js";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";

export function registerMessageHandlers(
  bot: Bot,
  client: PortaClient,
  config: TelegramConfig,
): void {
  function getEffectiveWorkspace(chatId: number): string | undefined {
    const session = getSession(chatId);
    return session?.workspaceUri ?? config.workspaceUri;
  }

  // ── Free-text messages → send to Antigravity ──
  bot.on("message:text", async (ctx) => {
    const chatId = ctx.chat.id;
    const text = ctx.message.text;

    // Ignore commands (already handled)
    if (text.startsWith("/")) return;

    let session = getSession(chatId);

    // Auto-create a conversation if none exists
    if (!session) {
      const statusMsg = await ctx.reply("⏳ Membuat conversation baru...");

      try {
        const result = await client.createConversation(getEffectiveWorkspace(chatId));
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

    cleanupWs(session);
    session.streamMessageId = null;
    session.streamBuffer = "";

    connectStreamer(ctx.api, chatId, session, client, config);

    try {
      await client.sendMessage(session.cascadeId, text, session.selectedModel);
    } catch (err) {
      const errMsg = (err as Error).message;
      if (errMsg.includes("not_found") || errMsg.includes("502")) {
        try {
          await client.recallConversation(session.cascadeId);
          await client.sendMessage(session.cascadeId, text, session.selectedModel);
        } catch (retryErr) {
          cleanupWs(session);
          destroySession(chatId);
          await ctx.reply(
            "⚠️ Conversation tidak bisa di-load dari disk.\n\n" +
              "<i>Ketik /new untuk buat conversation baru, atau kirim pesan langsung.</i>",
            { parse_mode: "HTML" },
          );
          return;
        }
      } else {
        cleanupWs(session);
        await ctx.reply(
          `❌ Gagal mengirim pesan: ${escapeHtml(errMsg)}`,
          { parse_mode: "HTML" },
        );
        return;
      }
    }
  });

  // ── Photo/image messages → send with media to Antigravity ──
  const photoGroupCache = new Map<
    string,
    {
      timer: ReturnType<typeof setTimeout>;
      items: { mimeType: string; data: string }[];
      caption?: string;
    }
  >();

  async function processPhotos(
    chatId: number,
    caption: string,
    items: { mimeType: string; data: string }[]
  ) {
    let session = getSession(chatId);

    if (!session) {
      const statusMsg = await bot.api.sendMessage(chatId, "⏳ Membuat conversation baru...");
      try {
        const result = await client.createConversation(getEffectiveWorkspace(chatId));
        session = createSession(chatId, result.cascadeId);
        await bot.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `✅ Conversation <code>${result.cascadeId.slice(0, 8)}</code> dibuat`,
          { parse_mode: "HTML" },
        );
      } catch (err) {
        await bot.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `❌ Gagal membuat conversation: ${escapeHtml((err as Error).message)}`,
          { parse_mode: "HTML" },
        );
        return;
      }
    }

    cleanupWs(session);
    session.streamMessageId = null;
    session.streamBuffer = "";

    connectStreamer(bot.api, chatId, session, client, config);

    try {
      await client.sendMessage(
        session.cascadeId,
        caption,
        session.selectedModel,
        items,
      );
    } catch (err) {
      cleanupWs(session);
      await bot.api.sendMessage(
        chatId,
        `❌ Gagal mengirim gambar: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  }

  bot.on("message:photo", async (ctx) => {
    const chatId = ctx.chat.id;
    const caption = ctx.message.caption ?? "Describe this image";
    const groupId = ctx.message.media_group_id;

    const photos = ctx.message.photo;
    const bestPhoto = photos[photos.length - 1];

    let mediaItem: { mimeType: string; data: string } | null = null;

    try {
      const file = await ctx.api.getFile(bestPhoto.file_id);
      const fileUrl = `https://api.telegram.org/file/bot${config.botToken}/${file.file_path}`;

      const response = await fetch(fileUrl);
      if (!response.ok) {
        throw new Error(`Failed to download photo: HTTP ${response.status}`);
      }
      const buffer = await response.arrayBuffer();
      const base64Data = Buffer.from(buffer).toString("base64");

      const ext = file.file_path?.split(".").pop()?.toLowerCase() ?? "jpg";
      const mimeMap: Record<string, string> = {
        jpg: "image/jpeg",
        jpeg: "image/jpeg",
        png: "image/png",
        gif: "image/gif",
        webp: "image/webp",
      };
      const mimeType = mimeMap[ext] ?? "image/jpeg";
      mediaItem = { mimeType, data: base64Data };
    } catch (err) {
      await ctx.reply(
        `❌ Gagal mendownload gambar: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
      return;
    }

    if (groupId) {
      if (!photoGroupCache.has(groupId)) {
        photoGroupCache.set(groupId, {
          items: [],
          timer: setTimeout(() => {}, 0),
        });
      }

      const group = photoGroupCache.get(groupId)!;
      group.items.push(mediaItem);
      if (caption !== "Describe this image" && !group.caption) {
        group.caption = caption;
      }

      clearTimeout(group.timer);
      group.timer = setTimeout(async () => {
        photoGroupCache.delete(groupId);
        await processPhotos(chatId, group.caption ?? "Describe these images", group.items);
      }, 1500);
      return;
    }

    await processPhotos(chatId, caption, [mediaItem]);
  });

  // ── Document messages → save and tell agent about the file ──
  const documentGroupCache = new Map<
    string,
    {
      timer: ReturnType<typeof setTimeout>;
      files: string[];
      fileNames: string[];
      caption?: string;
    }
  >();

  async function processDocumentQuery(chatId: number, query: string) {
    let session = getSession(chatId);

    if (!session) {
      const statusMsg = await bot.api.sendMessage(chatId, "⏳ Membuat conversation baru...");
      try {
        const result = await client.createConversation(getEffectiveWorkspace(chatId));
        session = createSession(chatId, result.cascadeId);
        await bot.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `✅ Conversation <code>${result.cascadeId.slice(0, 8)}</code> dibuat`,
          { parse_mode: "HTML" },
        );
      } catch (err) {
        await bot.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `❌ Gagal membuat conversation: ${escapeHtml((err as Error).message)}`,
          { parse_mode: "HTML" },
        );
        return;
      }
    }

    cleanupWs(session);
    session.streamMessageId = null;
    session.streamBuffer = "";

    connectStreamer(bot.api, chatId, session, client, config);

    try {
      await client.sendMessage(
        session.cascadeId,
        query,
        session.selectedModel,
      );
    } catch (err) {
      cleanupWs(session);
      await bot.api.sendMessage(
        chatId,
        `❌ Gagal mengirim dokumen: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  }

  bot.on("message:document", async (ctx) => {
    const chatId = ctx.chat.id;
    const caption = ctx.message.caption ?? "Examine this file";
    const doc = ctx.message.document;
    const groupId = ctx.message.media_group_id;

    let dest = "";
    const fileName = doc.file_name ?? "telegram_upload";

    try {
      const file = await ctx.api.getFile(doc.file_id);
      const fileUrl = `https://api.telegram.org/file/bot${config.botToken}/${file.file_path}`;

      const tmpDir = path.join(os.tmpdir(), "porta-telegram");
      fs.mkdirSync(tmpDir, { recursive: true });
      dest = path.join(tmpDir, `tg_${Date.now()}_${fileName}`);

      const response = await fetch(fileUrl);
      if (!response.ok) {
        throw new Error(`Failed to download file: HTTP ${response.status}`);
      }
      const buffer = Buffer.from(await response.arrayBuffer());
      fs.writeFileSync(dest, buffer);

      await ctx.reply(`📥 File disimpan: <code>${escapeHtml(fileName)}</code>`, {
        parse_mode: "HTML",
      });
    } catch (err) {
      await ctx.reply(
        `❌ Gagal mendownload dokumen: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
      return;
    }

    if (groupId) {
      if (!documentGroupCache.has(groupId)) {
        documentGroupCache.set(groupId, {
          files: [],
          fileNames: [],
          timer: setTimeout(() => {}, 0),
        });
      }

      const group = documentGroupCache.get(groupId)!;
      group.files.push(dest);
      group.fileNames.push(fileName);
      if (caption !== "Examine this file" && !group.caption) {
        group.caption = caption;
      }

      clearTimeout(group.timer);
      group.timer = setTimeout(async () => {
        documentGroupCache.delete(groupId);
        const finalCaption = group.caption ?? "Examine these files";
        const query =
          `[The user has uploaded multiple files. Use your view_file tool to examine them at:\n` +
          group.files.map(f => `- ${f}`).join("\n") + `]\n\n${finalCaption}`;
        await processDocumentQuery(chatId, query);
      }, 1500);
      return;
    }

    const query =
      `[The user has uploaded a file. Use your view_file tool to examine it at: ${dest}]\n` +
      `${caption}`;
    await processDocumentQuery(chatId, query);
  });
}
