/**
 * Share link management module.
 *
 * Provides public, read-only access to a specific workspace via
 * unique share tokens protected by a PIN (bcrypt-hashed).
 *
 * Share state is persisted to a JSON file alongside the .env,
 * and share sessions are stored in-memory with TTL expiry.
 */

import { randomBytes } from "node:crypto";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { hashSync, compareSync } from "bcryptjs";

// ── Types ──

export interface ShareLink {
  /** Unique share token (URL-safe random string). */
  shareToken: string;
  /** The workspace URI this share is scoped to. */
  workspaceUri: string;
  /** bcrypt hash of the PIN. */
  pinHash: string;
  /** ISO timestamp when the share was created. */
  createdAt: string;
  /** Whether the share is currently active. */
  enabled: boolean;
  /** Optional label for the share link. */
  label?: string;
}

export interface ShareSession {
  token: string;
  shareToken: string;
  workspaceUri: string;
  createdAt: number;
}

// ── Config ──

export interface ShareConfig {
  /** Session TTL for share viewers in milliseconds. Default: 8 hours. */
  sessionTtlMs: number;
  /** Maximum number of share links per workspace. Default: 10. */
  maxLinksPerWorkspace: number;
}

export function resolveShareConfig(
  env: NodeJS.ProcessEnv = process.env,
): ShareConfig {
  const ttlHours = parseInt(env.PORTA_SHARE_SESSION_TTL ?? "8", 10);
  const maxLinks = parseInt(env.PORTA_SHARE_MAX_LINKS ?? "10", 10);
  return {
    sessionTtlMs: ttlHours * 60 * 60 * 1000,
    maxLinksPerWorkspace: maxLinks,
  };
}

// ── Persistence ──

const __dirname = dirname(fileURLToPath(import.meta.url));
const SHARES_FILE = resolve(__dirname, "../../../.porta-shares.json");

function loadShares(): ShareLink[] {
  try {
    if (existsSync(SHARES_FILE)) {
      const raw = readFileSync(SHARES_FILE, "utf-8");
      const data = JSON.parse(raw);
      return Array.isArray(data) ? data : [];
    }
  } catch (err) {
    console.warn(`[share] Failed to load shares file: ${(err as Error).message}`);
  }
  return [];
}

function saveShares(shares: ShareLink[]): void {
  try {
    writeFileSync(SHARES_FILE, JSON.stringify(shares, null, 2), "utf-8");
  } catch (err) {
    console.warn(`[share] Failed to save shares file: ${(err as Error).message}`);
  }
}

// ── In-memory state ──

let shares: ShareLink[] = loadShares();

/** Share sessions — separate from main auth sessions. */
const shareSessions = new Map<string, ShareSession>();

/** Generate a URL-safe random token. */
function generateShareToken(): string {
  return randomBytes(24).toString("base64url");
}

/** Generate a cryptographically random session token. */
function generateSessionToken(): string {
  return randomBytes(32).toString("hex");
}

// ── Public API ──

/**
 * Create a new share link for a workspace.
 * @returns The created share link, or null if limit reached.
 */
export function createShareLink(
  workspaceUri: string,
  pin: string,
  config: ShareConfig,
  label?: string,
): ShareLink | null {
  const existing = shares.filter(
    (s) => s.workspaceUri === workspaceUri && s.enabled,
  );
  if (existing.length >= config.maxLinksPerWorkspace) {
    return null;
  }

  const shareLink: ShareLink = {
    shareToken: generateShareToken(),
    workspaceUri,
    pinHash: hashSync(pin, 10),
    createdAt: new Date().toISOString(),
    enabled: true,
    label,
  };

  shares.push(shareLink);
  saveShares(shares);

  console.log(
    `[share] Created share link for ${workspaceUri} (token: ${shareLink.shareToken.slice(0, 8)}…)`,
  );

  return shareLink;
}

/**
 * Authenticate a share token with a PIN.
 * @returns A scoped session token, or null if auth fails.
 */
export function authenticateShare(
  shareToken: string,
  pin: string,
  config: ShareConfig,
): { sessionToken: string; workspaceUri: string } | null {
  const share = shares.find(
    (s) => s.shareToken === shareToken && s.enabled,
  );
  if (!share) return null;

  if (!compareSync(pin, share.pinHash)) return null;

  // Create a scoped session
  const sessionToken = generateSessionToken();
  shareSessions.set(sessionToken, {
    token: sessionToken,
    shareToken,
    workspaceUri: share.workspaceUri,
    createdAt: Date.now(),
  });

  console.log(
    `[share] Authenticated viewer for ${share.workspaceUri} (share: ${shareToken.slice(0, 8)}…)`,
  );

  return { sessionToken, workspaceUri: share.workspaceUri };
}

/**
 * Look up a share link by its token (for public info like workspace name).
 */
export function getShareByToken(shareToken: string): ShareLink | undefined {
  return shares.find((s) => s.shareToken === shareToken && s.enabled);
}

/**
 * Validate a share session token and return scope info.
 */
export function validateShareSession(
  sessionToken: string,
  config: ShareConfig,
): ShareSession | null {
  const session = shareSessions.get(sessionToken);
  if (!session) return null;

  // TTL check
  if (Date.now() - session.createdAt > config.sessionTtlMs) {
    shareSessions.delete(sessionToken);
    return null;
  }

  // Verify the underlying share is still enabled
  const share = shares.find(
    (s) => s.shareToken === session.shareToken && s.enabled,
  );
  if (!share) {
    shareSessions.delete(sessionToken);
    return null;
  }

  return session;
}

/**
 * Check if a session token belongs to a share session.
 */
export function isShareSessionToken(sessionToken: string): boolean {
  return shareSessions.has(sessionToken);
}

/**
 * Get all share links for a workspace.
 */
export function getSharesForWorkspace(workspaceUri: string): ShareLink[] {
  return shares.filter((s) => s.workspaceUri === workspaceUri);
}

/**
 * Get all share links.
 */
export function getAllShares(): ShareLink[] {
  return [...shares];
}

/**
 * Revoke (disable) a share link.
 */
export function revokeShare(shareToken: string): boolean {
  const share = shares.find((s) => s.shareToken === shareToken);
  if (!share) return false;

  share.enabled = false;
  saveShares(shares);

  // Invalidate all sessions for this share
  for (const [token, session] of shareSessions) {
    if (session.shareToken === shareToken) {
      shareSessions.delete(token);
    }
  }

  console.log(`[share] Revoked share link (token: ${shareToken.slice(0, 8)}…)`);
  return true;
}

/**
 * Re-enable a previously revoked share link.
 */
export function enableShare(shareToken: string): boolean {
  const share = shares.find((s) => s.shareToken === shareToken);
  if (!share) return false;

  share.enabled = true;
  saveShares(shares);
  return true;
}

/**
 * Delete a share link permanently.
 */
export function deleteShare(shareToken: string): boolean {
  const idx = shares.findIndex((s) => s.shareToken === shareToken);
  if (idx === -1) return false;

  shares.splice(idx, 1);
  saveShares(shares);

  // Invalidate sessions
  for (const [token, session] of shareSessions) {
    if (session.shareToken === shareToken) {
      shareSessions.delete(token);
    }
  }

  return true;
}

/**
 * Cleanup expired share sessions.
 */
export function cleanupShareSessions(config: ShareConfig): number {
  const now = Date.now();
  let removed = 0;
  for (const [token, session] of shareSessions) {
    if (now - session.createdAt > config.sessionTtlMs) {
      shareSessions.delete(token);
      removed++;
    }
  }
  return removed;
}

/** Start periodic cleanup of expired share sessions. */
export function startShareSessionCleanup(config: ShareConfig): void {
  const timer = setInterval(() => {
    cleanupShareSessions(config);
  }, 5 * 60_000);
  timer.unref?.();
}
