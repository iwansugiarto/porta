/**
 * Authentication module.
 *
 * Supports two authentication methods:
 *   1. Shared token (PORTA_AUTH_TOKEN) — static Bearer token
 *   2. Username/password (PORTA_AUTH_USER + PORTA_AUTH_PASSWORD) —
 *      web login returns a session token used as Bearer token
 *
 * Password can be plaintext or bcrypt hash ($2a$/$2b$ prefix).
 * Sessions have configurable TTL (default 24h).
 */

import type { Context, Next } from "hono";
import {
  timingSafeEqual as cryptoTimingSafeEqual,
  randomBytes,
} from "node:crypto";
import { compareSync } from "bcryptjs";
import { isLoopbackHost, resolveProxyHost } from "./exposure.js";
import {
  validateShareSession,
  isShareSessionToken,
  resolveShareConfig,
  type ShareSession,
} from "./share.js";

// ── Config ──

export interface AuthConfig {
  /** The shared secret token. Undefined = token auth disabled. */
  token: string | undefined;
  /** Username for password-based auth. */
  username: string | undefined;
  /** Password (plaintext or bcrypt hash) for password-based auth. */
  password: string | undefined;
  /** Session TTL in milliseconds. Default: 24 hours. */
  sessionTtlMs: number;
  /** Paths that bypass authentication even when auth is configured. */
  publicPaths: string[];
}

/** Check if a string is a bcrypt hash. */
function isBcryptHash(s: string): boolean {
  return /^\$2[aby]\$\d{1,2}\$/.test(s);
}

export function resolveAuthConfig(
  env: NodeJS.ProcessEnv = process.env,
): AuthConfig {
  const ttlHours = parseInt(env.PORTA_SESSION_TTL ?? "24", 10);
  return {
    token: env.PORTA_AUTH_TOKEN?.trim() || undefined,
    username: env.PORTA_AUTH_USER?.trim() || undefined,
    password: env.PORTA_AUTH_PASSWORD?.trim() || undefined,
    sessionTtlMs: ttlHours * 60 * 60 * 1000,
    publicPaths: ["/api/health", "/api/auth/check", "/api/auth/login", "/api/share/auth", "/api/share/info", "/dashboard/api/health", "/dashboard/api/config/auto-approve"],
  };
}

// ── Token helpers ──

/** Constant-time string comparison to prevent timing attacks. */
function timingSafeStringEqual(a: string, b: string): boolean {
  const encoder = new TextEncoder();
  const bufA = encoder.encode(a);
  const bufB = encoder.encode(b);
  if (bufA.byteLength !== bufB.byteLength) return false;
  return cryptoTimingSafeEqual(bufA, bufB);
}

/** Extract Bearer token from Authorization header. */
export function extractBearerToken(
  authHeader: string | undefined,
): string | undefined {
  if (!authHeader) return undefined;
  const match = authHeader.match(/^Bearer\s+(\S+)$/i);
  return match?.[1];
}

/** Validate a token against the configured secret. */
export function validateToken(
  token: string | undefined,
  secret: string,
): boolean {
  if (!token) return false;
  return timingSafeStringEqual(token, secret);
}

// ── Session token management ──

interface SessionEntry {
  token: string;
  createdAt: number;
}

/**
 * In-memory session store for password-based auth.
 * Sessions have a TTL — expired sessions are automatically cleaned up.
 */
const activeSessions = new Map<string, SessionEntry>();

/** Maximum number of active sessions. */
const MAX_SESSIONS = 50;

/** Cleanup interval timer. */
let sessionCleanupTimer: ReturnType<typeof setInterval> | null = null;

/** Start the session cleanup timer. */
export function startSessionCleanup(ttlMs: number): void {
  if (sessionCleanupTimer) return;
  // Clean up every 5 minutes
  sessionCleanupTimer = setInterval(() => {
    cleanupExpiredSessions(ttlMs);
  }, 5 * 60_000);
  sessionCleanupTimer.unref?.();
}

/** Remove expired sessions. */
export function cleanupExpiredSessions(ttlMs: number): number {
  const now = Date.now();
  let removed = 0;
  for (const [token, entry] of activeSessions) {
    if (now - entry.createdAt > ttlMs) {
      activeSessions.delete(token);
      removed++;
    }
  }
  return removed;
}

/** Generate a cryptographically random session token. */
export function generateSessionToken(): string {
  return randomBytes(32).toString("hex");
}

/** Store a session token. Evicts expired first, then oldest if at limit. */
export function addSession(token: string, ttlMs?: number): void {
  // Evict expired sessions first
  if (ttlMs) cleanupExpiredSessions(ttlMs);

  // If still at limit, clear all and start fresh
  if (activeSessions.size >= MAX_SESSIONS) {
    activeSessions.clear();
  }
  activeSessions.set(token, { token, createdAt: Date.now() });
}

/** Check if a session token is valid (exists and not expired). */
export function isValidSession(token: string, ttlMs?: number): boolean {
  const entry = activeSessions.get(token);
  if (!entry) return false;
  if (ttlMs && Date.now() - entry.createdAt > ttlMs) {
    activeSessions.delete(token);
    return false;
  }
  return true;
}

/** Remove a session token (logout). */
export function removeSession(token: string): void {
  activeSessions.delete(token);
}

/** Get the count of active sessions. */
export function getSessionCount(): number {
  return activeSessions.size;
}

/** Validate username/password credentials. Supports bcrypt hashed passwords. */
export function validateCredentials(
  username: string,
  password: string,
  config: AuthConfig,
): boolean {
  if (!config.username || !config.password) return false;

  // Username check (always timing-safe)
  if (!timingSafeStringEqual(username, config.username)) return false;

  // Password check — bcrypt or plaintext
  if (isBcryptHash(config.password)) {
    return compareSync(password, config.password);
  }
  return timingSafeStringEqual(password, config.password);
}

// ── CSRF Protection ──

/**
 * Hono middleware that validates CSRF protection header.
 *
 * Since we use Bearer tokens (not cookies), CSRF is inherently mitigated
 * for authenticated requests. However, as defense-in-depth, we require
 * a custom header `X-Porta-Request: 1` on all mutating (non-GET/HEAD)
 * requests. Browsers won't send this header cross-origin without CORS
 * preflight approval.
 *
 * This protects against cross-origin form submissions that might bypass
 * the auth check if cookies were ever introduced.
 */
export function csrfProtection() {
  return async (c: Context, next: Next) => {
    const method = c.req.method.toUpperCase();

    // Safe methods don't need CSRF protection
    if (method === "GET" || method === "HEAD" || method === "OPTIONS") {
      return next();
    }

    // Public auth endpoints are exempt (login needs to work from the form)
    const path = new URL(c.req.url).pathname;
    if (
      path === "/api/auth/login" ||
      path === "/api/auth/logout" ||
      path === "/api/share/auth" ||
      path.startsWith("/api/share/info/")
    ) {
      return next();
    }

    // Require custom header on mutating requests
    const csrfHeader = c.req.header("X-Porta-Request");
    if (!csrfHeader) {
      return c.json(
        {
          error: "Forbidden",
          message: "Missing X-Porta-Request header",
        },
        403,
      );
    }

    return next();
  };
}

// ── Auth Middleware ──

/**
 * Hono middleware that enforces authentication.
 *
 * Accepts Bearer tokens from two sources:
 *   1. Static PORTA_AUTH_TOKEN
 *   2. Session tokens from password-based login (with TTL check)
 */
export function authMiddleware(config: AuthConfig) {
  const authEnabled =
    !!config.token || (!!config.username && !!config.password);
  const shareConfig = resolveShareConfig();

  return async (c: Context, next: Next) => {
    if (!authEnabled) {
      return next();
    }

    const path = new URL(c.req.url).pathname;
    if (
      config.publicPaths.some((p) => path === p || path.startsWith(p + "/"))
    ) {
      return next();
    }

    const token = extractBearerToken(c.req.header("Authorization"));

    // Check static token
    if (config.token && validateToken(token, config.token)) {
      return next();
    }

    // Check session token (with TTL)
    if (token && isValidSession(token, config.sessionTtlMs)) {
      return next();
    }

    // Check share session token (read-only, workspace-scoped)
    if (token) {
      const shareSession = validateShareSession(token, shareConfig);
      if (shareSession) {
        // Store share scope on the context for downstream route handlers
        c.set("shareSession", shareSession);

        // Block mutations for share sessions
        const method = c.req.method.toUpperCase();
        if (method !== "GET" && method !== "HEAD" && method !== "OPTIONS") {
          // Allow only share-specific POST endpoints
          if (!path.startsWith("/api/share/")) {
            return c.json(
              {
                error: "Forbidden",
                message: "Share sessions have read-only access",
              },
              403,
            );
          }
        }

        return next();
      }
    }

    return c.json(
      {
        error: "Unauthorized",
        message: "Valid authentication token required",
      },
      401,
    );
  };
}

/**
 * Extract share session from the Hono context.
 * Returns undefined if the request is not from a share session.
 */
export function getShareSession(c: Context): ShareSession | undefined {
  return c.get("shareSession") as ShareSession | undefined;
}

// ── WebSocket auth ──

export function validateWebSocketToken(
  queryToken: string | undefined,
  authHeader: string | undefined,
  secret: string | undefined,
  checkSessions = true,
  sessionTtlMs?: number,
): boolean {
  const hasStaticAuth = !!secret;
  const hasSessionAuth = checkSessions && activeSessions.size > 0;
  if (!hasStaticAuth && !hasSessionAuth) return true;

  const shareConfig = resolveShareConfig();

  if (queryToken) {
    if (secret && validateToken(queryToken, secret)) return true;
    if (checkSessions && isValidSession(queryToken, sessionTtlMs))
      return true;
    // Check share sessions for WebSocket (read-only streaming)
    if (validateShareSession(queryToken, shareConfig)) return true;
  }

  const headerToken = extractBearerToken(authHeader);
  if (headerToken) {
    if (secret && validateToken(headerToken, secret)) return true;
    if (checkSessions && isValidSession(headerToken, sessionTtlMs))
      return true;
    if (validateShareSession(headerToken, shareConfig)) return true;
  }

  return false;
}

// ── Startup helpers ──

export function logAuthStatus(
  config: AuthConfig,
  env: NodeJS.ProcessEnv = process.env,
): void {
  const host = resolveProxyHost(env);
  const hasToken = !!config.token;
  const hasPassword = !!config.username && !!config.password;
  const ttlHours = Math.round(config.sessionTtlMs / 3_600_000);
  const hashType = config.password && isBcryptHash(config.password)
    ? " [bcrypt]"
    : "";

  if (hasToken && hasPassword) {
    console.log(
      `🔒 Authentication enabled (token + password${hashType}, session TTL: ${ttlHours}h)`,
    );
  } else if (hasToken) {
    console.log("🔒 Authentication enabled (PORTA_AUTH_TOKEN)");
  } else if (hasPassword) {
    console.log(
      `🔒 Authentication enabled (user: ${config.username}${hashType}, session TTL: ${ttlHours}h)`,
    );
  } else if (isLoopbackHost(host)) {
    console.log(
      "🔓 Authentication disabled (loopback-only, no credentials configured)",
    );
  } else {
    console.warn(
      "⚠️  Authentication disabled but proxy is exposed beyond loopback!",
    );
    console.warn(
      "   Set PORTA_AUTH_TOKEN or PORTA_AUTH_USER + PORTA_AUTH_PASSWORD in .env.",
    );
  }
}

export function isAuthRequired(config: AuthConfig): boolean {
  return !!config.token || (!!config.username && !!config.password);
}

export function getAuthMethods(config: AuthConfig): {
  token: boolean;
  password: boolean;
} {
  return {
    token: !!config.token,
    password: !!config.username && !!config.password,
  };
}
