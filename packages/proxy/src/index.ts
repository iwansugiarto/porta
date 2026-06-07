/**
 * Porta Proxy Server
 *
 * Hono HTTP server that provides a stable REST API over the
 * Antigravity Language Server's dynamic Connect RPC endpoint.
 */

// ── Global crash safety ──
// Catch uncaught exceptions and unhandled rejections to log before dying.
// This prevents silent crashes (e.g. OOM during JSON.parse) from killing
// the proxy without any diagnostic output.
process.on("uncaughtException", (err) => {
  console.error(`[FATAL] Uncaught exception: ${err.message}`);
  console.error(err.stack);
  // Give time to flush logs, then exit (launchd KeepAlive will restart)
  setTimeout(() => process.exit(1), 500);
});

process.on("unhandledRejection", (reason) => {
  console.error(`[WARN] Unhandled rejection: ${reason}`);
});

import { Hono } from "hono";
import { cors } from "hono/cors";
import { createAdaptorServer } from "@hono/node-server";

import { discovery } from "./routing.js";
import { registerConversationRoutes } from "./routes/conversations.js";
import { registerModelRoutes } from "./routes/models.js";
import { registerWorkspaceRoutes } from "./routes/workspaces.js";
import { registerFileRoutes } from "./routes/files.js";
import { registerSearchRoutes } from "./routes/search.js";
import { registerRpcPassthroughRoutes } from "./routes/rpcPassthrough.js";
import { registerDashboardRoute } from "./dashboard.js";
import {
  assertSupportedListenHost,
  formatListenAddress,
  resolveProxyHost,
} from "./exposure.js";
import { getAllowedOrigins, resolveCorsOrigin } from "./origins.js";
import { setupWebSocket } from "./ws.js";
import {
  resolveAuthConfig,
  authMiddleware,
  csrfProtection,
  isAuthRequired,
  logAuthStatus,
  extractBearerToken,
  validateToken,
  getAuthMethods,
  validateCredentials,
  generateSessionToken,
  addSession,
  removeSession,
  isValidSession,
  startSessionCleanup,
  getShareSession,
} from "./auth.js";
import {
  resolveShareConfig,
  createShareLink,
  authenticateShare,
  getShareByToken,
  getSharesForWorkspace,
  getAllShares,
  revokeShare,
  enableShare,
  deleteShare,
  startShareSessionCleanup,
} from "./share.js";
import {
  RateLimiter,
  resolveRateLimitConfig,
  rateLimitMiddleware,
  getClientIp,
} from "./rate-limit.js";
import { requestLogger } from "./request-logger.js";
import { compressionMiddleware } from "./compression.js";
import { isAutoApproveEnabled } from "./metadata.js";

const PORT = parseInt(process.env.PORTA_PORT ?? "3170", 10);
const HOST = resolveProxyHost();

assertSupportedListenHost(HOST, process.env);

const app = new Hono();

// ── Auth & Rate Limit Config ──

const authConfig = resolveAuthConfig();
const shareConfig = resolveShareConfig();
const rateLimitConfig = resolveRateLimitConfig();
const rateLimiter = new RateLimiter(rateLimitConfig);

// ── Middleware (order matters) ──

// 1. CORS
const ALLOWED_ORIGINS = getAllowedOrigins();

const corsHandler = cors({
  origin: (origin) => resolveCorsOrigin(origin, ALLOWED_ORIGINS),
});

// Wrap CORS to suppress Node 25 "Response body should not be disturbed" errors.
// The response is already sent successfully before the error occurs.
app.use("*", async (c, next) => {
  try {
    await corsHandler(c, next);
  } catch (err) {
    if (
      err instanceof TypeError &&
      /disturbed|locked/i.test(err.message)
    ) {
      // Non-fatal: response was already sent. Suppress.
      return;
    }
    throw err;
  }
});

// 2. Response compression (gzip/deflate for responses > 1KB)
app.use("/api/*", compressionMiddleware());

// 3. Request logging
app.use("/api/*", requestLogger());

// 3. Rate limiting
app.use("/api/*", rateLimitMiddleware(rateLimiter));

// 4. CSRF protection (mutating requests need X-Porta-Request header)
app.use("/api/*", csrfProtection());

// 5. Authentication
app.use("/api/*", authMiddleware(authConfig));

// Track auth failures for lockout
app.use("/api/*", async (c, next) => {
  await next();
  const ip = getClientIp(c);
  if (c.res.status === 401) {
    rateLimiter.recordAuthFailure(ip);
  } else if (c.res.status === 200 || c.res.status === 201) {
    rateLimiter.clearAuthFailures(ip);
  }
});

// ── Auth Endpoints ──
// These are publicly accessible (bypassed by auth middleware via publicPaths).

// Check auth status and available methods
app.get("/api/auth/check", (c) => {
  const required = isAuthRequired(authConfig);
  const methods = getAuthMethods(authConfig);

  if (!required) {
    return c.json({ authRequired: false, authenticated: true, methods });
  }

  // Check if the provided token is valid (static token or session token)
  const token = extractBearerToken(c.req.header("Authorization"));
  let authenticated = false;
  if (token) {
    if (authConfig.token && validateToken(token, authConfig.token)) {
      authenticated = true;
    } else if (isValidSession(token, authConfig.sessionTtlMs)) {
      authenticated = true;
    }
  }

  return c.json({ authRequired: true, authenticated, methods });
});

// Username/password login → returns a session token
app.post("/api/auth/login", async (c) => {
  const methods = getAuthMethods(authConfig);
  if (!methods.password) {
    return c.json(
      { error: "Password authentication is not configured" },
      400,
    );
  }

  const body = await c.req.json().catch(() => ({}));
  const { username, password } = body as {
    username?: string;
    password?: string;
  };

  if (!username || !password) {
    return c.json(
      { error: "Username and password are required" },
      400,
    );
  }

  if (!validateCredentials(username, password, authConfig)) {
    return c.json(
      { error: "Invalid username or password" },
      401,
    );
  }

  // Generate and store session token
  const sessionToken = generateSessionToken();
  addSession(sessionToken, authConfig.sessionTtlMs);

  const ttlHours = Math.round(authConfig.sessionTtlMs / 3_600_000);
  console.log(`🔑 User "${username}" logged in (session expires in ${ttlHours}h)`);

  return c.json({ token: sessionToken });
});

// Logout — invalidates the current session token
app.post("/api/auth/logout", (c) => {
  const token = extractBearerToken(c.req.header("Authorization"));
  if (token) {
    removeSession(token);
    console.log("🔓 Session ended (logout)");
  }
  return c.json({ ok: true });
});

// ── Share Endpoints ──

// Get share link info (public — no auth required, for PIN entry page)
app.get("/api/share/info/:shareToken", (c) => {
  const shareToken = c.req.param("shareToken");
  const share = getShareByToken(shareToken);
  if (!share) {
    return c.json({ error: "Share link not found or disabled" }, 404);
  }
  // Return minimal info (no PIN hash, no internal details)
  return c.json({
    shareToken: share.shareToken,
    workspaceUri: share.workspaceUri,
    label: share.label,
    // Extract workspace name from URI for display
    workspaceName:
      share.workspaceUri.replace("file://", "").split("/").pop() ??
      share.workspaceUri,
  });
});

// Authenticate with share token + PIN (public — no auth required)
app.post("/api/share/auth", async (c) => {
  const body = await c.req.json().catch(() => ({}));
  const { shareToken, pin } = body as {
    shareToken?: string;
    pin?: string;
  };

  if (!shareToken || !pin) {
    return c.json(
      { error: "Share token and PIN are required" },
      400,
    );
  }

  const result = authenticateShare(shareToken, pin, shareConfig);
  if (!result) {
    return c.json(
      { error: "Invalid share token or PIN" },
      401,
    );
  }

  return c.json({
    token: result.sessionToken,
    workspaceUri: result.workspaceUri,
    workspaceName:
      result.workspaceUri.replace("file://", "").split("/").pop() ??
      result.workspaceUri,
  });
});

// Create a share link (requires full auth)
app.post("/api/share/create", async (c) => {
  // Block share sessions from creating shares
  if (getShareSession(c)) {
    return c.json({ error: "Forbidden" }, 403);
  }

  const body = await c.req.json().catch(() => ({}));
  const { workspaceUri, pin, label } = body as {
    workspaceUri?: string;
    pin?: string;
    label?: string;
  };

  if (!workspaceUri || !pin) {
    return c.json(
      { error: "workspaceUri and pin are required" },
      400,
    );
  }

  if (pin.length < 4) {
    return c.json(
      { error: "PIN must be at least 4 characters" },
      400,
    );
  }

  const share = createShareLink(workspaceUri, pin, shareConfig, label);
  if (!share) {
    return c.json(
      { error: "Maximum share links reached for this workspace" },
      400,
    );
  }

  return c.json({
    shareToken: share.shareToken,
    workspaceUri: share.workspaceUri,
    label: share.label,
    createdAt: share.createdAt,
    enabled: share.enabled,
  }, 201);
});

// List all share links (requires full auth)
app.get("/api/share/list", (c) => {
  if (getShareSession(c)) {
    return c.json({ error: "Forbidden" }, 403);
  }

  const wsUri = c.req.query("workspaceUri");
  const shares = wsUri ? getSharesForWorkspace(wsUri) : getAllShares();

  return c.json({
    shares: shares.map((s) => ({
      shareToken: s.shareToken,
      workspaceUri: s.workspaceUri,
      label: s.label,
      createdAt: s.createdAt,
      enabled: s.enabled,
    })),
  });
});

// Revoke a share link (requires full auth)
app.post("/api/share/revoke", async (c) => {
  if (getShareSession(c)) {
    return c.json({ error: "Forbidden" }, 403);
  }

  const body = await c.req.json().catch(() => ({}));
  const { shareToken } = body as { shareToken?: string };

  if (!shareToken) {
    return c.json({ error: "shareToken is required" }, 400);
  }

  const ok = revokeShare(shareToken);
  return c.json({ ok });
});

// Re-enable a share link (requires full auth)
app.post("/api/share/enable", async (c) => {
  if (getShareSession(c)) {
    return c.json({ error: "Forbidden" }, 403);
  }

  const body = await c.req.json().catch(() => ({}));
  const { shareToken } = body as { shareToken?: string };

  if (!shareToken) {
    return c.json({ error: "shareToken is required" }, 400);
  }

  const ok = enableShare(shareToken);
  return c.json({ ok });
});

// Delete a share link permanently (requires full auth)
app.delete("/api/share/:shareToken", (c) => {
  if (getShareSession(c)) {
    return c.json({ error: "Forbidden" }, 403);
  }

  const shareToken = c.req.param("shareToken");
  const ok = deleteShare(shareToken);
  return c.json({ ok });
});

// ── Health ──

app.get("/api/health", async (c) => {
  const instances = await discovery.getInstances();
  const os = await import("node:os");
  return c.json({
    status: "ok",
    proxy: { 
      port: PORT, 
      uptime: process.uptime(),
      memory: process.memoryUsage(),
      loadavg: os.loadavg()
    },
    autoApprove: isAutoApproveEnabled(),
    languageServers: instances.map((i) => ({
      pid: i.pid,
      httpsPort: i.httpsPort,
      workspaceId: i.workspaceId,
      subclientType: i.subclientType ?? "unknown",
      appDataDir: i.appDataDir ?? "unknown",
      source: i.source,
    })),
  });
});

// ── LS Instances (for client LS target selection) ──

app.get("/api/ls-instances", async (c) => {
  const instances = await discovery.getInstances();
  return c.json({
    instances: instances.map((i) => ({
      pid: i.pid,
      subclientType: i.subclientType ?? "unknown",
      appDataDir: i.appDataDir ?? "unknown",
      workspaceId: i.workspaceId,
    })),
  });
});

// ── Config ──

app.get("/api/config/auto-approve", (c) => {
  return c.json({ enabled: isAutoApproveEnabled() });
});

app.post("/api/config/auto-approve", async (c) => {
  const body = await c.req.json().catch(() => ({}));
  if (typeof body.enabled === "boolean") {
    process.env.PORTA_AUTO_APPROVE = body.enabled ? "true" : "false";
  }
  return c.json({ enabled: isAutoApproveEnabled() });
});

// ── Routes ──

registerConversationRoutes(app);
registerModelRoutes(app);
registerWorkspaceRoutes(app);
registerFileRoutes(app);
registerSearchRoutes(app);
registerRpcPassthroughRoutes(app);
registerDashboardRoute(app);

// ── Start ──

const listenAddress = formatListenAddress(HOST, PORT);

logAuthStatus(authConfig);
startSessionCleanup(authConfig.sessionTtlMs);
startShareSessionCleanup(shareConfig);
console.log(`🚀 Porta proxy starting on ${listenAddress}`);

const server = createAdaptorServer({ fetch: app.fetch, port: PORT });

setupWebSocket(server, PORT, ALLOWED_ORIGINS, authConfig.token);

void discovery
  .getInstances()
  .then((instances) => {
    if (instances.length > 0) return;

    console.warn(
      `⚠️ No Antigravity Language Server instances discovered. Make sure Antigravity is running.`,
    );
  })
  .catch((err) => {
    console.warn(`⚠️ Initial discovery failed: ${(err as Error).message}`);
  });

server.listen(PORT, HOST, () => {
  console.log(`✅ Porta proxy listening on ${listenAddress}`);
});
