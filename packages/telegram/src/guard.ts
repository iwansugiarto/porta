/**
 * Authentication guard middleware for grammY.
 *
 * Enforces a whitelist of Telegram user IDs and group IDs.
 * Unauthorized users are blocked with a denial message (private chats)
 * or silently ignored (unknown groups).
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
        return;
      }
      return next();
    }

    // Other chat types (channel, etc.) — silently ignore
  };
}
