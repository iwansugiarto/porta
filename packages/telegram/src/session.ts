/**
 * Chat ↔ Conversation session manager.
 *
 * Maps Telegram chat IDs to active Porta conversation sessions.
 * Each chat can have one active conversation at a time.
 * Sessions auto-expire after a period of inactivity.
 */

import type { WSConnection } from "./porta-client.js";

/** How long a session stays alive without activity (ms). */
const SESSION_TTL_MS = 2 * 60 * 60 * 1000; // 2 hours

/** Cleanup interval (ms). */
const CLEANUP_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes

export interface ChatSession {
  /** Porta cascade (conversation) ID. */
  cascadeId: string;
  /** Active WebSocket connection for streaming. */
  wsConnection: WSConnection | null;
  /** Telegram message ID currently being edited for streaming. */
  streamMessageId: number | null;
  /** Accumulated text for the current streaming message. */
  streamBuffer: string;
  /** Timer for batching stream updates. */
  flushTimer: ReturnType<typeof setTimeout> | null;
  /** Selected model for this chat (e.g. "gemini-2.5-pro"). */
  selectedModel: string | undefined;
  /** Last activity timestamp. */
  lastActivity: number;
  /**
   * Step offsets that have already been rendered to Telegram.
   * Persists across ResponseStreamer instances to prevent re-sending
   * historical steps when the WS replays the full conversation.
   */
  renderedStepOffsets: Set<number>;
  /**
   * The step count at the time the current user message was sent.
   * Steps with offset < this value are historical and should be skipped.
   */
  historicalStepCount: number;
}

/** In-memory session store: telegramChatId → ChatSession */
const sessions = new Map<number, ChatSession>();

/** Cleanup timer reference. */
let cleanupTimer: ReturnType<typeof setInterval> | null = null;

/** Get an existing session for a chat. */
export function getSession(chatId: number): ChatSession | undefined {
  const session = sessions.get(chatId);
  if (session) {
    session.lastActivity = Date.now();
  }
  return session;
}

/** Create a new session for a chat, replacing any existing one. */
export function createSession(
  chatId: number,
  cascadeId: string,
): ChatSession {
  // Close existing session first
  destroySession(chatId);

  const session: ChatSession = {
    cascadeId,
    wsConnection: null,
    streamMessageId: null,
    streamBuffer: "",
    flushTimer: null,
    selectedModel: undefined,
    lastActivity: Date.now(),
    renderedStepOffsets: new Set(),
    historicalStepCount: 0,
  };

  sessions.set(chatId, session);
  return session;
}

/** Switch a chat to a different conversation. */
export function switchSession(
  chatId: number,
  cascadeId: string,
): ChatSession {
  return createSession(chatId, cascadeId);
}

/** Close and remove a session. */
export function destroySession(chatId: number): void {
  const session = sessions.get(chatId);
  if (!session) return;

  if (session.flushTimer) {
    clearTimeout(session.flushTimer);
    session.flushTimer = null;
  }
  if (session.wsConnection) {
    session.wsConnection.close();
    session.wsConnection = null;
  }
  sessions.delete(chatId);
}

/** Get all active sessions (for diagnostics). */
export function getAllSessions(): Map<number, ChatSession> {
  return sessions;
}

/** Clean up expired sessions. */
function cleanupExpired(): void {
  const now = Date.now();
  for (const [chatId, session] of sessions) {
    if (now - session.lastActivity > SESSION_TTL_MS) {
      console.log(`[session] Expired session for chat ${chatId}`);
      destroySession(chatId);
    }
  }
}

/** Start the periodic cleanup timer. */
export function startSessionCleanup(): void {
  if (cleanupTimer) return;
  cleanupTimer = setInterval(cleanupExpired, CLEANUP_INTERVAL_MS);
  cleanupTimer.unref?.();
}

/** Stop the periodic cleanup timer. */
export function stopSessionCleanup(): void {
  if (cleanupTimer) {
    clearInterval(cleanupTimer);
    cleanupTimer = null;
  }
}
