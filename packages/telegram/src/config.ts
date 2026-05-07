/**
 * Configuration loader for the Telegram bot.
 *
 * Reads environment variables (loaded via Node --env-file) and validates
 * required settings. Fails fast at startup if critical config is missing.
 */

export interface TelegramConfig {
  /** Telegram Bot API token from @BotFather. */
  botToken: string;
  /** Set of allowed Telegram user IDs (whitelist). */
  allowedUsers: Set<number>;
  /** Set of allowed Telegram group/supergroup IDs. Empty = no group access. */
  allowedGroups: Set<number>;
  /** Default workspace URI for new conversations. */
  workspaceUri: string | undefined;
  /** Porta proxy base URL (e.g. http://127.0.0.1:3170). */
  proxyBaseUrl: string;
  /** Porta auth token for API access. */
  authToken: string | undefined;
  /** Path to the Antigravity CLI binary (for launching workspaces). */
  antigravityCliPath: string;
}

/** Parse a comma-separated list of numeric IDs into a Set. */
function parseIdList(raw: string | undefined): Set<number> {
  if (!raw || raw.trim() === "") return new Set();
  return new Set(
    raw
      .split(",")
      .map((s) => s.trim())
      .filter((s) => s.length > 0)
      .map((s) => {
        const n = Number(s);
        if (!Number.isFinite(n) || n === 0) {
          throw new Error(`Invalid Telegram ID: "${s}" — must be a non-zero integer`);
        }
        return Math.trunc(n);
      }),
  );
}

export function loadConfig(
  env: NodeJS.ProcessEnv = process.env,
): TelegramConfig {
  const botToken = env.TELEGRAM_BOT_TOKEN?.trim();
  if (!botToken) {
    console.error("❌ TELEGRAM_BOT_TOKEN is required. Get one from @BotFather.");
    process.exit(1);
  }

  const allowedUsers = parseIdList(env.TELEGRAM_ALLOWED_USERS);
  if (allowedUsers.size === 0) {
    console.error(
      "❌ TELEGRAM_ALLOWED_USERS is required — set to your Telegram user ID(s).\n" +
        "   Send a message to @userinfobot on Telegram to get your ID.",
    );
    process.exit(1);
  }

  const allowedGroups = parseIdList(env.TELEGRAM_ALLOWED_GROUPS);

  const host = env.PORTA_HOST ?? "127.0.0.1";
  const port = env.PORTA_PORT ?? "3170";
  const proxyBaseUrl = `http://${host}:${port}`;

  return {
    botToken,
    allowedUsers,
    allowedGroups,
    workspaceUri: env.TELEGRAM_WORKSPACE_URI?.trim() || undefined,
    proxyBaseUrl,
    authToken: env.PORTA_AUTH_TOKEN?.trim() || undefined,
    antigravityCliPath:
      env.ANTIGRAVITY_CLI_PATH?.trim() ||
      "/Applications/Antigravity.app/Contents/Resources/app/bin/antigravity",
  };
}
