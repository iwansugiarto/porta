import { describe, it, expect } from "vitest";
import {
  extractBearerToken,
  validateToken,
  validateWebSocketToken,
  resolveAuthConfig,
  isAuthRequired,
  validateCredentials,
  generateSessionToken,
  addSession,
  isValidSession,
  removeSession,
  getAuthMethods,
  type AuthConfig,
} from "../auth.js";

const baseConfig: AuthConfig = {
  token: undefined,
  username: undefined,
  password: undefined,
  sessionTtlMs: 24 * 60 * 60 * 1000,
  publicPaths: [],
};

describe("extractBearerToken", () => {
  it("extracts token from valid Bearer header", () => {
    expect(extractBearerToken("Bearer my-secret-token")).toBe(
      "my-secret-token",
    );
  });

  it("is case-insensitive for Bearer prefix", () => {
    expect(extractBearerToken("bearer my-token")).toBe("my-token");
    expect(extractBearerToken("BEARER my-token")).toBe("my-token");
  });

  it("returns undefined for missing header", () => {
    expect(extractBearerToken(undefined)).toBeUndefined();
  });

  it("returns undefined for empty string", () => {
    expect(extractBearerToken("")).toBeUndefined();
  });

  it("returns undefined for non-Bearer auth schemes", () => {
    expect(extractBearerToken("Basic abc123")).toBeUndefined();
  });

  it("returns undefined for malformed Bearer header", () => {
    expect(extractBearerToken("Bearer")).toBeUndefined();
    expect(extractBearerToken("Bearer ")).toBeUndefined();
  });
});

describe("validateToken", () => {
  it("returns true for matching token", () => {
    expect(validateToken("my-secret", "my-secret")).toBe(true);
  });

  it("returns false for non-matching token", () => {
    expect(validateToken("wrong-token", "my-secret")).toBe(false);
  });

  it("returns false for undefined token", () => {
    expect(validateToken(undefined, "my-secret")).toBe(false);
  });

  it("returns false for empty token", () => {
    expect(validateToken("", "my-secret")).toBe(false);
  });

  it("returns false for different lengths", () => {
    expect(validateToken("short", "a-much-longer-secret")).toBe(false);
  });
});

describe("validateWebSocketToken", () => {
  it("returns true when no secret configured (auth disabled)", () => {
    expect(validateWebSocketToken(undefined, undefined, undefined, false)).toBe(true);
  });

  it("returns true for valid query token", () => {
    expect(validateWebSocketToken("my-secret", undefined, "my-secret", false)).toBe(
      true,
    );
  });

  it("returns true for valid auth header", () => {
    expect(
      validateWebSocketToken(undefined, "Bearer my-secret", "my-secret", false),
    ).toBe(true);
  });

  it("prefers query token over header", () => {
    expect(
      validateWebSocketToken("my-secret", "Bearer wrong", "my-secret", false),
    ).toBe(true);
  });

  it("returns false when secret set but no token provided", () => {
    expect(validateWebSocketToken(undefined, undefined, "my-secret", false)).toBe(
      false,
    );
  });

  it("returns false for wrong query token", () => {
    expect(validateWebSocketToken("wrong", undefined, "my-secret", false)).toBe(false);
  });

  it("falls back to header when query token is wrong", () => {
    expect(
      validateWebSocketToken("wrong", "Bearer my-secret", "my-secret", false),
    ).toBe(true);
  });
});

describe("resolveAuthConfig", () => {
  it("reads token from PORTA_AUTH_TOKEN", () => {
    const config = resolveAuthConfig({
      PORTA_AUTH_TOKEN: "test-token",
    } as NodeJS.ProcessEnv);
    expect(config.token).toBe("test-token");
  });

  it("trims whitespace from token", () => {
    const config = resolveAuthConfig({
      PORTA_AUTH_TOKEN: "  test-token  ",
    } as NodeJS.ProcessEnv);
    expect(config.token).toBe("test-token");
  });

  it("returns undefined token when env var is empty", () => {
    const config = resolveAuthConfig({
      PORTA_AUTH_TOKEN: "",
    } as NodeJS.ProcessEnv);
    expect(config.token).toBeUndefined();
  });

  it("returns undefined token when env var is not set", () => {
    const config = resolveAuthConfig({} as NodeJS.ProcessEnv);
    expect(config.token).toBeUndefined();
  });

  it("reads username and password", () => {
    const config = resolveAuthConfig({
      PORTA_AUTH_USER: "admin",
      PORTA_AUTH_PASSWORD: "secret",
    } as NodeJS.ProcessEnv);
    expect(config.username).toBe("admin");
    expect(config.password).toBe("secret");
  });

  it("includes health, auth check, and auth login in public paths", () => {
    const config = resolveAuthConfig({} as NodeJS.ProcessEnv);
    expect(config.publicPaths).toContain("/api/health");
    expect(config.publicPaths).toContain("/api/auth/check");
    expect(config.publicPaths).toContain("/api/auth/login");
  });
});

describe("isAuthRequired", () => {
  it("returns true when token is set", () => {
    expect(isAuthRequired({ ...baseConfig, token: "secret" })).toBe(true);
  });

  it("returns true when username and password are set", () => {
    expect(
      isAuthRequired({ ...baseConfig, username: "admin", password: "pw" }),
    ).toBe(true);
  });

  it("returns false when nothing is configured", () => {
    expect(isAuthRequired(baseConfig)).toBe(false);
  });

  it("returns false when only username is set (no password)", () => {
    expect(isAuthRequired({ ...baseConfig, username: "admin" })).toBe(false);
  });
});

describe("getAuthMethods", () => {
  it("reports both methods when both are configured", () => {
    const methods = getAuthMethods({
      ...baseConfig,
      token: "secret",
      username: "admin",
      password: "pw",
    });
    expect(methods.token).toBe(true);
    expect(methods.password).toBe(true);
  });

  it("reports only token when no password", () => {
    const methods = getAuthMethods({ ...baseConfig, token: "secret" });
    expect(methods.token).toBe(true);
    expect(methods.password).toBe(false);
  });

  it("reports only password when no token", () => {
    const methods = getAuthMethods({
      ...baseConfig,
      username: "admin",
      password: "pw",
    });
    expect(methods.token).toBe(false);
    expect(methods.password).toBe(true);
  });
});

describe("validateCredentials", () => {
  const config: AuthConfig = {
    ...baseConfig,
    username: "admin",
    password: "my-password",
  };

  it("returns true for correct credentials", () => {
    expect(validateCredentials("admin", "my-password", config)).toBe(true);
  });

  it("returns false for wrong username", () => {
    expect(validateCredentials("wrong", "my-password", config)).toBe(false);
  });

  it("returns false for wrong password", () => {
    expect(validateCredentials("admin", "wrong", config)).toBe(false);
  });

  it("returns false when password auth not configured", () => {
    expect(validateCredentials("admin", "pw", baseConfig)).toBe(false);
  });
});

describe("session tokens", () => {
  it("generates unique session tokens", () => {
    const a = generateSessionToken();
    const b = generateSessionToken();
    expect(a).not.toBe(b);
    expect(a.length).toBe(64); // 32 bytes hex
  });

  it("validates stored session tokens", () => {
    const token = generateSessionToken();
    expect(isValidSession(token)).toBe(false);
    addSession(token);
    expect(isValidSession(token)).toBe(true);
  });

  it("removes session tokens", () => {
    const token = generateSessionToken();
    addSession(token);
    expect(isValidSession(token)).toBe(true);
    removeSession(token);
    expect(isValidSession(token)).toBe(false);
  });
});

