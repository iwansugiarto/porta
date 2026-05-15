const API_BASE = import.meta.env.VITE_API_BASE ?? "";

// ── Auth token management ──

const AUTH_TOKEN_KEY = "porta_auth_token";

export function getAuthToken(): string | null {
  try {
    return localStorage.getItem(AUTH_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setAuthToken(token: string): void {
  try {
    localStorage.setItem(AUTH_TOKEN_KEY, token);
  } catch {
    // localStorage not available
  }
}

export function clearAuthToken(): void {
  try {
    localStorage.removeItem(AUTH_TOKEN_KEY);
  } catch {
    // localStorage not available
  }
}

// ── Share session token management (tab-scoped via sessionStorage) ──

const SHARE_TOKEN_KEY = "porta_share_token";
const SHARE_WORKSPACE_KEY = "porta_share_workspace";

export function getShareSessionToken(): string | null {
  try {
    return sessionStorage.getItem(SHARE_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setShareSession(token: string, workspaceUri: string): void {
  try {
    sessionStorage.setItem(SHARE_TOKEN_KEY, token);
    sessionStorage.setItem(SHARE_WORKSPACE_KEY, workspaceUri);
  } catch {
    // sessionStorage not available
  }
}

export function getShareWorkspace(): string | null {
  try {
    return sessionStorage.getItem(SHARE_WORKSPACE_KEY);
  } catch {
    return null;
  }
}

export function clearShareSession(): void {
  try {
    sessionStorage.removeItem(SHARE_TOKEN_KEY);
    sessionStorage.removeItem(SHARE_WORKSPACE_KEY);
  } catch {
    // sessionStorage not available
  }
}

// ── API client ──

function previewBody(text: string): string {
  const singleLine = text.replace(/\s+/g, " ").trim();
  if (singleLine.length <= 120) return singleLine;
  return `${singleLine.slice(0, 117)}...`;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "X-Porta-Request": "1",
    ...((options.headers as Record<string, string>) ?? {}),
  };

  // Attach auth token if available (main auth takes priority over share session)
  const token = getAuthToken() || getShareSessionToken();
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  });

  if (res.status === 401) {
    // Dispatch a global event so the app can redirect to login
    window.dispatchEvent(new CustomEvent("porta:auth-required"));
    throw new Error("Authentication required");
  }

  if (!res.ok) {
    const body = await res.text();
    let msg: string;
    try {
      msg = JSON.parse(body).error ?? body;
    } catch {
      msg = body;
    }
    throw new Error(`API ${res.status}: ${msg}`);
  }

  const contentType = res.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("json")) {
    const body = previewBody(await res.text());
    throw new Error(
      `API returned non-JSON for ${path}: ${body || "<empty response>"}`,
    );
  }

  return res.json();
}

export const api = {
  health: () => request<import("../types").HealthResponse>("/api/health"),

  /** Check if auth is required and if current token is valid. */
  authCheck: () =>
    request<{ authRequired: boolean; authenticated: boolean }>(
      "/api/auth/check",
    ),

  /** Logout — invalidate current session token. */
  logout: async () => {
    try {
      await request<{ ok: boolean }>("/api/auth/logout", { method: "POST" });
    } catch {
      // Ignore errors (token may already be invalid)
    } finally {
      clearAuthToken();
    }
  },

  conversations: () =>
    request<import("../types").ConversationsResponse>("/api/conversations"),

  getConversation: (cascadeId: string) =>
    request<import("../types").ConversationDetail>(
      `/api/conversations/${cascadeId}`,
    ),

  /** Fetch steps with optional limit. Returns { steps, offset, stepCount? }. */
  getSteps: (cascadeId: string, offset = 0, limit?: number, tail?: number) => {
    const params = new URLSearchParams({ offset: String(offset) });
    if (limit !== undefined) params.set("limit", String(limit));
    if (tail !== undefined) params.set("tail", String(tail));
    return request<import("../types").StepsPageResponse>(
      `/api/conversations/${cascadeId}/steps?${params}`,
    );
  },

  getWorkspaces: () =>
    request<{
      workspaceInfos?: { workspaceUri: string; gitRootUri?: string }[];
    }>("/api/workspaces"),

  startConversation: (workspaceUri?: string, fileAccessGranted = false) =>
    request<{ cascadeId: string }>("/api/conversations", {
      method: "POST",
      body: JSON.stringify({
        ...(workspaceUri ? { workspaceFolderAbsoluteUri: workspaceUri } : {}),
        fileAccessGranted,
      }),
    }),

  sendMessage: (
    cascadeId: string,
    items: unknown[],
    clientMessageId?: string,
    model?: string,
    media?: Array<{ mimeType: string; inlineData: string }>,
    plannerType?: string,
    fileAccessGranted = false,
  ) =>
    request(`/api/conversations/${cascadeId}/messages`, {
      method: "POST",
      body: JSON.stringify({
        items,
        clientMessageId,
        model,
        media,
        plannerType,
        fileAccessGranted,
      }),
    }),

  stop: (cascadeId: string) =>
    request(`/api/conversations/${cascadeId}/stop`, { method: "POST" }),

  filePermission: (
    cascadeId: string,
    trajectoryId: string,
    stepIndex: number,
    allow: boolean,
    scope: number,
    absolutePathUri: string,
  ) =>
    request(`/api/conversations/${cascadeId}/file-permission`, {
      method: "POST",
      body: JSON.stringify({
        trajectoryId,
        stepIndex,
        allow,
        scope,
        absolutePathUri,
      }),
    }),

  commandAction: (
    cascadeId: string,
    trajectoryId: string,
    stepIndex: number,
    approved: boolean,
  ) =>
    request(`/api/conversations/${cascadeId}/command-action`, {
      method: "POST",
      body: JSON.stringify({
        trajectoryId,
        stepIndex,
        approved,
      }),
    }),

  revert: (cascadeId: string, stepIndex: number, model?: string) =>
    request(`/api/conversations/${cascadeId}/revert`, {
      method: "POST",
      body: JSON.stringify({ stepIndex, model }),
    }),

  deleteConversation: (cascadeId: string) =>
    request(`/api/conversations/${cascadeId}`, { method: "DELETE" }),

  models: () =>
    request<{
      clientModelConfigs: Array<{
        label: string;
        modelOrAlias: { model: string };
        supportsImages: boolean;
        isRecommended: boolean;
        quotaInfo?: { remainingFraction: number };
      }>;
      defaultOverrideModelConfig?: { modelOrAlias: { model: string } };
    }>("/api/models"),

  rpc: (method: string, body: Record<string, unknown> = {}) =>
    request(`/api/rpc/${method}`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  search: (query: string) =>
    request<{
      query: string;
      results: {
        id: string;
        title: string;
        snippets: string[];
        matchCount: number;
      }[];
      totalConversations: number;
      elapsedMs: number;
    }>(`/api/search?q=${encodeURIComponent(query)}`),

  // ── Share API ──

  /** Get share link info (public, no auth needed). */
  shareInfo: (shareToken: string) =>
    request<{
      shareToken: string;
      workspaceUri: string;
      workspaceName: string;
      label?: string;
    }>(`/api/share/info/${shareToken}`),

  /** Authenticate with share token + PIN. */
  shareAuth: (shareToken: string, pin: string) =>
    fetch(`${API_BASE}/api/share/auth`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ shareToken, pin }),
    }).then(async (res) => {
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error ?? "Authentication failed");
      }
      return res.json() as Promise<{
        token: string;
        workspaceUri: string;
        workspaceName: string;
      }>;
    }),

  /** Create a share link (requires full auth). */
  createShare: (workspaceUri: string, pin: string, label?: string) =>
    request<{
      shareToken: string;
      workspaceUri: string;
      label?: string;
      createdAt: string;
      enabled: boolean;
    }>("/api/share/create", {
      method: "POST",
      body: JSON.stringify({ workspaceUri, pin, label }),
    }),

  /** List all share links (requires full auth). */
  listShares: (workspaceUri?: string) => {
    const params = workspaceUri
      ? `?workspaceUri=${encodeURIComponent(workspaceUri)}`
      : "";
    return request<{
      shares: {
        shareToken: string;
        workspaceUri: string;
        label?: string;
        createdAt: string;
        enabled: boolean;
      }[];
    }>(`/api/share/list${params}`);
  },

  /** Revoke a share link (requires full auth). */
  revokeShare: (shareToken: string) =>
    request<{ ok: boolean }>("/api/share/revoke", {
      method: "POST",
      body: JSON.stringify({ shareToken }),
    }),

  /** Re-enable a share link (requires full auth). */
  enableShare: (shareToken: string) =>
    request<{ ok: boolean }>("/api/share/enable", {
      method: "POST",
      body: JSON.stringify({ shareToken }),
    }),

  /** Delete a share link permanently (requires full auth). */
  deleteShareLink: (shareToken: string) =>
    request<{ ok: boolean }>(`/api/share/${shareToken}`, {
      method: "DELETE",
    }),
};
