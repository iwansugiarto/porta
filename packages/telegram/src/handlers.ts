import type { Bot } from "grammy";
import type { TelegramConfig } from "./config.js";
import { PortaClient } from "./porta-client.js";

import { registerCommands } from "./handlers/commands.js";
import { registerMessageHandlers } from "./handlers/messages.js";
import { registerFileHandlers } from "./handlers/file.js";
import { registerWorkspaceHandlers } from "./handlers/workspace.js";
import { registerCallbackHandlers } from "./handlers/callbacks.js";

export function registerHandlers(bot: Bot, config: TelegramConfig): void {
  const client = new PortaClient(config.proxyBaseUrl, config.authToken);

  registerCommands(bot, client, config);
  registerWorkspaceHandlers(bot, client, config);
  registerFileHandlers(bot, config);
  registerCallbackHandlers(bot, client, config);
  registerMessageHandlers(bot, client, config);
}
