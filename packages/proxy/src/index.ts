/**
 * Porta Proxy Server
 *
 * Hono HTTP server that provides a stable REST API over the
 * Antigravity Language Server's dynamic Connect RPC endpoint.
 */

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
} from "./auth.js";
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
      source: i.source,
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

// ── Start ──

const listenAddress = formatListenAddress(HOST, PORT);

logAuthStatus(authConfig);
startSessionCleanup(authConfig.sessionTtlMs);
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
