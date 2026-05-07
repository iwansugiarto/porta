/**
 * Authentication guard middleware for grammY.
 *
 * Enforces a whitelist of Telegram user IDs and group IDs.
 * Unauthorized users are blocked with a denial message (private chats)
 * or silently ignored (unknown groups).
 *
 * Sends an alert to all authorized users when an unauthorized access
 * attempt is detected (inspired by antigravity-telegram-suite).
 */

import type { Context, NextFunction } from "grammy";
import type { TelegramConfig } from "./config.js";

/**
 * Creates a grammY middleware that rejects unauthorized users.
 *
 * Security model:
 *   - Private chats: user ID must be in allowedUsers.
 *   - Group/supergroup: group ID must be in allowedGroups AND user must be in allowedUsers.
 *   - Channels and other chat types: silently ignored.
 */
export function authGuard(config: TelegramConfig) {
  return async (ctx: Context, next: NextFunction): Promise<void> => {
    const userId = ctx.from?.id;
    const chatType = ctx.chat?.type;
    const chatId = ctx.chat?.id;

    // No user info — skip (e.g. channel posts, service messages)
    if (!userId || !chatType || !chatId) return;

    // Private chat: check user whitelist
    if (chatType === "private") {
      if (!config.allowedUsers.has(userId)) {
        console.warn(
          `[guard] ⛔ Denied private access: user=${userId} (${ctx.from?.username ?? "unknown"})`,
        );
        await ctx.reply(
          "⛔ *Access Denied*\n\nAnda tidak terdaftar untuk menggunakan bot ini\\.",
          { parse_mode: "MarkdownV2" },
        );

        // Alert all authorized users
        await sendUnauthorizedAlert(ctx, config);
        return;
      }
      return next();
    }

    // Group / supergroup: check group whitelist first, then user whitelist
    if (chatType === "group" || chatType === "supergroup") {
      if (config.allowedGroups.size > 0 && !config.allowedGroups.has(chatId)) {
        // Silently ignore messages from unknown groups
        return;
      }

      if (!config.allowedUsers.has(userId)) {
        console.warn(
          `[guard] ⛔ Denied group access: user=${userId} group=${chatId}`,
        );
        // Brief denial in groups to avoid spam
        await ctx.reply("⛔ Akses ditolak.", {
          reply_parameters: { message_id: ctx.msg?.message_id ?? 0 },
        });

        // Alert all authorized users
        await sendUnauthorizedAlert(ctx, config);
        return;
      }
      return next();
    }

    // Other chat types (channel, etc.) — silently ignore
  };
}

/**
 * Send an alert to all authorized users about an unauthorized access attempt.
 */
async function sendUnauthorizedAlert(
  ctx: Context,
  config: TelegramConfig,
): Promise<void> {
  const from = ctx.from;
  if (!from) return;

  const username = from.username ? `@${from.username}` : "unknown";
  const fullName = `${from.first_name ?? ""} ${from.last_name ?? ""}`.trim() || "Unknown";

  let actionDetail = `Type: ${ctx.chat?.type ?? "unknown"}`;
  if (ctx.message && "text" in ctx.message && ctx.message.text) {
    actionDetail = `Message: "${ctx.message.text.slice(0, 100)}"`;
  } else if (ctx.callbackQuery?.data) {
    actionDetail = `Button: ${ctx.callbackQuery.data}`;
  }

  const alertMsg =
    `⚠️ <b>Unauthorized Access Attempt</b>\n\n` +
    `👤 <b>Name:</b> ${escapeHtmlSimple(fullName)}\n` +
    `🔖 <b>Username:</b> ${escapeHtmlSimple(username)}\n` +
    `🆔 <b>ID:</b> ${from.id}\n` +
    `💬 ${escapeHtmlSimple(actionDetail)}`;

  for (const authorizedUserId of config.allowedUsers) {
    try {
      await ctx.api.sendMessage(authorizedUserId, alertMsg, {
        parse_mode: "HTML",
      });
    } catch {
      // Ignore errors (e.g. if user hasn't started the bot)
    }
  }
}

/** Minimal HTML escape for alert messages. */
function escapeHtmlSimple(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
