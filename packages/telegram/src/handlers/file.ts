import { InlineKeyboard, InputFile } from "grammy";
import type { Bot, Context } from "grammy";
import type { TelegramConfig } from "../config.js";
import { escapeHtml } from "../formatter.js";
import { getPathId, pathCache } from "./shared.js";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";

export function registerFileHandlers(
  bot: Bot,
  config: TelegramConfig,
): void {
  function listDirectory(ctx: Context, dirPath: string, page = 0): void {
    const PAGE_SIZE = 8;
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(dirPath, { withFileTypes: true });
    } catch (err) {
      ctx.reply(`❌ Tidak bisa baca: ${(err as Error).message}`).catch(() => {});
      return;
    }

    const filtered = entries
      .filter((e) => !e.name.startsWith(".") && e.name !== "node_modules")
      .sort((a, b) => {
        if (a.isDirectory() && !b.isDirectory()) return -1;
        if (!a.isDirectory() && b.isDirectory()) return 1;
        return a.name.localeCompare(b.name);
      });

    const totalPages = Math.ceil(filtered.length / PAGE_SIZE) || 1;
    const pageEntries = filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

    if (pageEntries.length === 0) {
      ctx.reply("📂 Folder kosong.").catch(() => {});
      return;
    }

    const keyboard = new InlineKeyboard();
    for (const entry of pageEntries) {
      const icon = entry.isDirectory() ? "📂" : "📄";
      const fullPath = path.join(dirPath, entry.name);
      const pathId = getPathId(fullPath);
      const action = entry.isDirectory() ? `fd:${pathId}` : `ff:${pathId}`;
      keyboard.text(`${icon} ${entry.name}`, action).row();
    }

    // Navigation row
    const parentDir = path.dirname(dirPath);
    if (parentDir !== dirPath) {
      keyboard.text("⬆️ Parent", `fd:${getPathId(parentDir)}`);
    }
    const dirPathId = getPathId(dirPath);
    if (page > 0) keyboard.text("◀️", `fp:${dirPathId}|${page - 1}`);
    if (page < totalPages - 1) keyboard.text("▶️", `fp:${dirPathId}|${page + 1}`);
    if (parentDir !== dirPath || page > 0 || page < totalPages - 1) keyboard.row();

    const shortPath = dirPath.replace(os.homedir(), "~");
    ctx.reply(
      `📂 <b>${escapeHtml(shortPath)}</b>\n` +
        `<i>${filtered.length} items — page ${page + 1}/${totalPages}</i>`,
      { parse_mode: "HTML", reply_markup: keyboard },
    ).catch(() => {});
  }

  bot.command("file", (ctx) => {
    const filePath = ctx.match?.trim();
    const wsUri = config.workspaceUri;
    const defaultDir = wsUri
      ? wsUri.replace(/^file:\/\//, "")
      : os.homedir();

    if (!filePath) {
      listDirectory(ctx, defaultDir);
      return;
    }

    const fullPath = filePath.startsWith("/")
      ? filePath
      : path.join(defaultDir, filePath);

    if (!fs.existsSync(fullPath)) {
      ctx.reply(`❌ Tidak ditemukan: ${escapeHtml(fullPath)}`, {
        parse_mode: "HTML",
      }).catch(() => {});
      return;
    }

    const stat = fs.statSync(fullPath);
    if (stat.isDirectory()) {
      listDirectory(ctx, fullPath);
      return;
    }

    if (stat.size > 50 * 1024 * 1024) {
      ctx.reply(`❌ File terlalu besar: ${(stat.size / 1024 / 1024).toFixed(1)}MB`).catch(() => {});
      return;
    }

    ctx.replyWithDocument(new InputFile(fullPath, path.basename(fullPath)))
      .catch((e) => ctx.reply(`❌ Gagal kirim: ${(e as Error).message}`).catch(() => {}));
  });

  bot.callbackQuery(/^fd:(.+)$/, (ctx) => {
    const pathId = ctx.match[1];
    const dirPath = pathCache.get(pathId);
    if (!dirPath) return ctx.answerCallbackQuery({ text: "Expired — /file lagi" });
    ctx.answerCallbackQuery().catch(() => {});
    listDirectory(ctx, dirPath);
  });

  bot.callbackQuery(/^ff:(.+)$/, async (ctx) => {
    const pathId = ctx.match[1];
    const filePath = pathCache.get(pathId);
    if (!filePath) return ctx.answerCallbackQuery({ text: "Expired — /file lagi" });
    await ctx.answerCallbackQuery({ text: `Sending ${path.basename(filePath)}...` });
    try {
      const stat = fs.statSync(filePath);
      if (stat.size > 50 * 1024 * 1024) {
        await ctx.reply(`❌ File terlalu besar: ${(stat.size / 1024 / 1024).toFixed(1)}MB`);
        return;
      }
      await ctx.replyWithDocument(new InputFile(filePath, path.basename(filePath)));
    } catch (e) {
      await ctx.reply(`❌ Gagal kirim: ${(e as Error).message}`);
    }
  });

  bot.callbackQuery(/^fp:(.+)$/, (ctx) => {
    const [pathId, pageStr] = ctx.match[1].split("|");
    const dirPath = pathCache.get(pathId);
    if (!dirPath) return ctx.answerCallbackQuery({ text: "Expired — /file lagi" });
    ctx.answerCallbackQuery().catch(() => {});
    listDirectory(ctx, dirPath, parseInt(pageStr) || 0);
  });
}
