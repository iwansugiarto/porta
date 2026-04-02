/**
 * Authentication module.
 *
 * Supports two authentication methods:
 *   1. Shared token (PORTA_AUTH_TOKEN) — static Bearer token
 *   2. Username/password (PORTA_AUTH_USER + PORTA_AUTH_PASSWORD) —
 *      web login returns a session token used as Bearer token
 *
 * Both methods result in a Bearer token for API requests.
 * Backward-compatible: auth is disabled when nothing is configured
 * and the proxy is bound to a loopback address.
 */

import type { Context, Next } from "hono";
import {
  timingSafeEqual as cryptoTimingSafeEqual,
  randomBytes,
} from "node:crypto";
import { isLoopbackHost, resolveProxyHost } from "./exposure.js";

// ── Config ──

export interface AuthConfig {
  /** The shared secret token. Undefined = token auth disabled. */
  token: string | undefined;
  /** Username for password-based auth. */
  username: string | undefined;
  /** Password for password-based auth. */
  password: string | undefined;
  /** Paths that bypass authentication even when auth is configured. */
  publicPaths: string[];
}

export function resolveAuthConfig(
  env: NodeJS.ProcessEnv = process.env,
): AuthConfig {
  return {
    token: env.PORTA_AUTH_TOKEN?.trim() || undefined,
    username: env.PORTA_AUTH_USER?.trim() || undefined,
    password: env.PORTA_AUTH_PASSWORD?.trim() || undefined,
    publicPaths: ["/api/health", "/api/auth/check", "/api/auth/login"],
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

/**
 * In-memory session store for password-based auth.
 * When a user logs in with username/password, a random session token
 * is generated and stored here. The session token is then used as a
 * Bearer token for all subsequent API requests.
 *
 * Sessions are ephemeral — they're lost on proxy restart (user just
 * logs in again). This is acceptable for a single-user self-hosted tool.
 */
const activeSessions = new Set<string>();

/** Maximum number of active sessions (prevents memory leaks). */
const MAX_SESSIONS = 50;

/** Generate a cryptographically random session token. */
export function generateSessionToken(): string {
  return randomBytes(32).toString("hex");
}

/** Store a session token. Evicts oldest if limit reached. */
export function addSession(token: string): void {
  // Simple eviction: if at limit, clear all and start fresh.
  // With MAX_SESSIONS=50 and single-user this is effectively never hit.
  if (activeSessions.size >= MAX_SESSIONS) {
    activeSessions.clear();
  }
  activeSessions.add(token);
}

/** Check if a session token is valid. */
export function isValidSession(token: string): boolean {
  return activeSessions.has(token);
}

/** Remove a session token (logout). */
export function removeSession(token: string): void {
  activeSessions.delete(token);
}

/** Validate username/password credentials. */
export function validateCredentials(
  username: string,
  password: string,
  config: AuthConfig,
): boolean {
  if (!config.username || !config.password) return false;
  return (
    timingSafeStringEqual(username, config.username) &&
    timingSafeStringEqual(password, config.password)
  );
}

// ── Middleware ──

/**
 * Hono middleware that enforces authentication.
 *
 * Accepts Bearer tokens from two sources:
 *   1. Static PORTA_AUTH_TOKEN
 *   2. Session tokens from password-based login
 *
 * When neither auth method is configured, all requests pass through.
 */
export function authMiddleware(config: AuthConfig) {
  const authEnabled = !!config.token || (!!config.username && !!config.password);

  return async (c: Context, next: Next) => {
    // Auth disabled — pass through
    if (!authEnabled) {
      return next();
    }

    // Public paths bypass auth
    const path = new URL(c.req.url).pathname;
    if (
      config.publicPaths.some((p) => path === p || path.startsWith(p + "/"))
    ) {
      return next();
    }

    // Extract and validate token
    const token = extractBearerToken(c.req.header("Authorization"));

    // Check against static token
    if (config.token && validateToken(token, config.token)) {
      return next();
    }

    // Check against session tokens (from password login)
    if (token && isValidSession(token)) {
      return next();
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

// ── WebSocket auth ──

/**
 * Validate token for WebSocket upgrade requests.
 * Browsers cannot send custom headers on WebSocket, so the token
 * is passed via query parameter: `?token=<token>`.
 * Also supports the Authorization header for non-browser clients.
 */
export function validateWebSocketToken(
  queryToken: string | undefined,
  authHeader: string | undefined,
  secret: string | undefined,
  /** Check session tokens too (for password-based auth). */
  checkSessions = true,
): boolean {
  // No auth configured → pass through
  if (!secret && activeSessions.size === 0) return true;

  // Try query parameter first (browser WebSocket)
  if (queryToken) {
    if (secret && validateToken(queryToken, secret)) return true;
    if (checkSessions && isValidSession(queryToken)) return true;
  }

  // Try Authorization header (non-browser clients)
  const headerToken = extractBearerToken(authHeader);
  if (headerToken) {
    if (secret && validateToken(headerToken, secret)) return true;
    if (checkSessions && isValidSession(headerToken)) return true;
  }

  return false;
}

// ── Startup helpers ──

/** Log auth status at startup. */
export function logAuthStatus(
  config: AuthConfig,
  env: NodeJS.ProcessEnv = process.env,
): void {
  const host = resolveProxyHost(env);
  const hasToken = !!config.token;
  const hasPassword = !!config.username && !!config.password;

  if (hasToken && hasPassword) {
    console.log(
      "🔒 Authentication enabled (token + password)",
    );
  } else if (hasToken) {
    console.log("🔒 Authentication enabled (PORTA_AUTH_TOKEN)");
  } else if (hasPassword) {
    console.log(
      `🔒 Authentication enabled (user: ${config.username})`,
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

/** Check if auth is required (for the web client to know). */
export function isAuthRequired(config: AuthConfig): boolean {
  return !!config.token || (!!config.username && !!config.password);
}

/** Which auth methods are available (for the web client to render the right form). */
export function getAuthMethods(config: AuthConfig): {
  token: boolean;
  password: boolean;
} {
  return {
    token: !!config.token,
    password: !!config.username && !!config.password,
  };
}
