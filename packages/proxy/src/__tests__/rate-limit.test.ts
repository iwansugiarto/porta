import { describe, it, expect, afterEach } from "vitest";
import { RateLimiter, type RateLimitConfig } from "../rate-limit.js";

function makeConfig(overrides: Partial<RateLimitConfig> = {}): RateLimitConfig {
  return {
    maxRequests: 5,
    windowMs: 1000,
    maxAuthFailures: 3,
    lockoutMs: 5000,
    ...overrides,
  };
}

describe("RateLimiter", () => {
  let limiter: RateLimiter;

  afterEach(() => {
    limiter?.destroy();
  });

  it("allows requests within the limit", () => {
    limiter = new RateLimiter(makeConfig({ maxRequests: 3 }));
    expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
    expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
    expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
  });

  it("blocks requests exceeding the limit", () => {
    limiter = new RateLimiter(makeConfig({ maxRequests: 2 }));
    expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
    expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
    const result = limiter.isAllowed("1.2.3.4");
    expect(result.allowed).toBe(false);
    expect(result.retryAfterMs).toBeGreaterThan(0);
  });

  it("tracks IPs independently", () => {
    limiter = new RateLimiter(makeConfig({ maxRequests: 1 }));
    expect(limiter.isAllowed("1.1.1.1").allowed).toBe(true);
    expect(limiter.isAllowed("2.2.2.2").allowed).toBe(true);
    expect(limiter.isAllowed("1.1.1.1").allowed).toBe(false);
    expect(limiter.isAllowed("2.2.2.2").allowed).toBe(false);
  });

  it("resets after window expires", async () => {
    limiter = new RateLimiter(makeConfig({ maxRequests: 1, windowMs: 50 }));
    expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
    expect(limiter.isAllowed("1.2.3.4").allowed).toBe(false);
    await new Promise((r) => setTimeout(r, 60));
    expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
  });

  describe("auth failure lockout", () => {
    it("does not lock out below threshold", () => {
      limiter = new RateLimiter(makeConfig({ maxAuthFailures: 3 }));
      limiter.recordAuthFailure("1.2.3.4");
      limiter.recordAuthFailure("1.2.3.4");
      // 2 failures, threshold is 3
      expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
    });

    it("locks out after exceeding threshold", () => {
      limiter = new RateLimiter(makeConfig({ maxAuthFailures: 3, lockoutMs: 5000 }));
      limiter.recordAuthFailure("1.2.3.4");
      limiter.recordAuthFailure("1.2.3.4");
      limiter.recordAuthFailure("1.2.3.4");
      const result = limiter.isAllowed("1.2.3.4");
      expect(result.allowed).toBe(false);
      expect(result.retryAfterMs).toBeGreaterThan(0);
    });

    it("clears failures on successful auth", () => {
      limiter = new RateLimiter(makeConfig({ maxAuthFailures: 3 }));
      limiter.recordAuthFailure("1.2.3.4");
      limiter.recordAuthFailure("1.2.3.4");
      limiter.clearAuthFailures("1.2.3.4");
      // Should be able to fail again without being locked out
      limiter.recordAuthFailure("1.2.3.4");
      limiter.recordAuthFailure("1.2.3.4");
      expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
    });

    it("lockout expires after lockoutMs", async () => {
      limiter = new RateLimiter(
        makeConfig({ maxAuthFailures: 2, lockoutMs: 50 }),
      );
      limiter.recordAuthFailure("1.2.3.4");
      limiter.recordAuthFailure("1.2.3.4");
      expect(limiter.isAllowed("1.2.3.4").allowed).toBe(false);
      await new Promise((r) => setTimeout(r, 60));
      expect(limiter.isAllowed("1.2.3.4").allowed).toBe(true);
    });

    it("tracks lockouts per IP independently", () => {
      limiter = new RateLimiter(makeConfig({ maxAuthFailures: 2 }));
      limiter.recordAuthFailure("1.1.1.1");
      limiter.recordAuthFailure("1.1.1.1");
      expect(limiter.isAllowed("1.1.1.1").allowed).toBe(false);
      expect(limiter.isAllowed("2.2.2.2").allowed).toBe(true);
    });
  });
});
