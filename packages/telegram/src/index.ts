/**
 * Porta Telegram Bot — Entry Point
 *
 * Starts a grammY bot that bridges Telegram messages to Antigravity
 * via the Porta proxy. Uses long polling for Telegram updates.
 *
 * Security: Only users listed in TELEGRAM_ALLOWED_USERS can interact.
 */

import { Bot } from "grammy";
import { loadConfig } from "./config.js";
import { authGuard } from "./guard.js";
import { registerHandlers } from "./handlers.js";
import { startSessionCleanup, stopSessionCleanup } from "./session.js";

const config = loadConfig();

console.log("🤖 Porta Telegram Bot");
console.log(`   Proxy: ${config.proxyBaseUrl}`);
console.log(`   Allowed users: ${[...config.allowedUsers].join(", ")}`);
if (config.allowedGroups.size > 0) {
  console.log(`   Allowed groups: ${[...config.allowedGroups].join(", ")}`);
}
if (config.workspaceUri) {
  console.log(`   Default workspace: ${config.workspaceUri}`);
}
console.log(`   Auth: ${config.authToken ? "🔒 Token configured" : "🔓 No token"}`);

const bot = new Bot(config.botToken);

// Error handler — log but don't crash
bot.catch((err) => {
  console.error("[bot] Unhandled error:", err.message);
});

// Security middleware — must be first
bot.use(authGuard(config));

// Register all command and message handlers
registerHandlers(bot, config);

// Start session cleanup
startSessionCleanup();

// Graceful shutdown
function shutdown(signal: string): void {
  console.log(`\n${signal} received — shutting down...`);
  stopSessionCleanup();
  bot.stop();
  process.exit(0);
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

// Start the bot
console.log("⏳ Connecting to Telegram...");
bot.start({
  onStart: (info) => {
    console.log(`✅ Porta Telegram Bot is running as @${info.username}`);
  },
});
