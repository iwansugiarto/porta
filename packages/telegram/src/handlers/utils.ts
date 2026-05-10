import type { Api } from "grammy";
import type { ChatSession } from "../session.js";
import type { TelegramConfig } from "../config.js";
import { PortaClient } from "../porta-client.js";
import { ResponseStreamer } from "../streamer.js";

/** Helper to close a session's WebSocket without TypeScript narrowing issues. */
export function cleanupWs(session: ChatSession): void {
  if (session.wsConnection) {
    session.wsConnection.close();
    session.wsConnection = null;
  }
}

/** Format seconds into a human-readable uptime string. */
export function formatUptime(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);

  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

/**
 * Connect a WebSocket streamer for a conversation session.
 * Sends a completion notification when the agent finishes.
 */
export function connectStreamer(
  api: Api,
  chatId: number,
  session: ChatSession,
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
      }
      // Note: completion notification is handled by streamer.finalize() progress message

      // Send hint if agent hit permission errors
      if (streamer.hasPermissionError) {
        void api.sendMessage(
          chatId,
          "💡 <b>Tip:</b> Agent terkena error permission saat menjalankan command.\n" +
            "Gunakan <code>/cmd &lt;command&gt;</code> untuk bypass — " +
            "command dijalankan langsung di host, bukan melalui agent.\n\n" +
            "Contoh: <code>/cmd git status</code>",
          { parse_mode: "HTML" },
        ).catch(() => {});
      }
    });
  });
}
