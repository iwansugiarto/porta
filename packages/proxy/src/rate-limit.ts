/**
 * In-memory sliding-window rate limiter middleware.
 *
 * Tracks requests per IP using a fixed-window counter with automatic
 * cleanup. Includes auth-failure lockout: repeated failed authentication
 * attempts trigger a temporary IP block.
 */

import type { Context, Next } from "hono";

// ── Config ──

export interface RateLimitConfig {
  /** Maximum requests per window. Default: 100. */
  maxRequests: number;
  /** Window duration in milliseconds. Default: 60_000 (1 minute). */
  windowMs: number;
  /** Maximum auth failures before lockout. Default: 5. */
  maxAuthFailures: number;
  /** Lockout duration in milliseconds. Default: 900_000 (15 minutes). */
  lockoutMs: number;
}

export function resolveRateLimitConfig(
  env: NodeJS.ProcessEnv = process.env,
): RateLimitConfig {
  return {
    maxRequests: parseInt(env.PORTA_RATE_LIMIT ?? "100", 10),
    windowMs: 60_000,
    maxAuthFailures: 5,
    lockoutMs: 15 * 60_000,
  };
}

// ── Rate limiter state ──

interface WindowEntry {
  count: number;
  resetAt: number;
}

interface LockoutEntry {
  failures: number;
  lockedUntil: number;
}

export class RateLimiter {
  private windows = new Map<string, WindowEntry>();
  private lockouts = new Map<string, LockoutEntry>();
  private config: RateLimitConfig;
  private cleanupTimer: ReturnType<typeof setInterval> | null = null;

  constructor(config: RateLimitConfig) {
    this.config = config;
    // Periodic cleanup every 5 minutes to prevent memory leaks
    this.cleanupTimer = setInterval(() => this.cleanup(), 5 * 60_000);
    this.cleanupTimer.unref?.();
  }

  /** Check if a request from the given IP should be allowed. */
  isAllowed(ip: string): { allowed: boolean; retryAfterMs?: number } {
    const now = Date.now();

    // Check lockout first
    const lockout = this.lockouts.get(ip);
    if (lockout && lockout.lockedUntil > now) {
      return {
        allowed: false,
        retryAfterMs: lockout.lockedUntil - now,
      };
    }

    // Check rate limit window
    const entry = this.windows.get(ip);
    if (!entry || entry.resetAt <= now) {
      // New window
      this.windows.set(ip, {
        count: 1,
        resetAt: now + this.config.windowMs,
      });
      return { allowed: true };
    }

    if (entry.count >= this.config.maxRequests) {
      return {
        allowed: false,
        retryAfterMs: entry.resetAt - now,
      };
    }

    entry.count++;
    return { allowed: true };
  }

  /** Record an authentication failure for lockout tracking. */
  recordAuthFailure(ip: string): void {
    const now = Date.now();
    const entry = this.lockouts.get(ip) ?? { failures: 0, lockedUntil: 0 };

    // Reset count if previous lockout has expired
    if (entry.lockedUntil > 0 && entry.lockedUntil <= now) {
      entry.failures = 0;
    }

    entry.failures++;

    if (entry.failures >= this.config.maxAuthFailures) {
      entry.lockedUntil = now + this.config.lockoutMs;
      console.warn(
        `🚫 IP ${ip} locked out for ${this.config.lockoutMs / 1000}s after ${entry.failures} auth failures`,
      );
    }

    this.lockouts.set(ip, entry);
  }

  /** Clear auth failure count for an IP (on successful auth). */
  clearAuthFailures(ip: string): void {
    this.lockouts.delete(ip);
  }

  /** Remove expired entries to prevent memory leaks. */
  private cleanup(): void {
    const now = Date.now();
    for (const [ip, entry] of this.windows) {
      if (entry.resetAt <= now) this.windows.delete(ip);
    }
    for (const [ip, entry] of this.lockouts) {
      if (entry.lockedUntil <= now && entry.failures < this.config.maxAuthFailures) {
        this.lockouts.delete(ip);
      }
    }
  }

  /** Shut down the cleanup timer (for testing). */
  destroy(): void {
    if (this.cleanupTimer) {
      clearInterval(this.cleanupTimer);
      this.cleanupTimer = null;
    }
  }
}

// ── Middleware ──

/** Extract client IP from request. */
export function getClientIp(c: Context): string {
  // Standard proxy headers (Cloudflare, nginx, etc.)
  return (
    c.req.header("CF-Connecting-IP") ??
    c.req.header("X-Forwarded-For")?.split(",")[0].trim() ??
    c.req.header("X-Real-IP") ??
    "unknown"
  );
}

/**
 * Hono middleware that enforces per-IP rate limiting.
 * Returns 429 Too Many Requests when the limit is exceeded.
 */
export function rateLimitMiddleware(limiter: RateLimiter) {
  return async (c: Context, next: Next) => {
    const ip = getClientIp(c);
    const result = limiter.isAllowed(ip);

    if (!result.allowed) {
      const retryAfterSec = Math.ceil((result.retryAfterMs ?? 60_000) / 1000);
      c.header("Retry-After", String(retryAfterSec));
      return c.json(
        {
          error: "Too Many Requests",
          message: `Rate limit exceeded. Try again in ${retryAfterSec}s.`,
        },
        429,
      );
    }

    return next();
  };
}
