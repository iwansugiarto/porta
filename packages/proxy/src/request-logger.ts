/**
 * Structured request logging middleware.
 *
 * Logs each API request with timestamp, method, path, client IP,
 * response status, and duration. Auth failures and rate limit blocks
 * are logged at WARN level.
 */

import type { Context, Next } from "hono";
import { getClientIp } from "./rate-limit.js";

/**
 * Hono middleware that logs every request with timing information.
 */
export function requestLogger() {
  return async (c: Context, next: Next) => {
    const start = Date.now();
    const method = c.req.method;
    const path = new URL(c.req.url).pathname;
    const ip = getClientIp(c);

    await next();

    const status = c.res.status;
    const duration = Date.now() - start;

    const logLine = `${method} ${path} ${status} ${duration}ms [${ip}]`;

    if (status === 401) {
      console.warn(`⛔ ${logLine} (auth failure)`);
    } else if (status === 429) {
      console.warn(`🚫 ${logLine} (rate limited)`);
    } else if (status >= 500) {
      console.error(`❌ ${logLine}`);
    } else {
      // Only log non-routine requests to avoid flooding logs
      // Skip health checks and high-frequency polling endpoints
      if (path !== "/api/health") {
        console.log(`📡 ${logLine}`);
      }
    }
  };
}
