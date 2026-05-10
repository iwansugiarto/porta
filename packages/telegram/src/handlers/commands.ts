import { InlineKeyboard, InputFile } from "grammy";
import type { Bot } from "grammy";
import type { TelegramConfig } from "../config.js";
import type { PortaClient } from "../porta-client.js";
import { getSession, createSession, switchSession, destroySession } from "../session.js";
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

  bot.command("start", async (ctx) => {
    await ctx.reply(
      "🚀 <b>Porta Telegram Bridge</b>\n\n" +
        "Akses Antigravity langsung dari Telegram.\n\n" +
        "<b>💬 Chat:</b>\n" +
        "/new — Buat conversation baru\n" +
        "/list — Daftar conversations\n" +
        "/use <code>id</code> — Switch ke conversation\n" +
        "/history — History conversation saat ini\n" +
        "/latest — Response terakhir dari agent\n" +
        "/info — Info conversation saat ini\n" +
        "/stop — Stop agent yang sedang berjalan\n" +
        "/stopall — Stop semua agent yang berjalan\n" +
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
        "/cat <code>file</code> — Baca isi file teks\n" +
        "/diff — Lihat perubahan file (git diff)\n" +
        "/logs <code>[svc] [n]</code> — Lihat log (proxy/web/telegram)\n" +
        "/status — Status proxy &amp; LS\n" +
        "/autoapprove <code>[on|off]</code> — Toggle auto-approve\n" +
        "/restart — Restart bot\n" +
        "/help — Tampilkan bantuan\n\n" +
        "<i>Kirim pesan teks, foto, atau dokumen untuk chat dengan Antigravity.</i>",
      { parse_mode: "HTML" },
    );
  });

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
        "<b>Remote Shell & Logs:</b>\n" +
        "• /cmd ls -la — jalankan command di server\n" +
        "• /cat file.txt — baca file teks\n" +
        "• /diff — lihat perubahan file (git diff)\n" +
        "• /logs proxy 50 — tail 50 baris log\n" +
        "• Beberapa command berbahaya diblokir otomatis\n\n" +
        "<b>Approval & Settings:</b>\n" +
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

      const PAGE_SIZE = 10;
      const maxPage = Math.ceil(conversations.length / PAGE_SIZE);
      const currentPage = Math.max(1, Math.min(page, maxPage));
      
      const startIndex = (currentPage - 1) * PAGE_SIZE;
      const items = conversations.slice(startIndex, startIndex + PAGE_SIZE);
      const currentSession = getSession(chatId);

      let text = `📋 <b>Conversations</b> (Halaman ${currentPage}/${maxPage})\n\n<i>Tap untuk switch:</i>`;
      
      const keyboard = new InlineKeyboard();
      for (const conv of items) {
        const shortId = conv.id.slice(0, 8);
        const isActive = currentSession?.cascadeId === conv.id;
        const statusIcon = conv.status === "CASCADE_RUN_STATUS_RUNNING" ? "🟢" : "⚪";
        const marker = isActive ? " 👈" : "";
        const summary = conv.summary.length > 35
          ? conv.summary.slice(0, 32) + "..."
          : conv.summary;

        keyboard.text(`${statusIcon} ${shortId} ${summary}${marker}`, `use:${conv.id}`).row();
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

      const chunks = splitMessage(stdout || stderr);
      for (const chunk of chunks) {
        await withRetry(() =>
          ctx.reply(`<pre>${escapeHtml(chunk.slice(0, 3800))}</pre>`, { parse_mode: "HTML" }),
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

      const chunks = splitMessage(stdout);
      for (const chunk of chunks) {
        await withRetry(() =>
          ctx.reply(`<pre>${escapeHtml(chunk.slice(0, 3800))}</pre>`, { parse_mode: "HTML" }),
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
      
      const chunks = splitMessage(content);
      const ext = path.extname(fullPath).slice(1);
      
      for (const chunk of chunks) {
        await withRetry(() =>
          ctx.reply(`<pre><code class="language-${ext}">${escapeHtml(chunk.slice(0, 3800))}</code></pre>`, { parse_mode: "HTML" }),
        );
      }
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
      for (const chunk of chunks) {
        await withRetry(() =>
          ctx.reply(chunk, { parse_mode: "HTML" }),
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

  bot.command("restart", async (ctx) => {
    await ctx.reply("🔄 Bot restarting...");
    process.exit(0);
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
}
