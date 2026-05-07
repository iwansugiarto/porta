/**
 * Bot command and message handlers.
 *
 * Registers all Telegram bot commands and the free-text message handler.
 * Each command validates the session state and delegates to PortaClient.
 */

import { InlineKeyboard, InputFile } from "grammy";
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
import { escapeHtml, splitMessage } from "./formatter.js";
import { withRetry } from "./retry.js";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";

/** Helper to close a session's WebSocket without TypeScript narrowing issues. */
function cleanupWs(session: import("./session.js").ChatSession): void {
  if (session.wsConnection) {
    session.wsConnection.close();
    session.wsConnection = null;
  }
}

export function registerHandlers(bot: Bot, config: TelegramConfig): void {
  const client = new PortaClient(config.proxyBaseUrl, config.authToken);

  /** Resolve workspace: session override > config default > auto-detect. */
  function getEffectiveWorkspace(chatId: number): string | undefined {
    const session = getSession(chatId);
    return session?.workspaceUri ?? config.workspaceUri;
  }

  // ── /start ──

  bot.command("start", async (ctx) => {
    await ctx.reply(
      "🚀 <b>Porta Telegram Bridge</b>\n\n" +
        "Akses Antigravity langsung dari Telegram.\n\n" +
        "<b>💬 Chat:</b>\n" +
        "/new — Buat conversation baru\n" +
        "/list — Daftar conversations\n" +
        "/use <code>id</code> — Switch ke conversation\n" +
        "/latest — Response terakhir dari agent\n" +
        "/stop — Stop agent yang sedang berjalan\n" +
        "/end — Akhiri session saat ini\n\n" +
        "<b>🤖 Model:</b>\n" +
        "/models — Daftar model tersedia\n" +
        "/model <code>name</code> — Pilih model\n\n" +
        "<b>📂 Files:</b>\n" +
        "/workspace — Switch workspace/project\n" +
        "/file — Browse file project\n" +
        "/artifacts — Lihat artifacts conversation\n\n" +
        "<b>🔧 Tools:</b>\n" +
        "/cmd <code>command</code> — Jalankan shell command\n" +
        "/status — Status proxy &amp; LS\n" +
        "/restart — Restart bot\n" +
        "/help — Tampilkan bantuan\n\n" +
        "<i>Kirim pesan teks, foto, atau dokumen untuk chat dengan Antigravity.</i>",
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
        "<b>Media:</b>\n" +
        "Kirim foto atau dokumen dengan caption untuk diteruskan\n" +
        "ke Antigravity (e.g. \"fix this UI bug\").\n\n" +
        "<b>File Explorer:</b>\n" +
        "• /file — browse project files (with inline buttons)\n" +
        "• /file src/ — langsung ke subdirectory\n" +
        "• /artifacts — lihat artifacts conversation\n\n" +
        "<b>Remote Shell:</b>\n" +
        "• /cmd ls -la — jalankan command di server\n" +
        "• /cmd git status — cek status git\n" +
        "• Beberapa command berbahaya diblokir otomatis\n\n" +
        "<b>Approval:</b>\n" +
        "Saat agent perlu izin, bot akan tampilkan\n" +
        "tombol ✅ Approve / ❌ Reject.",
      { parse_mode: "HTML" },
    );
  });

  // ── /new ──

  bot.command("new", async (ctx) => {
    const chatId = ctx.chat.id;
    const statusMsg = await ctx.reply("⏳ Membuat conversation baru...");

    try {
      const result = await client.createConversation(getEffectiveWorkspace(chatId));
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

  // ── /workspace — list and switch workspaces ──

  bot.command("workspace", async (ctx) => {
    const chatId = ctx.chat.id;
    const wsArg = ctx.match?.trim();

    // If argument is given, set workspace directly
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

    // No argument — list available workspaces
    const statusMsg = await ctx.reply("⏳ Memuat daftar workspaces...");

    try {
      const workspaces = await client.listWorkspaces();
      const activeUris = new Set(workspaces.map((w) => w.workspaceUri));

      // Collect recent workspaces from conversation history
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
      const currentWs =
        session?.workspaceUri ?? config.workspaceUri ?? "";

      let text = "📂 <b>Workspaces</b>\n\n";
      const keyboard = new InlineKeyboard();

      // Active workspaces (LS running)
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
          keyboard
            .text(`🟢 ${shortPath}${marker}`, `ws:${wsId}`)
            .row();
        }
      }

      // Recent (inactive) workspaces — from conversation history
      if (recentWsUris.size > 0) {
        text += (workspaces.length > 0 ? "\n" : "") + "<b>⚪ Recent:</b>";
        for (const uri of [...recentWsUris].slice(0, 5)) {
          const shortPath = uri
            .replace(/^file:\/\//, "")
            .replace(os.homedir(), "~");
          const wsId = getPathId(uri);
          keyboard
            .text(`⚪ ${shortPath} ▶`, `wl:${wsId}`)
            .row();
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

  // ── /cmd <command> — execute shell command ──

  /** Dangerous command patterns to block. */
  const BLOCKED_CMD_PATTERNS = [
    /rm\s+(-[rf]+\s+)*\//i,
    /mkfs/i,
    /dd\s+if=/i,
    /:(){ :\|:& };:/,
    />\s*\/dev\/sd/i,
    /shutdown/i,
    /reboot/i,
    /init\s+0/i,
  ];

  bot.command("cmd", async (ctx) => {
    const cmdStr = ctx.match?.trim();
    if (!cmdStr) {
      await ctx.reply(
        "💡 Gunakan: /cmd <code>command</code>\n\n" +
          "Contoh: /cmd ls -la\n" +
          "Contoh: /cmd git status",
        { parse_mode: "HTML" },
      );
      return;
    }

    // Safety check
    if (BLOCKED_CMD_PATTERNS.some((p) => p.test(cmdStr))) {
      await ctx.reply("🚫 Command diblokir karena alasan keamanan.");
      return;
    }

    const statusMsg = await ctx.reply(
      `⏳ Running: <code>${escapeHtml(cmdStr.slice(0, 200))}</code>`,
      { parse_mode: "HTML" },
    );

    try {
      const result = await client.runShellCommand(cmdStr);
      let output = "";
      if (result.stdout) output += result.stdout;
      if (result.stderr) output += (output ? "\n" : "") + result.stderr;
      if (!output) output = "✅ Command selesai (tidak ada output).";

      const header = result.exitCode !== 0
        ? `❌ Exit code: ${result.exitCode}\n\n`
        : "";
      const fullText = header + output;

      const chunks = splitMessage(fullText);
      for (const chunk of chunks) {
        await withRetry(() =>
          ctx.reply(`<pre>${escapeHtml(chunk.slice(0, 3800))}</pre>`, {
            parse_mode: "HTML",
          }),
        );
      }
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /latest — get last agent response ──

  bot.command("latest", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif. Gunakan /new atau /list.");
      return;
    }

    const statusMsg = await ctx.reply("⏳ Mengambil response terakhir...");

    try {
      const detail = await client.getConversationDetail(session.cascadeId);
      // Extract the last planner response from steps
      const trajectories = detail.trajectories as Record<string, unknown>[] | undefined;
      let lastResponse = "";

      if (trajectories && trajectories.length > 0) {
        const lastTraj = trajectories[trajectories.length - 1];
        const steps = (lastTraj.steps ?? lastTraj.cortexSteps) as Record<string, unknown>[] | undefined;
        if (steps) {
          // Walk backwards to find the last planner response
          for (let i = steps.length - 1; i >= 0; i--) {
            const step = steps[i];
            const resp = step.plannerResponse as Record<string, unknown> | undefined;
            if (resp) {
              lastResponse =
                (resp.modifiedResponse as string) ??
                (resp.response as string) ??
                "";
              if (lastResponse) break;
            }
          }
        }
      }

      if (!lastResponse) {
        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          "📭 Tidak ada response ditemukan.",
        );
        return;
      }

      // Delete the status message
      await ctx.api.deleteMessage(chatId, statusMsg.message_id).catch(() => {});

      const chunks = splitMessage(lastResponse);
      for (const chunk of chunks) {
        await withRetry(() =>
          ctx.reply(escapeHtml(chunk), { parse_mode: "HTML" }),
        );
      }
    } catch (err) {
      await ctx.api.editMessageText(
        chatId,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /file — interactive file explorer ──

  const pathCache = new Map<string, string>();
  let pathIdCounter = 0;

  function getPathId(fullPath: string): string {
    for (const [id, p] of pathCache.entries()) {
      if (p === fullPath) return id;
    }
    const id = (++pathIdCounter).toString(36);
    pathCache.set(id, fullPath);
    if (pathCache.size > 2000) {
      const firstKey = pathCache.keys().next().value;
      if (firstKey) pathCache.delete(firstKey);
    }
    return id;
  }

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

  // File explorer callback handlers
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

  // ── /artifacts — list/download conversation artifacts ──

  let cachedArtifacts: { name: string; path: string }[] = [];

  bot.command("artifacts", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif. Gunakan /new atau /list.");
      return;
    }

    const artifactsDir = path.join(
      os.homedir(),
      ".gemini",
      "antigravity",
      "brain",
      session.cascadeId,
    );

    if (!fs.existsSync(artifactsDir)) {
      await ctx.reply("📭 Tidak ada artifacts untuk conversation ini.");
      return;
    }

    cachedArtifacts = [];
    const items = fs.readdirSync(artifactsDir, { withFileTypes: true });

    for (const item of items) {
      if (item.isDirectory()) {
        // Scan scratch/ subdirectory
        if (item.name === "scratch") {
          const scratchDir = path.join(artifactsDir, "scratch");
          const scratchItems = fs.readdirSync(scratchDir, { withFileTypes: true });
          for (const si of scratchItems) {
            if (!si.isDirectory()) {
              cachedArtifacts.push({
                name: `scratch/${si.name}`,
                path: path.join(scratchDir, si.name),
              });
            }
          }
        }
        continue;
      }
      const name = item.name;
      // Skip metadata files
      if (name.includes(".metadata.json") || name.startsWith(".sys")) continue;
      cachedArtifacts.push({ name, path: path.join(artifactsDir, name) });
    }

    if (cachedArtifacts.length === 0) {
      await ctx.reply("📭 Tidak ada artifacts untuk conversation ini.");
      return;
    }

    let msg = "📎 <b>Artifacts</b>\n\n";
    for (let i = 0; i < cachedArtifacts.length; i++) {
      const displayName = cachedArtifacts[i].name
        .replace(/\.[^/.]+$/, "")
        .replace(/_/g, " ");
      msg += `/artifact_${i + 1} — ${escapeHtml(displayName)}\n`;
    }

    await ctx.reply(msg, { parse_mode: "HTML" });
  });

  bot.hears(/^\/artifact_(\d+)$/, async (ctx) => {
    const num = parseInt(ctx.match[1], 10);
    if (num < 1 || num > cachedArtifacts.length) {
      await ctx.reply("❌ Nomor artifact tidak valid.");
      return;
    }
    const artifact = cachedArtifacts[num - 1];
    const ext = path.extname(artifact.name).toLowerCase();

    try {
      if ([".png", ".jpg", ".jpeg", ".webp"].includes(ext)) {
        await ctx.replyWithPhoto(new InputFile(artifact.path));
      } else if ([".mp4", ".mov", ".webm"].includes(ext)) {
        await ctx.replyWithVideo(new InputFile(artifact.path));
      } else if (ext === ".md") {
        const content = fs.readFileSync(artifact.path, "utf8");
        const chunks = splitMessage(content);
        for (const chunk of chunks) {
          await ctx.reply(chunk);
        }
      } else {
        await ctx.replyWithDocument(
          new InputFile(artifact.path, path.basename(artifact.name)),
        );
      }
    } catch (e) {
      await ctx.reply(`❌ Error: ${(e as Error).message}`);
    }
  });

  // ── /restart — restart the bot process ──

  bot.command("restart", async (ctx) => {
    await ctx.reply("🔄 Bot restarting...");
    process.exit(0);
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
        const wsUri = session.workspaceUri ?? config.workspaceUri;
        if (wsUri) {
          const shortWs = wsUri.replace(/^file:\/\//, "").replace(os.homedir(), "~");
          text += `\n<b>Workspace:</b> <code>${escapeHtml(shortWs)}</code>`;
        }
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

      // Preserve workspace from previous session
      const prevSession = getSession(chatId);
      const prevWorkspace = prevSession?.workspaceUri;

      switchSession(chatId, cascadeId);

      // Restore workspace on the new session
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

    // ── Handle "ws:<pathId>" (workspace switch) ──
    if (data.startsWith("ws:")) {
      const wsPathId = data.slice(3);
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

      // Set workspace on current session (or remember for new ones)
      let session = getSession(chatId);
      if (session) {
        session.workspaceUri = wsUri;
      }

      // List existing conversations for this workspace
      try {
        const conversations = await client.listConversations();
        const filtered = conversations.filter((c) => {
          const cWs = c.workspaces?.[0]?.workspaceFolderAbsoluteUri;
          return cWs && cWs === wsUri;
        }).slice(0, 8);

        const keyboard = new InlineKeyboard();

        // Show existing conversations
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

        // Always add a "New" button
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
      return;
    }

    // ── Handle "wl:<pathId>" (workspace launch — open in Antigravity) ──
    if (data.startsWith("wl:")) {
      const wsPathId = data.slice(3);
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

      // Launch Antigravity with the folder
      const antigravityCli =
        "/Applications/Antigravity.app/Contents/Resources/app/bin/antigravity";
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

      // Poll for the workspace to become available (max 30s)
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

      // Workspace is now active — show conversations
      try {
        const conversations = await client.listConversations();
        const filtered = conversations.filter((c) => {
          const cWs = c.workspaces?.[0]?.workspaceFolderAbsoluteUri;
          return cWs && cWs === wsUri;
        }).slice(0, 8);

        // Set workspace on session
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
      return;
    }

    // ── Handle "wn:<pathId>" (new conversation in workspace) ──
    if (data.startsWith("wn:")) {
      const wsPathId = data.slice(3);
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

    // Close existing WebSocket if any (new user message = new streaming session)
    cleanupWs(session);
    session.streamMessageId = null;
    session.streamBuffer = "";

    // Connect WebSocket FIRST so it catches the "activate" signal
    // emitted by the proxy when the message is processed.
    connectStreamer(ctx.api, chatId, session, client, config);

    // Send the message to Porta (triggers activate → WS transitions to ACTIVE)
    try {
      await client.sendMessage(session.cascadeId, text, session.selectedModel);
    } catch (err) {
      const errMsg = (err as Error).message;
      if (errMsg.includes("not_found") || errMsg.includes("502")) {
        // Try to recall (load from disk) and retry once
        try {
          await client.recallConversation(session.cascadeId);
          await client.sendMessage(session.cascadeId, text, session.selectedModel);
        } catch (retryErr) {
          // Clean up the WS we opened
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
        // Clean up the WS on non-recoverable error
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

  bot.on("message:photo", async (ctx) => {
    const chatId = ctx.chat.id;
    const caption = ctx.message.caption ?? "Describe this image";

    let session = getSession(chatId);

    // Auto-create a conversation if none exists
    if (!session) {
      const statusMsg = await ctx.reply("⏳ Membuat conversation baru...");
      try {
        const result = await client.createConversation(getEffectiveWorkspace(chatId));
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
    cleanupWs(session);
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
      cleanupWs(session);
      await ctx.reply(
        `❌ Gagal mengirim gambar: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
      return;
    }
  });

  // ── Document messages → save and tell agent about the file ──

  bot.on("message:document", async (ctx) => {
    const chatId = ctx.chat.id;
    const caption = ctx.message.caption ?? "Examine this file";
    const doc = ctx.message.document;

    let session = getSession(chatId);

    // Auto-create a conversation if none exists
    if (!session) {
      const statusMsg = await ctx.reply("⏳ Membuat conversation baru...");
      try {
        const result = await client.createConversation(getEffectiveWorkspace(chatId));
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
    cleanupWs(session);
    session.streamMessageId = null;
    session.streamBuffer = "";

    try {
      // Download file from Telegram
      const file = await ctx.api.getFile(doc.file_id);
      const fileName = doc.file_name ?? "telegram_upload";
      const fileUrl = `https://api.telegram.org/file/bot${config.botToken}/${file.file_path}`;

      const tmpDir = path.join(os.tmpdir(), "porta-telegram");
      fs.mkdirSync(tmpDir, { recursive: true });
      const dest = path.join(tmpDir, `tg_${Date.now()}_${fileName}`);

      const response = await fetch(fileUrl);
      if (!response.ok) {
        throw new Error(`Failed to download file: HTTP ${response.status}`);
      }
      const buffer = Buffer.from(await response.arrayBuffer());
      fs.writeFileSync(dest, buffer);

      await ctx.reply(`📥 File disimpan: <code>${escapeHtml(fileName)}</code>`, {
        parse_mode: "HTML",
      });

      // Connect WebSocket FIRST
      connectStreamer(ctx.api, chatId, session, client, config);

      // Send a system message telling agent about the file
      const query =
        `[The user has uploaded a file. Use your view_file tool to examine it at: ${dest}]\n` +
        `${caption}`;

      await client.sendMessage(
        session.cascadeId,
        query,
        session.selectedModel,
      );
    } catch (err) {
      // Clean up the WS on error
      cleanupWs(session);
      await ctx.reply(
        `❌ Gagal mengirim dokumen: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
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
  cleanupWs(session);
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
