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
 * Includes a stale-conversation timeout to prevent indefinite "typing..." state.
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

  const wsConn = client.connectWebSocket(
    session.cascadeId,
    (msg) => {
      void streamer.onMessage(msg);
    },
  );

  session.wsConnection = wsConn;

  // Stale-conversation timeout: detect when the agent isn't producing new content.
  // For conversations with history (stuck RUNNING), use 30s.
  // For fresh conversations (no history), use 2min — the model may need time to think.
  const staleTimeout = setTimeout(() => {
    if (!streamer.hasNewContent && !streamer.hasPermissionError) {
      const isFresh = session.historicalStepCount === 0;
      if (isFresh) {
        // Fresh conversation still thinking — don't kill it, just log
        console.log(`[streamer] fresh conversation still thinking: ${session.cascadeId.slice(0, 8)}`);
        return;
      }
      console.log(`[streamer] stale timeout: no new content for ${session.cascadeId.slice(0, 8)}`);
      void api.sendMessage(
        chatId,
        "⚠️ Agent tidak merespon — conversation mungkin stuck dari task sebelumnya.\n\n" +
          "<i>Kirim /stop lalu kirim ulang pesan, atau /new untuk conversation baru.</i>",
        { parse_mode: "HTML" },
      ).catch(() => {});
      // Force-close the WS to stop the typing indicator
      cleanupWs(session);
    }
  }, 30_000);

  wsConn.ws.on("close", () => {
    clearTimeout(staleTimeout);
    if (session.wsConnection === wsConn) {
      session.wsConnection = null;
    }
    void streamer.finalize().then(() => {
      const elapsed = Date.now() - startTime;

      if (!streamer.hasNewContent && elapsed < 10_000) {
        // WS closed quickly without any new content — agent didn't start
        void api.sendMessage(
          chatId,
          "⚠️ Agent tidak merespon. Conversation mungkin sedang aktif di Antigravity desktop.\n\n" +
            "<i>Coba /new untuk buat conversation baru.</i>",
          { parse_mode: "HTML" },
        ).catch(() => {});
      }

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
