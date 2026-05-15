import { InlineKeyboard, InputFile } from "grammy";
import type { Bot } from "grammy";
import type { TelegramConfig } from "../config.js";
import type { PortaClient } from "../porta-client.js";
import { getSession, createSession, switchSession, destroySession, addBookmark, getBookmarks, clearBookmarks } from "../session.js";
import { escapeHtml, splitMessage } from "../formatter.js";
import { withRetry } from "../retry.js";
import { formatUptime } from "./utils.js";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";

export function registerCommands(
  bot: Bot,
  client: PortaClient,
  config: TelegramConfig,
): void {
  function getEffectiveWorkspace(chatId: number): string | undefined {
    const session = getSession(chatId);
    return session?.workspaceUri ?? config.workspaceUri;
  }

  async function sendTextAsChunksOrFile(ctx: any, text: string, filename: string, useCodeExt?: string) {
    const chunks = splitMessage(text);
    if (chunks.length > 3) {
      const buffer = Buffer.from(text, "utf-8");
      await ctx.replyWithDocument(new InputFile(buffer, filename));
    } else {
      for (const chunk of chunks) {
        const escaped = escapeHtml(chunk.slice(0, 3800));
        const content = useCodeExt ? `<pre><code class="language-${useCodeExt}">${escaped}</code></pre>` : `<pre>${escaped}</pre>`;
        await withRetry(() => ctx.reply(content, { parse_mode: "HTML" }));
      }
    }
  }

  bot.command("start", async (ctx) => {
    const keyboard = new InlineKeyboard()
      .text("💬 New Chat", "quick:new").text("📋 My Chats", "quick:list").row()
      .text("📂 Browse Files", "quick:file").text("🔍 Status", "quick:status").row()
      .text("📖 Full Help", "quick:help");

    await ctx.reply(
      "🚀 <b>Porta Telegram Bridge</b>\n\n" +
        "Akses <b>Antigravity AI</b> langsung dari Telegram.\n\n" +
        "💡 <b>Quick Start:</b>\n" +
        "• Ketik pesan langsung untuk chat dengan AI\n" +
        "• Kirim foto/dokumen untuk analisis\n" +
        "• Reply ke pesan bot untuk konteks\n\n" +
        "Pilih aksi di bawah, atau ketik /help untuk bantuan lengkap.",
      { parse_mode: "HTML", reply_markup: keyboard },
    );
  });

  // Handle quick-action callbacks from /start
  bot.callbackQuery(/^quick:(.+)$/, async (ctx) => {
    await ctx.answerCallbackQuery();
    const action = ctx.match[1];
    const chatId = ctx.chat!.id;

    switch (action) {
      case "new": {
        try {
          const result = await client.createConversation(getEffectiveWorkspace(chatId));
          createSession(chatId, result.cascadeId);
          const shortId = result.cascadeId.slice(0, 8);
          await ctx.reply(
            `✅ Conversation baru: <code>${shortId}</code>\n<i>Kirim pesan untuk mulai chat.</i>`,
            { parse_mode: "HTML" },
          );
        } catch (err) {
          await ctx.reply(`❌ Gagal: ${escapeHtml((err as Error).message)}`, { parse_mode: "HTML" });
        }
        break;
      }
      case "list": {
        await renderConversationPage(ctx, 1);
        break;
      }
      case "file": {
        // Trigger /file command logic
        const ws = getEffectiveWorkspace(chatId);
        if (!ws) {
          await ctx.reply("⚠️ Workspace belum di-set. Gunakan /workspace dulu.");
        } else {
          await ctx.reply(`📂 Gunakan /file untuk browse files di workspace.`);
        }
        break;
      }
      case "status": {
        try {
          await client.getHealth();
          const session = getSession(chatId);
          const convId = session ? session.cascadeId.slice(0, 8) : "none";
          await ctx.reply(
            `🔍 <b>Status</b>\n\n` +
              `• Proxy: ✅ connected\n` +
              `• Session: <code>${convId}</code>\n` +
              `• Model: <code>${session?.selectedModel ?? "default"}</code>`,
            { parse_mode: "HTML" },
          );
        } catch (err) {
          await ctx.reply(`❌ Proxy unreachable: ${escapeHtml((err as Error).message)}`, { parse_mode: "HTML" });
        }
        break;
      }
      case "help": {
        await ctx.reply(
          "<b>💬 Chat:</b>\n" +
            "/new — Buat conversation baru\n" +
            "/list — Daftar conversations\n" +
            "/use <code>id</code> — Switch ke conversation\n" +
            "/history — History conversation\n" +
            "/latest — Response terakhir\n" +
            "/clear — Bersihkan context\n" +
            "/quiet — Toggle silent mode\n" +
            "/export — Export conversation ke file\n" +
            "/retry — Retry/lanjutkan task terakhir\n" +
            "/stop — Stop agent\n" +
            "/end — Akhiri session\n\n" +
            "<b>🤖 Model:</b>\n" +
            "/models — Daftar model\n" +
            "/model <code>name</code> — Pilih model\n\n" +
            "<b>📂 Files:</b>\n" +
            "/workspace — Switch workspace\n" +
            "/file — Browse file\n" +
            "/artifacts — Lihat artifacts\n" +
            "/cat <code>file</code> — Baca file\n" +
            "/bookmark — Simpan pesan bot\n" +
            "/bookmarks — Lihat bookmarks\n\n" +
            "<b>🔧 Tools:</b>\n" +
            "/cmd <code>command</code> — Shell command\n" +
            "/diff — Git diff\n" +
            "/logs — Service logs\n" +
            "/search <code>text</code> — Cari teks\n" +
            "/find <code>name</code> — Cari file\n" +
            "/status — Status proxy\n" +
            "/ps — Proses aktif\n" +
            "/restart — Restart bot",
          { parse_mode: "HTML" },
        );
        break;
      }
    }
  });

  bot.command("help", async (ctx) => {
    await ctx.reply(
      "📖 <b>Bantuan Porta Bot</b>\n\n" +
        "<b>Mulai baru:</b>\n" +
        "1. Ketik /new untuk buat conversation\n" +
        "2. Kirim pesan — bot akan meneruskan ke Antigravity\n" +
        "3. Response akan di-stream secara real-time\n\n" +
        "<b>Conversations:</b>\n" +
        "• /list — daftar conversations (grouped by workspace)\n" +
        "• /chatsearch <i>keyword</i> — cari conversation\n" +
        "• /delete — hapus conversation aktif\n" +
        "• /history — lihat riwayat chat\n\n" +
        "<b>Media:</b>\n" +
        "Kirim foto atau dokumen dengan caption untuk diteruskan\n" +
        "ke Antigravity (e.g. \"fix this UI bug\").\n\n" +
        "<b>File Explorer:</b>\n" +
        "• /file — browse project files (with inline buttons)\n" +
        "• /file src/ — langsung ke subdirectory\n" +
        "• /artifacts — lihat artifacts conversation\n\n" +
        "<b>Remote Shell & Logs:</b>\n" +
        "• /cmd ls -la — jalankan command di server\n" +
        "• /cat file.txt — baca file teks\n" +
        "• /diff — lihat perubahan file (git diff)\n" +
        "• /logs proxy 50 — tail 50 baris log\n" +
        "• /ps — lihat proses aktif\n" +
        "• /search text — cari string di workspace\n" +
        "• Beberapa command berbahaya diblokir otomatis\n\n" +
        "<b>Approval & Settings:</b>\n" +
        "• /settings — panel pengaturan terpadu\n" +
        "• /compact — toggle compact mode (sembunyikan tool steps)\n" +
        "• /pin — pin pesan (reply ke pesan lalu /pin)\n" +
        "Saat agent perlu izin, bot akan tampilkan\n" +
        "tombol ✅ Approve / ❌ Reject.\n" +
        "Gunakan /autoapprove on untuk bypass approval.",
      { parse_mode: "HTML" },
    );
  });

  bot.command("new", async (ctx) => {
    const chatId = ctx.chat.id;
    const statusMsg = await ctx.reply("⏳ Membuat conversation baru...");

    try {
      const result = await client.createConversation(getEffectiveWorkspace(chatId));
      createSession(chatId, result.cascadeId);
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

  /** Format elapsed time as human-readable relative string. */
  function relativeTime(isoOrMs: string | number | undefined): string {
    if (!isoOrMs) return "";
    const ms = typeof isoOrMs === "number" ? isoOrMs : new Date(isoOrMs).getTime();
    const diff = Date.now() - ms;
    const mins = Math.floor(diff / 60_000);
    if (mins < 1) return "just now";
    if (mins < 60) return `${mins}m ago`;
    const hours = Math.floor(mins / 60);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
  }

  /** Extract workspace name from a conversation's workspace URI. */
  function extractWorkspaceName(conv: { workspaces?: { workspaceFolderAbsoluteUri?: string }[] }): string {
    const ws = conv.workspaces?.[0];
    if (!ws?.workspaceFolderAbsoluteUri) return "Others";
    return ws.workspaceFolderAbsoluteUri.split("/").pop() ?? "Others";
  }

  async function renderConversationPage(
    ctx: any,
    page: number,
    messageIdToEdit?: number,
  ) {
    const chatId = ctx.chat.id;
    let statusMsg: any;
    if (!messageIdToEdit) {
      statusMsg = await ctx.reply("⏳ Memuat daftar conversations...");
    }

    try {
      const conversations = await client.listConversations();

      if (conversations.length === 0) {
        const txt = "📭 Tidak ada conversation. Ketik /new untuk membuat.";
        if (messageIdToEdit) {
          await ctx.api.editMessageText(chatId, messageIdToEdit, txt);
        } else {
          await ctx.api.editMessageText(chatId, statusMsg.message_id, txt);
        }
        return;
      }

      // Group by workspace — mirrors web's Sidebar workspace groups
      const groups = new Map<string, typeof conversations>();
      for (const conv of conversations) {
        const wsName = extractWorkspaceName(conv);
        const list = groups.get(wsName) ?? [];
        list.push(conv);
        groups.set(wsName, list);
      }

      // Sort groups: those with running conversations first, then by recency
      const sortedGroups = [...groups.entries()].sort(([, a], [, b]) => {
        const aRunning = a.some(c => c.status === "CASCADE_RUN_STATUS_RUNNING");
        const bRunning = b.some(c => c.status === "CASCADE_RUN_STATUS_RUNNING");
        if (aRunning !== bRunning) return aRunning ? -1 : 1;
        const aTime = Math.max(...a.map(c => new Date(c.lastModifiedTime ?? 0).getTime()));
        const bTime = Math.max(...b.map(c => new Date(c.lastModifiedTime ?? 0).getTime()));
        return bTime - aTime;
      });

      // Flatten for pagination
      const PAGE_SIZE = 8;
      const allItems: { wsName: string; conv: typeof conversations[0] }[] = [];
      for (const [wsName, convs] of sortedGroups) {
        // Sort within group: running first, then by recency
        convs.sort((a, b) => {
          const aR = a.status === "CASCADE_RUN_STATUS_RUNNING" ? 0 : 1;
          const bR = b.status === "CASCADE_RUN_STATUS_RUNNING" ? 0 : 1;
          if (aR !== bR) return aR - bR;
          return new Date(b.lastModifiedTime ?? 0).getTime() - new Date(a.lastModifiedTime ?? 0).getTime();
        });
        for (const conv of convs) {
          allItems.push({ wsName, conv });
        }
      }

      const maxPage = Math.ceil(allItems.length / PAGE_SIZE);
      const currentPage = Math.max(1, Math.min(page, maxPage));
      const startIndex = (currentPage - 1) * PAGE_SIZE;
      const pageItems = allItems.slice(startIndex, startIndex + PAGE_SIZE);
      const currentSession = getSession(chatId);

      // Build text header with workspace group labels
      let text = `📋 <b>Conversations</b> (${currentPage}/${maxPage})\n`;
      const runningCount = conversations.filter(c => c.status === "CASCADE_RUN_STATUS_RUNNING").length;
      if (runningCount > 0) {
        text += `🟢 ${runningCount} running\n`;
      }
      text += `\n<i>Tap untuk switch:</i>`;

      const keyboard = new InlineKeyboard();
      let lastWsName = "";
      for (const { wsName, conv } of pageItems) {
        // Show workspace header when group changes
        if (wsName !== lastWsName) {
          lastWsName = wsName;
          keyboard.text(`📂 ${wsName}`, `noop:${wsName}`).row();
        }

        const shortId = conv.id.slice(0, 8);
        const isActive = currentSession?.cascadeId === conv.id;
        const isRunning = conv.status === "CASCADE_RUN_STATUS_RUNNING";
        const statusIcon = isRunning ? "🟢" : "⚪";
        const marker = isActive ? " 👈" : "";
        const time = relativeTime(conv.lastModifiedTime);
        const steps = conv.stepCount > 0 ? `${conv.stepCount}s` : "";
        const meta = [time, steps].filter(Boolean).join(" · ");
        const summary = conv.summary.length > 25
          ? conv.summary.slice(0, 22) + "..."
          : conv.summary;

        keyboard.text(`${statusIcon} ${summary} (${meta})${marker}`, `use:${conv.id}`).row();
      }

      const navRow = [];
      if (currentPage > 1) {
        navRow.push(InlineKeyboard.text("⬅️ Prev", `list_page:${currentPage - 1}`));
      }
      if (currentPage < maxPage) {
        navRow.push(InlineKeyboard.text("Next ➡️", `list_page:${currentPage + 1}`));
      }
      if (navRow.length > 0) {
        keyboard.row(...navRow);
      }
      // Always add refresh button
      keyboard.row(InlineKeyboard.text("🔄 Refresh", `list_page:${currentPage}`));

      const targetMsgId = messageIdToEdit ?? statusMsg.message_id;
      await ctx.api.editMessageText(chatId, targetMsgId, text, {
        parse_mode: "HTML",
        reply_markup: keyboard,
      });
    } catch (err) {
      const errMsg = `❌ Error: ${escapeHtml((err as Error).message)}`;
      if (messageIdToEdit) {
        await ctx.api.editMessageText(chatId, messageIdToEdit, errMsg, { parse_mode: "HTML" });
      } else {
        await ctx.api.editMessageText(chatId, statusMsg.message_id, errMsg, { parse_mode: "HTML" });
      }
    }
  }

  bot.command("list", async (ctx) => {
    const pageMatch = ctx.match?.trim();
    let page = 1;
    if (pageMatch && /^\d+$/.test(pageMatch)) {
      page = parseInt(pageMatch, 10);
    }
    await renderConversationPage(ctx, page);
  });

  bot.callbackQuery(/^list_page:(\d+)$/, async (ctx) => {
    const page = parseInt(ctx.match[1], 10);
    await renderConversationPage(ctx, page, ctx.callbackQuery.message?.message_id);
    await ctx.answerCallbackQuery();
  });

  bot.command("use", async (ctx) => {
    const chatId = ctx.chat.id;
    const idArg = ctx.match?.trim();

    if (!idArg) {
      await ctx.reply(
        "💡 Gunakan: /use <code>id</code>\n\nLihat daftar ID dengan /list",
        { parse_mode: "HTML" },
      );
      return;
    }

    const statusMsg = await ctx.reply("⏳ Mencari conversation...");

    try {
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

  const BLOCKED_CMD_PATTERNS = [
    /rm\s+(-[rf]+\s+)*\//i,
    /mkfs/i,
    /dd\s+if=/i,
    /:\(\){ :\|:& };:/,
    />\s*\/dev\/sd/i,
    /shutdown/i,
    /reboot/i,
    /init\s+0/i,
  ];

  bot.command("cmd", async (ctx) => {
    const cmdStr = ctx.match?.trim();
    if (!cmdStr) {
      await ctx.reply(
        "💡 Gunakan: /cmd <code>command</code>\n\nContoh: /cmd ls -la\nContoh: /cmd git status",
        { parse_mode: "HTML" },
      );
      return;
    }

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

      await sendTextAsChunksOrFile(ctx, fullText, "cmd_output.txt");
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  bot.command("diff", async (ctx) => {
    const statusMsg = await ctx.reply("⏳ Mengambil git diff...");
    try {
      const cwd = config.workspaceUri ? config.workspaceUri.replace(/^file:\/\//, "") : process.cwd();
      const { exec } = await import("node:child_process");
      const util = await import("node:util");
      const execPromise = util.promisify(exec);

      const { stdout, stderr } = await execPromise("git diff", { cwd });
      if (!stdout && !stderr) {
        await ctx.api.editMessageText(ctx.chat.id, statusMsg.message_id, "✅ Tidak ada perubahan (clean working tree).");
        return;
      }

      await ctx.api.deleteMessage(ctx.chat.id, statusMsg.message_id).catch(() => {});

      await sendTextAsChunksOrFile(ctx, stdout || stderr, "diff_output.diff", "diff");
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  bot.command("logs", async (ctx) => {
    const args = ctx.match?.trim().split(/\s+/) || [];
    const service = args[0] || "proxy";
    const lines = parseInt(args[1] || "50", 10);

    if (!["proxy", "web", "telegram"].includes(service)) {
      await ctx.reply("💡 Gunakan: /logs [proxy|web|telegram] [baris]\nContoh: /logs proxy 100", { parse_mode: "HTML" });
      return;
    }

    const statusMsg = await ctx.reply(`⏳ Membaca ${lines} baris log ${service}...`);

    try {
      const rootDir = config.workspaceUri ? config.workspaceUri.replace(/^file:\/\//, "") : path.join(process.cwd(), "../../");
      const logPath = path.join(rootDir, "logs", `${service}.log`);

      if (!fs.existsSync(logPath)) {
        await ctx.api.editMessageText(ctx.chat.id, statusMsg.message_id, `❌ File log tidak ditemukan: ${service}.log`);
        return;
      }

      const { exec } = await import("node:child_process");
      const util = await import("node:util");
      const execPromise = util.promisify(exec);

      const { stdout } = await execPromise(`tail -n ${lines} "${logPath}"`);

      await ctx.api.deleteMessage(ctx.chat.id, statusMsg.message_id).catch(() => {});

      if (!stdout) {
        await ctx.reply("📭 Log kosong.");
        return;
      }

      await sendTextAsChunksOrFile(ctx, stdout, `${service}_logs.txt`);
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  bot.command("cat", async (ctx) => {
    const fileArg = ctx.match?.trim();
    if (!fileArg) {
      await ctx.reply("💡 Gunakan: /cat <code>path/to/file</code>\n\nContoh: /cat src/main.ts", { parse_mode: "HTML" });
      return;
    }

    const wsUri = getEffectiveWorkspace(ctx.chat.id) || process.cwd();
    const rootDir = wsUri.replace(/^file:\/\//, "");
    const fullPath = fileArg.startsWith("/") ? fileArg : path.join(rootDir, fileArg);

    const statusMsg = await ctx.reply(`⏳ Membaca file: <code>${escapeHtml(fileArg)}</code>...`, { parse_mode: "HTML" });

    try {
      if (!fs.existsSync(fullPath)) {
        await ctx.api.editMessageText(ctx.chat.id, statusMsg.message_id, `❌ File tidak ditemukan:\n<code>${escapeHtml(fullPath)}</code>`, { parse_mode: "HTML" });
        return;
      }
      
      const stat = fs.statSync(fullPath);
      if (stat.isDirectory()) {
        await ctx.api.editMessageText(ctx.chat.id, statusMsg.message_id, `❌ <code>${escapeHtml(fileArg)}</code> adalah direktori. Gunakan /file.`, { parse_mode: "HTML" });
        return;
      }

      if (stat.size > 2 * 1024 * 1024) {
        await ctx.api.editMessageText(ctx.chat.id, statusMsg.message_id, `❌ File terlalu besar untuk ditampilkan di chat (>${(stat.size/1024/1024).toFixed(1)}MB). Gunakan /file untuk mendownload.`);
        return;
      }

      const content = fs.readFileSync(fullPath, "utf-8");
      await ctx.api.deleteMessage(ctx.chat.id, statusMsg.message_id).catch(() => {});
      
      const ext = path.extname(fullPath).slice(1) || "txt";
      await sendTextAsChunksOrFile(ctx, content, path.basename(fullPath), ext);
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Error membaca file: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

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
      const trajectories = detail.trajectories as Record<string, unknown>[] | undefined;
      let lastResponse = "";

      if (trajectories && trajectories.length > 0) {
        const lastTraj = trajectories[trajectories.length - 1];
        const steps = (lastTraj.steps ?? lastTraj.cortexSteps) as Record<string, unknown>[] | undefined;
        if (steps) {
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

  bot.command("history", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif. Gunakan /new atau /list.");
      return;
    }

    const statusMsg = await ctx.reply("⏳ Mengambil history conversation...");

    try {
      const detail = await client.getConversationDetail(session.cascadeId);
      const trajectories = detail.trajectories as Record<string, unknown>[] | undefined;

      if (!trajectories || trajectories.length === 0) {
        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          "📭 Tidak ada history untuk conversation ini.",
        );
        return;
      }

      const countArg = ctx.match?.trim();
      const count = countArg && /^\d+$/.test(countArg) ? parseInt(countArg, 10) : 5;
      
      let historyText = `📜 <b>History Terakhir (${count} items)</b>\n\n`;
      const recent = trajectories.slice(-count);
      
      for (let i = 0; i < recent.length; i++) {
        const t = recent[i];
        
        // Find user message
        const firstStep = ((t.steps ?? t.cortexSteps) as Record<string, unknown>[])?.[0];
        const userMsg = firstStep?.plannerResponse ? null : (t.summary as string) ?? `Turn ${i + 1}`;
        if (userMsg) {
          historyText += `👤 <b>User:</b> ${escapeHtml(userMsg)}\n`;
        }

        // Find agent response
        let agentResp = "";
        const steps = (t.steps ?? t.cortexSteps) as Record<string, unknown>[] | undefined;
        if (steps) {
          for (let j = steps.length - 1; j >= 0; j--) {
            const resp = steps[j].plannerResponse as Record<string, unknown> | undefined;
            if (resp) {
              agentResp = (resp.modifiedResponse as string) ?? (resp.response as string) ?? "";
              if (agentResp) break;
            }
          }
        }
        
        if (agentResp) {
          const shortResp = agentResp.length > 200 ? agentResp.slice(0, 200) + "..." : agentResp;
          historyText += `🤖 <b>Agent:</b> ${escapeHtml(shortResp)}\n\n`;
        } else {
          historyText += `🤖 <b>Agent:</b> <i>(No response)</i>\n\n`;
        }
      }

      await ctx.api.deleteMessage(chatId, statusMsg.message_id).catch(() => {});

      const chunks = splitMessage(historyText);
      if (chunks.length > 3) {
        const buffer = Buffer.from(historyText, "utf-8");
        await ctx.replyWithDocument(new InputFile(buffer, `history_${session.cascadeId.slice(0, 8)}.txt`));
      } else {
        for (const chunk of chunks) {
          await withRetry(() =>
            ctx.reply(chunk, { parse_mode: "HTML" }),
          );
        }
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

  // ── /delete — Delete a conversation (mirrors web's context menu delete) ──
  bot.command("delete", async (ctx) => {
    const chatId = ctx.chat.id;
    const idArg = ctx.match?.trim();
    const session = getSession(chatId);

    let targetId = idArg;
    if (!targetId && session) {
      targetId = session.cascadeId;
    }

    if (!targetId) {
      await ctx.reply(
        "💡 Gunakan: /delete [id]\n\n" +
          "Tanpa ID, conversation aktif akan dihapus.\n" +
          "<i>Gunakan /list untuk melihat daftar.</i>",
        { parse_mode: "HTML" },
      );
      return;
    }

    // Resolve short IDs
    let fullId = targetId;
    if (targetId.length < 20) {
      try {
        const conversations = await client.listConversations();
        const match = conversations.find(c => c.id.startsWith(targetId!));
        if (match) fullId = match.id;
      } catch { /* use as-is */ }
    }

    const shortId = fullId.slice(0, 8);
    const keyboard = new InlineKeyboard()
      .text("⚠️ Ya, hapus", `del_confirm:${fullId}`)
      .text("❌ Batal", `del_cancel:${fullId}`);

    await ctx.reply(
      `🗑 <b>Hapus conversation</b> <code>${shortId}</code>?\n\n` +
        "<i>Aksi ini tidak bisa dibatalkan.</i>",
      { parse_mode: "HTML", reply_markup: keyboard },
    );
  });

  // ── /chatsearch — Search across conversation summaries ──
  bot.command("chatsearch", async (ctx) => {
    const chatId = ctx.chat.id;
    const query = ctx.match?.trim();

    if (!query) {
      await ctx.reply(
        "🔍 Gunakan: /chatsearch <code>keyword</code>\n\n" +
          "Contoh: /chatsearch telegram bot\n" +
          "<i>Mencari di judul/summary semua conversation.</i>",
        { parse_mode: "HTML" },
      );
      return;
    }

    const statusMsg = await ctx.reply(`🔍 Mencari "<code>${escapeHtml(query)}</code>"...`, { parse_mode: "HTML" });

    try {
      const conversations = await client.listConversations();
      const lowerQuery = query.toLowerCase();
      const matches = conversations.filter(c =>
        c.summary.toLowerCase().includes(lowerQuery) ||
        c.id.toLowerCase().startsWith(lowerQuery)
      );

      if (matches.length === 0) {
        await ctx.api.editMessageText(
          chatId,
          statusMsg.message_id,
          `📭 Tidak ditemukan: "<code>${escapeHtml(query)}</code>"`,
          { parse_mode: "HTML" },
        );
        return;
      }

      const currentSession = getSession(chatId);
      let text = `🔍 <b>Hasil pencarian</b> "${escapeHtml(query)}" — ${matches.length} ditemukan\n\n<i>Tap untuk switch:</i>`;
      const keyboard = new InlineKeyboard();

      for (const conv of matches.slice(0, 10)) {
        const shortId = conv.id.slice(0, 8);
        const isActive = currentSession?.cascadeId === conv.id;
        const statusIcon = conv.status === "CASCADE_RUN_STATUS_RUNNING" ? "🟢" : "⚪";
        const marker = isActive ? " 👈" : "";
        const time = relativeTime(conv.lastModifiedTime);
        const summary = conv.summary.length > 30
          ? conv.summary.slice(0, 27) + "..."
          : conv.summary;

        keyboard.text(`${statusIcon} ${summary} (${time})${marker}`, `use:${conv.id}`).row();
      }

      if (matches.length > 10) {
        keyboard.row(InlineKeyboard.text(`📋 +${matches.length - 10} more`, `noop:more`));
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

    const newArtifacts: { name: string; path: string }[] = [];
    const items = fs.readdirSync(artifactsDir, { withFileTypes: true });

    for (const item of items) {
      if (item.isDirectory()) {
        if (item.name === "scratch") {
          const scratchDir = path.join(artifactsDir, "scratch");
          const scratchItems = fs.readdirSync(scratchDir, { withFileTypes: true });
          for (const si of scratchItems) {
            if (!si.isDirectory()) {
              newArtifacts.push({
                name: `scratch/${si.name}`,
                path: path.join(scratchDir, si.name),
              });
            }
          }
        }
        continue;
      }
      const name = item.name;
      if (name.includes(".metadata.json") || name.startsWith(".sys")) continue;
      newArtifacts.push({ name, path: path.join(artifactsDir, name) });
    }

    // Sort alphabetically for easier scanning
    newArtifacts.sort((a, b) => a.name.localeCompare(b.name));

    if (newArtifacts.length === 0) {
      await ctx.reply("📭 Tidak ada artifacts untuk conversation ini.");
      return;
    }

    session.cachedArtifacts = newArtifacts;

    let text = `📎 <b>Artifacts</b> (${session.cachedArtifacts.length})\n\n<i>Tap untuk melihat artifact:</i>`;
    const keyboard = new InlineKeyboard();

    for (let i = 0; i < session.cachedArtifacts.length; i++) {
      const art = session.cachedArtifacts[i];
      const ext = path.extname(art.name).toLowerCase();
      const displayName = art.name
        .replace(/\.[^/.]+$/, "")
        .replace(/_/g, " ");
      // File type icons
      let icon = "📄";
      if (ext === ".md") icon = "📝";
      else if ([".png", ".jpg", ".jpeg", ".webp", ".gif"].includes(ext)) icon = "🖼";
      else if ([".mp4", ".mov", ".webm"].includes(ext)) icon = "🎬";
      else if ([".ts", ".js", ".py", ".dart"].includes(ext)) icon = "💻";
      else if (ext === ".json") icon = "📋";

      // File size
      let sizeStr = "";
      try {
        const stat = fs.statSync(art.path);
        const kb = Math.round(stat.size / 1024);
        sizeStr = kb > 0 ? ` (${kb}KB)` : " (<1KB)";
      } catch { /* skip */ }

      keyboard.text(`${icon} ${displayName}${sizeStr}`, `art:${i}`).row();
    }

    await ctx.reply(text, { parse_mode: "HTML", reply_markup: keyboard });
  });

  bot.callbackQuery(/^art:(\d+)$/, async (ctx) => {
    const num = parseInt(ctx.match[1], 10);
    const chatId = ctx.chat?.id;
    if (!chatId) return;

    const session = getSession(chatId);
    if (!session || !session.cachedArtifacts || num < 0 || num >= session.cachedArtifacts.length) {
      await ctx.answerCallbackQuery({ text: "❌ Artifact tidak ditemukan atau session expired.", show_alert: true });
      return;
    }

    const artifact = session.cachedArtifacts[num];
    const ext = path.extname(artifact.name).toLowerCase();

    await ctx.answerCallbackQuery({ text: `Membuka ${artifact.name}...` });

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


  // ── /summary — One-glance dashboard ──
  bot.command("summary", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    let text = `📊 <b>Dashboard</b>\n\n`;

    // Connection status
    try {
      const health = await client.getHealth();
      text += `🟢 Proxy: connected\n`;
      text += `🔑 Auto-approve: ${health.autoApprove ? "ON ✅" : "OFF ❌"}\n`;
    } catch {
      text += `🔴 Proxy: disconnected\n`;
    }

    // Session info
    if (session) {
      const shortId = session.cascadeId.slice(0, 8);
      text += `\n💬 <b>Session:</b> <code>${shortId}</code>\n`;
      text += `🤖 Model: <code>${session.selectedModel ?? "default"}</code>\n`;
      text += `🔇 Quiet: ${session.quietMode ? "ON" : "OFF"}\n`;
      if (session.workspaceUri) {
        const wsName = session.workspaceUri.split("/").pop() ?? session.workspaceUri;
        text += `📂 Workspace: <code>${escapeHtml(wsName)}</code>\n`;
      }
      const bmarks = getBookmarks(chatId);
      if (bmarks.length > 0) {
        text += `📌 Bookmarks: ${bmarks.length}\n`;
      }
    } else {
      text += `\n⚪ Tidak ada session aktif\n`;
    }

    const keyboard = new InlineKeyboard()
      .text("💬 New Chat", "quick:new")
      .text("📋 My Chats", "quick:list")
      .row()
      .text("📖 Help", "quick:help");

    await ctx.reply(text, { parse_mode: "HTML", reply_markup: keyboard });
  });

  bot.command("restart", async (ctx) => {
    await ctx.reply("🔄 Bot restarting...");
    process.exit(0);
  });

  // ── /settings — Unified settings panel (mirrors web's SettingsPanel) ──
  bot.command("settings", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    // Gather all current settings
    const model = session?.selectedModel ?? "(default)";
    const planner = session?.plannerType ?? "conversational";
    const plannerLabel = planner === "planning" ? "📋 Plan" : "⚡ Fast";
    const quietLabel = session?.quietMode ? "🔇 ON" : "🔔 OFF";
    const wsUri = session?.workspaceUri ?? config.workspaceUri ?? "(none)";
    const shortWs = wsUri.replace(/^file:\/\//, "").split("/").pop() ?? wsUri;
    const convId = session?.cascadeId?.slice(0, 8) ?? "(none)";

    let autoApproveLabel = "❓ Unknown";
    try {
      const health = await client.getHealth();
      autoApproveLabel = health.autoApprove ? "✅ ON" : "❌ OFF";
    } catch { /* ignore */ }

    const compactLabel = session?.compactMode ? "📦 ON" : "📋 OFF";

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

    const keyboard = new InlineKeyboard()
      .text(`🤖 Model`, `set:models`)
      .text(`${plannerLabel}`, `set:planner`)
      .row()
      .text(`${quietLabel.split(" ")[0]} Quiet: ${session?.quietMode ? "ON" : "OFF"}`, `set:quiet`)
      .text(`${compactLabel.split(" ")[0]} Compact: ${session?.compactMode ? "ON" : "OFF"}`, `set:compact`)
      .row()
      .text(`🔓 Auto: ${autoApproveLabel.includes("ON") ? "ON" : "OFF"}`, `set:autoapprove`)
      .text(`🔄 Refresh`, `set:refresh`)
      .row()
      .text(`📂 Workspace`, `set:workspace`);

    await ctx.reply(text, { parse_mode: "HTML", reply_markup: keyboard });
  });

  bot.command("autoapprove", async (ctx) => {
    const arg = ctx.match?.trim().toLowerCase();
    
    if (arg !== "on" && arg !== "off") {
      try {
        const health = await client.getHealth();
        const state = health.autoApprove ? "ON ✅" : "OFF ❌";
        await ctx.reply(`💡 Gunakan: /autoapprove [on|off]\n\nStatus saat ini: <b>${state}</b>`, { parse_mode: "HTML" });
      } catch {
        await ctx.reply("💡 Gunakan: /autoapprove [on|off]");
      }
      return;
    }

    const enable = arg === "on";
    const statusMsg = await ctx.reply(`⏳ Mengubah auto-approve menjadi ${enable ? "ON" : "OFF"}...`);

    try {
      const newState = await client.setAutoApprove(enable);
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `✅ Auto-approve berhasil diubah menjadi: <b>${newState ? "ON ✅" : "OFF ❌"}</b>`,
        { parse_mode: "HTML" }
      );
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Gagal mengubah auto-approve: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

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

  bot.command("stopall", async (ctx) => {
    const statusMsg = await ctx.reply("⏳ Menghentikan semua agent yang berjalan...");

    try {
      const convs = await client.listConversations();
      const runningConvs = convs.filter(c => c.status === "CASCADE_RUN_STATUS_RUNNING");

      if (runningConvs.length === 0) {
        await ctx.api.editMessageText(
          ctx.chat.id,
          statusMsg.message_id,
          "ℹ️ Tidak ada agent yang sedang berjalan.",
        );
        return;
      }

      let stoppedCount = 0;
      for (const conv of runningConvs) {
        try {
          await client.stopConversation(conv.id);
          stoppedCount++;
        } catch (err) {
          console.warn(`Gagal stop ${conv.id}:`, err);
        }
      }

      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `✅ ${stoppedCount} agent berhasil dihentikan.`,
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

  // ── /retry — Retry/continue the last task ──
  bot.command("retry", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif. Gunakan /new atau /list.");
      return;
    }

    const { connectStreamer, cleanupWs } = await import("./utils.js");

    cleanupWs(session);
    session.streamMessageId = null;
    session.streamBuffer = "";

    connectStreamer(ctx.api, chatId, session, client, config);

    const retryMsg = ctx.match?.trim() || "please continue or retry the last task";

    try {
      await client.sendMessage(session.cascadeId, retryMsg, session.selectedModel);
      await ctx.reply(`🔄 Retry dikirim ke agent.`, { parse_mode: "HTML" });
    } catch (err) {
      await ctx.reply(
        `❌ Gagal mengirim retry: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /bookmark — Save a bot message by replying to it ──
  bot.command("bookmark", async (ctx) => {
    const chatId = ctx.chat.id;
    const replyTo = ctx.message?.reply_to_message;

    if (!replyTo || !replyTo.text) {
      await ctx.reply(
        "📌 Reply ke pesan bot dengan /bookmark untuk menyimpannya.\n\n" +
          "<i>Contoh: tekan reply pada pesan agent, lalu ketik /bookmark</i>",
        { parse_mode: "HTML" },
      );
      return;
    }

    const text = replyTo.text;
    const label = (ctx.match?.trim()) || text.slice(0, 60).replace(/\n/g, " ");
    const count = addBookmark(chatId, { label, text, createdAt: Date.now() });

    await ctx.reply(
      `📌 Bookmark #${count} tersimpan: <i>${escapeHtml(label.slice(0, 50))}${label.length > 50 ? "..." : ""}</i>`,
      { parse_mode: "HTML" },
    );
  });

  // ── /bookmarks — List all bookmarks ──
  bot.command("bookmarks", async (ctx) => {
    const chatId = ctx.chat.id;
    const arg = ctx.match?.trim();

    if (arg === "clear") {
      clearBookmarks(chatId);
      await ctx.reply("🗑 Semua bookmarks dihapus.");
      return;
    }

    const list = getBookmarks(chatId);
    if (list.length === 0) {
      await ctx.reply("📌 Belum ada bookmarks. Reply ke pesan bot dengan /bookmark untuk menyimpan.");
      return;
    }

    let text = `📌 <b>Bookmarks</b> (${list.length}):\n\n`;
    for (let i = 0; i < list.length; i++) {
      const bm = list[i];
      const time = new Date(bm.createdAt).toLocaleTimeString("id-ID", { hour: "2-digit", minute: "2-digit" });
      text += `${i + 1}. <i>${escapeHtml(bm.label.slice(0, 50))}</i> <code>(${time})</code>\n`;
    }
    text += `\n<i>Ketik /bookmarks clear untuk menghapus semua.</i>`;

    const keyboard = new InlineKeyboard();
    // Show buttons to view each bookmark
    for (let i = 0; i < Math.min(list.length, 10); i++) {
      keyboard.text(`📖 #${i + 1}`, `bm:view:${i}`).row();
    }

    await ctx.reply(text, { parse_mode: "HTML", reply_markup: keyboard });
  });

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

  // ── /clear — Start fresh conversation (clear context without /end + /new) ──
  bot.command("clear", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (session) {
      destroySession(chatId);
    }

    try {
      const result = await client.createConversation(getEffectiveWorkspace(chatId));
      createSession(chatId, result.cascadeId);
      const shortId = result.cascadeId.slice(0, 8);
      await ctx.reply(
        `🧹 Context dibersihkan.\n\n` +
          `Conversation baru: <code>${shortId}</code>\n` +
          `<i>Kirim pesan untuk mulai chat.</i>`,
        { parse_mode: "HTML" },
      );
    } catch (err) {
      await ctx.reply(
        `❌ Gagal membuat conversation baru: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /quiet — Toggle silent notifications ──
  bot.command("quiet", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif. Gunakan /new atau /list.");
      return;
    }

    session.quietMode = !session.quietMode;
    const icon = session.quietMode ? "🔇" : "🔔";
    const label = session.quietMode ? "ON" : "OFF";
    await ctx.reply(
      `${icon} Quiet mode: <b>${label}</b>\n\n` +
        (session.quietMode
          ? "<i>Notifikasi intermediate dimatikan. Kamu hanya akan diberitahu saat task selesai.</i>"
          : "<i>Semua notifikasi diaktifkan kembali.</i>"),
      { parse_mode: "HTML" },
    );
  });

  // ── /compact — Toggle compact mode (suppress individual tool step messages) ──
  bot.command("compact", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif. Gunakan /new atau /list.");
      return;
    }

    session.compactMode = !session.compactMode;
    const icon = session.compactMode ? "📦" : "📋";
    const label = session.compactMode ? "ON" : "OFF";
    await ctx.reply(
      `${icon} Compact mode: <b>${label}</b>\n\n` +
        (session.compactMode
          ? "<i>Tool steps individual dimatikan. Hanya collapsed summary yang akan ditampilkan saat task selesai.</i>"
          : "<i>Semua tool steps ditampilkan secara individual (default).</i>"),
      { parse_mode: "HTML" },
    );
  });

  // ── /pin — Pin a bot message in the chat ──
  bot.command("pin", async (ctx) => {
    const reply = ctx.message?.reply_to_message;
    if (!reply) {
      await ctx.reply(
        "📌 <b>Cara pakai:</b>\nReply ke pesan yang ingin di-pin, lalu ketik /pin\n\n" +
          "<i>Ini akan pin pesan tersebut di chat ini.</i>",
        { parse_mode: "HTML" },
      );
      return;
    }

    try {
      await ctx.api.pinChatMessage(ctx.chat.id, reply.message_id);
      await ctx.reply("📌 Pesan berhasil di-pin.");
    } catch (err) {
      await ctx.reply(
        `❌ Gagal pin: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  // ── /export — Export conversation history to markdown file ──
  bot.command("export", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif. Gunakan /new atau /list.");
      return;
    }

    const statusMsg = await ctx.reply("⏳ Mengekspor conversation...");

    try {
      const steps = await client.getSteps(session.cascadeId, 0, 1000);
      if (!steps || steps.length === 0) {
        await ctx.api.editMessageText(chatId, statusMsg.message_id, "📭 Tidak ada steps untuk diexport.");
        return;
      }

      // Build markdown content
      const shortId = session.cascadeId.slice(0, 8);
      let md = `# Conversation ${shortId}\n\n`;
      md += `**Exported:** ${new Date().toISOString()}\n`;
      md += `**Steps:** ${steps.length}\n\n---\n\n`;

      for (const step of steps) {
        const status = (step.status as string) ?? "";
        if (status !== "CORTEX_STEP_STATUS_DONE") continue;

        // Planner response
        const resp = step.plannerResponse as Record<string, unknown> | undefined;
        if (resp) {
          const text = (resp.modifiedResponse as string) ?? (resp.response as string) ?? "";
          if (text) {
            md += `## 💬 Agent Response\n\n${text}\n\n---\n\n`;
          }
        }

        // Command
        const cmd = step.runCommand as Record<string, unknown> | undefined;
        if (cmd) {
          const cmdLine = (cmd.commandLine as string) ?? "";
          const output = (cmd.output as string) ?? "";
          md += `## ⚡ Command\n\n\`\`\`\n${cmdLine}\n\`\`\`\n\n`;
          if (output) md += `**Output:**\n\`\`\`\n${output.slice(0, 2000)}\n\`\`\`\n\n`;
          md += `---\n\n`;
        }

        // Code edit
        const codeAction = step.codeAction as Record<string, unknown> | undefined;
        if (codeAction) {
          const file = (codeAction.filePath as string) ?? "unknown";
          const desc = (codeAction.description as string) ?? "";
          md += `## 📝 Edit: ${file}\n\n${desc}\n\n---\n\n`;
        }
      }

      // Write to temp file and send as document
      const tmpPath = path.join(os.tmpdir(), `porta-export-${shortId}.md`);
      fs.writeFileSync(tmpPath, md, "utf-8");
      
      await ctx.api.deleteMessage(chatId, statusMsg.message_id).catch(() => {});
      await ctx.replyWithDocument(new InputFile(tmpPath, `conversation-${shortId}.md`), {
        caption: `📄 Export conversation <code>${shortId}</code> (${steps.length} steps)`,
        parse_mode: "HTML",
      });

      // Clean up temp file
      fs.unlinkSync(tmpPath);
    } catch (err) {
      await ctx.api.editMessageText(
        chatId, statusMsg.message_id,
        `❌ Export gagal: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  bot.command("info", async (ctx) => {
    const chatId = ctx.chat.id;
    const session = getSession(chatId);

    if (!session) {
      await ctx.reply("ℹ️ Tidak ada session aktif. Gunakan /new atau /list.");
      return;
    }

    const statusMsg = await ctx.reply("⏳ Memuat info conversation...");

    try {
      const convs = await client.listConversations();
      const current = convs.find(c => c.id === session.cascadeId);

      let text = "ℹ️ <b>Conversation Info</b>\n\n";
      text += `<b>ID:</b> <code>${session.cascadeId.slice(0, 8)}</code>\n`;

      if (current) {
        text += `<b>Summary:</b> ${escapeHtml(current.summary)}\n`;
        text += `<b>Status:</b> ${current.status === "CASCADE_RUN_STATUS_RUNNING" ? "🟢 Running" : "⚪ Stopped"}\n`;
        text += `<b>Steps:</b> ${current.stepCount}\n`;
        if (current.lastModifiedTime) {
          text += `<b>Last updated:</b> ${new Date(current.lastModifiedTime).toLocaleString()}\n`;
        }
      }

      text += `<b>Model:</b> ${escapeHtml(session.selectedModel ?? "Default")}\n`;

      const wsUri = session.workspaceUri ?? config.workspaceUri;
      if (wsUri) {
        const shortWs = wsUri.replace(/^file:\/\//, "").replace(os.homedir(), "~");
        text += `<b>Workspace:</b> <code>${escapeHtml(shortWs)}</code>\n`;
      }

      await ctx.api.editMessageText(chatId, statusMsg.message_id, text, {
        parse_mode: "HTML",
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

  bot.command("status", async (ctx) => {
    const chatId = ctx.chat.id;

    try {
      const health = await client.getHealth();
      const proxy = health.proxy as { 
        port?: number; 
        uptime?: number;
        memory?: { rss: number; heapTotal: number; heapUsed: number };
        loadavg?: number[];
      } | undefined;
      const lsList = health.languageServers as Record<string, unknown>[] | undefined;
      const autoApprove = health.autoApprove as boolean | undefined;

      let text = "📊 <b>Porta Status</b>\n\n";
      text += `<b>Proxy:</b> ✅ Online (port ${proxy?.port ?? "?"})\n`;
      text += `<b>Uptime:</b> ${formatUptime(proxy?.uptime ?? 0)}\n`;
      if (proxy?.memory) {
        const rssMb = (proxy.memory.rss / 1024 / 1024).toFixed(1);
        const heapMb = (proxy.memory.heapUsed / 1024 / 1024).toFixed(1);
        text += `<b>Memory:</b> ${rssMb}MB (Heap: ${heapMb}MB)\n`;
      }
      if (proxy?.loadavg) {
        const load = proxy.loadavg.map(v => v.toFixed(2)).join(", ");
        text += `<b>Load:</b> ${load}\n`;
      }
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
      let text = "🤖 <b>Model Tersedia</b>\n\n<i>Tap untuk memilih:</i>";

      const keyboard = new InlineKeyboard();
      for (const m of models) {
        const isSelected = session?.selectedModel === m.name;
        const marker = isSelected ? " 👈" : "";
        keyboard.text(`${m.displayName}${marker}`, `model:${m.name}`).row();
      }

      await ctx.api.editMessageText(ctx.chat.id, statusMsg.message_id, text, {
        parse_mode: "HTML",
        reply_markup: keyboard,
      });
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  bot.command("model", async (ctx) => {
    const chatId = ctx.chat.id;
    const modelArg = ctx.match?.trim();
    let session = getSession(chatId);

    if (!modelArg) {
      if (session) {
        session.selectedModel = undefined;
      }
      await ctx.reply("🔄 Model di-reset ke default.");
      return;
    }

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
    } catch { }

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

  bot.command("ps", async (ctx) => {
    const statusMsg = await ctx.reply("⏳ Mengambil daftar proses...");
    try {
      // Use OS-agnostic sort approach via awk, or just rely on ps aux
      const cmdStr = `ps aux | awk 'NR==1; NR>1{print $0 | "sort -nrk 3"}' | head -n 15`;
      const result = await client.runShellCommand(cmdStr);
      let output = "";
      if (result.stdout) output += result.stdout;
      if (result.stderr) output += (output ? "\n" : "") + result.stderr;
      
      await ctx.api.deleteMessage(ctx.chat.id, statusMsg.message_id).catch(() => {});
      
      if (!output) {
        await ctx.reply("📭 Tidak ada proses ditemukan.");
        return;
      }

      await sendTextAsChunksOrFile(ctx, output, "ps_output.txt");
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });

  bot.command("kill", async (ctx) => {
    const pid = ctx.match?.trim();
    if (!pid || !/^\d+$/.test(pid)) {
      await ctx.reply("💡 Gunakan: /kill <code>pid</code>\n\nContoh: /kill 12345", { parse_mode: "HTML" });
      return;
    }

    const statusMsg = await ctx.reply(`⏳ Menghentikan proses ${pid}...`);
    try {
      const result = await client.runShellCommand(`kill -9 ${pid}`);
      let output = "";
      if (result.stdout) output += result.stdout;
      if (result.stderr) output += (output ? "\n" : "") + result.stderr;

      if (result.exitCode === 0) {
        await ctx.api.editMessageText(ctx.chat.id, statusMsg.message_id, `✅ Proses ${pid} berhasil dihentikan.`);
      } else {
        await ctx.api.editMessageText(ctx.chat.id, statusMsg.message_id, `❌ Gagal menghentikan proses ${pid}:\n<pre>${escapeHtml(output)}</pre>`, { parse_mode: "HTML" });
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

  bot.command("search", async (ctx) => {
    const query = ctx.match?.trim();
    if (!query) {
      await ctx.reply("💡 Gunakan: /search <code>text</code>\n\nContoh: /search config", { parse_mode: "HTML" });
      return;
    }

    const statusMsg = await ctx.reply(`⏳ Mencari <code>${escapeHtml(query)}</code> di workspace...`, { parse_mode: "HTML" });
    try {
      const wsUri = getEffectiveWorkspace(ctx.chat.id) || process.cwd();
      const rootDir = wsUri.replace(/^file:\/\//, "");
      const { exec } = await import("node:child_process");
      const util = await import("node:util");
      const execPromise = util.promisify(exec);

      // Using grep with recursive, line number, ignore case, and binary files without match
      const { stdout, stderr } = await execPromise(`grep -rinI --exclude-dir=node_modules --exclude-dir=.git "${query.replace(/"/g, '\\"')}" . | head -n 50`, { cwd: rootDir });
      
      await ctx.api.deleteMessage(ctx.chat.id, statusMsg.message_id).catch(() => {});
      
      const output = stdout || stderr;
      if (!output) {
        await ctx.reply(`📭 Tidak ditemukan: <code>${escapeHtml(query)}</code>`, { parse_mode: "HTML" });
        return;
      }

      await sendTextAsChunksOrFile(ctx, output, "search_results.txt");
    } catch (err) {
      // exec throws if grep finds nothing (exit code 1)
      if ((err as any).code === 1) {
        await ctx.api.editMessageText(ctx.chat.id, statusMsg.message_id, `📭 Tidak ditemukan: <code>${escapeHtml(query)}</code>`, { parse_mode: "HTML" });
      } else {
        await ctx.api.editMessageText(
          ctx.chat.id,
          statusMsg.message_id,
          `❌ Error: ${escapeHtml((err as Error).message)}`,
          { parse_mode: "HTML" },
        );
      }
    }
  });

  bot.command("find", async (ctx) => {
    const query = ctx.match?.trim();
    if (!query) {
      await ctx.reply("💡 Gunakan: /find <code>filename</code>\n\nContoh: /find *.ts", { parse_mode: "HTML" });
      return;
    }

    const statusMsg = await ctx.reply(`⏳ Mencari file <code>${escapeHtml(query)}</code>...`, { parse_mode: "HTML" });
    try {
      const wsUri = getEffectiveWorkspace(ctx.chat.id) || process.cwd();
      const rootDir = wsUri.replace(/^file:\/\//, "");
      const { exec } = await import("node:child_process");
      const util = await import("node:util");
      const execPromise = util.promisify(exec);

      const { stdout, stderr } = await execPromise(`find . -type f -name "${query.replace(/"/g, '\\"')}" -not -path "*/node_modules/*" -not -path "*/.git/*" | head -n 50`, { cwd: rootDir });
      
      await ctx.api.deleteMessage(ctx.chat.id, statusMsg.message_id).catch(() => {});
      
      const output = stdout || stderr;
      if (!output) {
        await ctx.reply(`📭 File tidak ditemukan: <code>${escapeHtml(query)}</code>`, { parse_mode: "HTML" });
        return;
      }

      await sendTextAsChunksOrFile(ctx, output, "find_results.txt");
    } catch (err) {
      await ctx.api.editMessageText(
        ctx.chat.id,
        statusMsg.message_id,
        `❌ Error: ${escapeHtml((err as Error).message)}`,
        { parse_mode: "HTML" },
      );
    }
  });
}
