/**
 * Chat ↔ Conversation session manager.
 *
 * Maps Telegram chat IDs to active Porta conversation sessions.
 * Each chat can have one active conversation at a time.
 * Sessions auto-expire after a period of inactivity.
 */

import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import type { WSConnection } from "./porta-client.js";

const STATE_FILE = path.join(os.homedir(), ".gemini", "antigravity", "telegram_state.json");

interface PersistedState {
  sessions: Record<string, Partial<ChatSession>>;
  bookmarks: Record<string, Bookmark[]>;
}

export function loadState(): void {
  try {
    if (fs.existsSync(STATE_FILE)) {
      const data: PersistedState = JSON.parse(fs.readFileSync(STATE_FILE, "utf-8"));
      if (data.sessions) {
        for (const [chatIdStr, sess] of Object.entries(data.sessions)) {
          const chatId = parseInt(chatIdStr, 10);
          sessions.set(chatId, {
            cascadeId: sess.cascadeId ?? "",
            wsConnection: null,
            streamMessageId: null,
            streamBuffer: "",
            flushTimer: null,
            selectedModel: sess.selectedModel,
            workspaceUri: sess.workspaceUri,
            lastActivity: Date.now(),
            renderedStepOffsets: new Set(),
            historicalStepCount: 0,
            cachedArtifacts: [],
            quietMode: sess.quietMode ?? false,
            isCancelled: false,
            plannerType: sess.plannerType ?? "conversational",
            compactMode: sess.compactMode ?? false,
          });
        }
      }
      if (data.bookmarks) {
        for (const [chatIdStr, bms] of Object.entries(data.bookmarks)) {
          bookmarks.set(parseInt(chatIdStr, 10), bms);
        }
      }
    }
  } catch (err) {
    console.error("Failed to load telegram state:", err);
  }
}

export function saveState(): void {
  try {
    const state: PersistedState = { sessions: {}, bookmarks: {} };
    for (const [chatId, sess] of sessions.entries()) {
      state.sessions[chatId.toString()] = {
        cascadeId: sess.cascadeId,
        selectedModel: sess.selectedModel,
        workspaceUri: sess.workspaceUri,
        quietMode: sess.quietMode,
        plannerType: sess.plannerType,
        compactMode: sess.compactMode,
      };
    }
    for (const [chatId, bms] of bookmarks.entries()) {
      state.bookmarks[chatId.toString()] = bms;
    }
    const dir = path.dirname(STATE_FILE);
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2), "utf-8");
  } catch (err) {
    console.error("Failed to save telegram state:", err);
  }
}

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
  /** Per-session workspace override (e.g. "file:///path/to/project"). */
  workspaceUri: string | undefined;
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
  /**
   * Cached artifacts for the current session to power inline keyboards.
   */
  cachedArtifacts: { name: string; path: string }[];
  /** Quiet mode — suppress intermediate notifications, only notify on completion. */
  quietMode: boolean;
  /** Whether the current task was cancelled by the user. */
  isCancelled: boolean;
  /** Planner type: "conversational" (fast) or "planning" (multi-step). Mirrors web's PlannerTypeSelector. */
  plannerType: "conversational" | "planning";
  /** Compact mode — suppress individual tool step messages, show only collapsed summary. */
  compactMode: boolean;
}

/** In-memory session store: telegramChatId → ChatSession */
const sessions = new Map<number, ChatSession>();

/** Bookmark entry saved by the user. */
export interface Bookmark {
  /** Short label / first line of text. */
  label: string;
  /** Full text content. */
  text: string;
  /** Timestamp when bookmarked. */
  createdAt: number;
}

/** Per-chat bookmark store (persists across session switches). */
const bookmarks = new Map<number, Bookmark[]>();

export function getBookmarks(chatId: number): Bookmark[] {
  return bookmarks.get(chatId) ?? [];
}

export function addBookmark(chatId: number, bookmark: Bookmark): number {
  let list = bookmarks.get(chatId);
  if (!list) {
    list = [];
    bookmarks.set(chatId, list);
  }
  list.push(bookmark);
  saveState();
  return list.length;
}

export function clearBookmarks(chatId: number): void {
  bookmarks.delete(chatId);
  saveState();
}

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
    workspaceUri: undefined,
    lastActivity: Date.now(),
    renderedStepOffsets: new Set(),
    historicalStepCount: 0,
    cachedArtifacts: [],
    quietMode: false,
    isCancelled: false,
    plannerType: "conversational",
    compactMode: false,
  };

  sessions.set(chatId, session);
  saveState();
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
  saveState();
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
