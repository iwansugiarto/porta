/**
 * Exponential backoff retry utility.
 *
 * Wraps an async function and retries on transient Telegram API errors
 * (network timeouts, DNS resolution failures, rate limits).
 *
 * Inspired by the retry pattern in antigravity-telegram-suite.
 */

/** Errors that warrant a retry (transient network issues). */
const RETRYABLE_PATTERNS = [
  "EAI_AGAIN",
  "ETIMEDOUT",
  "ECONNRESET",
  "ECONNREFUSED",
  "ENOTFOUND",
  "socket hang up",
  "Too Many Requests",
  "429",
];

function isRetryable(err: unknown): boolean {
  const msg = err instanceof Error ? err.message : String(err);
  return RETRYABLE_PATTERNS.some((p) => msg.includes(p));
}

/**
 * Execute an async function with exponential backoff retry.
 *
 * @param fn    The async function to execute.
 * @param retries  Max number of retries (default 3).
 * @param baseDelay  Initial delay in ms (default 1000). Doubles each retry.
 * @returns The result of fn().
 * @throws The last error if all retries are exhausted.
 */
export async function withRetry<T>(
  fn: () => Promise<T>,
  retries = 3,
  baseDelay = 1000,
): Promise<T> {
  let lastError: unknown;

  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastError = err;

      if (attempt >= retries || !isRetryable(err)) {
        throw err;
      }

      const delay = baseDelay * Math.pow(2, attempt);
      console.warn(
        `[retry] Attempt ${attempt + 1}/${retries} failed: ${(err as Error).message}. Retrying in ${delay}ms...`,
      );
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
  }

  throw lastError;
}
