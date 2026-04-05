import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { getMetadata, isAutoApproveEnabled } from "../metadata.js";

describe("getMetadata", () => {
  const originalEnv = process.env.PORTA_AUTO_APPROVE;

  afterEach(() => {
    // Restore original env after each test
    if (originalEnv === undefined) {
      delete process.env.PORTA_AUTO_APPROVE;
    } else {
      process.env.PORTA_AUTO_APPROVE = originalEnv;
    }
  });

  it("returns base fields without file access", async () => {
    const meta = await getMetadata();
    expect(meta.ideName).toBe("porta");
    expect(meta.ideVersion).toBe("0.1.0");
    expect(meta.extensionVersion).toBe("0.1.0");
    expect(meta.allowFileAccess).toBeUndefined();
    expect(meta.allWorkspaceTrustGranted).toBeUndefined();
  });

  it("returns base fields with fileAccessGranted=false", async () => {
    const meta = await getMetadata(false);
    expect(meta.ideName).toBe("porta");
    expect(meta.allowFileAccess).toBeUndefined();
  });

  it("includes file access fields when granted (auto-approve default)", async () => {
    delete process.env.PORTA_AUTO_APPROVE;
    const meta = await getMetadata(true);
    expect(meta.ideName).toBe("porta");
    expect(meta.allowFileAccess).toBe(true);
    expect(meta.allWorkspaceTrustGranted).toBe(true);
  });

  it("omits allWorkspaceTrustGranted when PORTA_AUTO_APPROVE=false", async () => {
    process.env.PORTA_AUTO_APPROVE = "false";
    const meta = await getMetadata(true);
    expect(meta.allowFileAccess).toBe(true);
    expect(meta.allWorkspaceTrustGranted).toBeUndefined();
  });

  it("includes allWorkspaceTrustGranted when PORTA_AUTO_APPROVE=true", async () => {
    process.env.PORTA_AUTO_APPROVE = "true";
    const meta = await getMetadata(true);
    expect(meta.allowFileAccess).toBe(true);
    expect(meta.allWorkspaceTrustGranted).toBe(true);
  });

  it("returns a fresh object on each call", async () => {
    const a = await getMetadata();
    const b = await getMetadata();
    expect(a).not.toBe(b);
    expect(a).toEqual(b);
  });
});

describe("isAutoApproveEnabled", () => {
  const originalEnv = process.env.PORTA_AUTO_APPROVE;

  afterEach(() => {
    if (originalEnv === undefined) {
      delete process.env.PORTA_AUTO_APPROVE;
    } else {
      process.env.PORTA_AUTO_APPROVE = originalEnv;
    }
  });

  it("defaults to true when env is unset", () => {
    delete process.env.PORTA_AUTO_APPROVE;
    expect(isAutoApproveEnabled()).toBe(true);
  });

  it("defaults to true when env is empty string", () => {
    process.env.PORTA_AUTO_APPROVE = "";
    expect(isAutoApproveEnabled()).toBe(true);
  });

  it("returns false when env is 'false'", () => {
    process.env.PORTA_AUTO_APPROVE = "false";
    expect(isAutoApproveEnabled()).toBe(false);
  });

  it("returns false when env is 'FALSE'", () => {
    process.env.PORTA_AUTO_APPROVE = "FALSE";
    expect(isAutoApproveEnabled()).toBe(false);
  });

  it("returns false when env is '0'", () => {
    process.env.PORTA_AUTO_APPROVE = "0";
    expect(isAutoApproveEnabled()).toBe(false);
  });

  it("returns true when env is 'true'", () => {
    process.env.PORTA_AUTO_APPROVE = "true";
    expect(isAutoApproveEnabled()).toBe(true);
  });

  it("returns true when env is '1'", () => {
    process.env.PORTA_AUTO_APPROVE = "1";
    expect(isAutoApproveEnabled()).toBe(true);
  });
});
