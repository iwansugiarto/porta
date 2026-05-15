/**
 * HTTP + WebSocket client for communicating with the Porta proxy.
 *
 * Wraps the proxy REST API and WebSocket streaming endpoint.
 * All requests carry the auth token and CSRF header.
 */

import { request } from "node:http";
import type { IncomingMessage } from "node:http";
import WebSocket from "ws";

export interface ConversationSummary {
  id: string;
  summary: string;
  stepCount: number;
  status: string;
  lastModifiedTime?: string;
  workspaces?: { workspaceFolderAbsoluteUri?: string }[];
}

export interface StepData {
  type: string;
  offset: number;
  steps: Record<string, unknown>[];
}

export interface StatusData {
  type: "status";
  running: boolean;
}

export interface ReadyData {
  type: "ready";
  stepCount: number;
}

export type WSMessage = StepData | StatusData | ReadyData;

export type StepCallback = (msg: WSMessage) => void;

export interface WSConnection {
  ws: WebSocket;
  close: () => void;
}

export class PortaClient {
  private readonly baseUrl: string;
  private readonly authToken: string | undefined;
  /** Cached default model resolved from /api/models. */
  private defaultModel: string | undefined;

  constructor(baseUrl: string, authToken?: string) {
    this.baseUrl = baseUrl;
    this.authToken = authToken;
  }

  /**
   * Resolve the default model from the LS.
   * Cached after the first successful call.
   */
  async getDefaultModel(): Promise<string | undefined> {
    if (this.defaultModel) return this.defaultModel;
    try {
      const data = await this.get<Record<string, unknown>>("/api/models");
      // Check for override default first, then fall back to first recommended model
      const override = data.defaultOverrideModelConfig as
        | { modelOrAlias?: { model?: string } }
        | undefined;
      if (override?.modelOrAlias?.model) {
        this.defaultModel = override.modelOrAlias.model;
        return this.defaultModel;
      }
      const models = (data.clientModelConfigs ?? []) as Record<string, unknown>[];
      const recommended = models.find((m) => m.isRecommended);
      const first = recommended ?? models[0];
      if (first) {
        const alias = first.modelOrAlias as { model?: string } | undefined;
        this.defaultModel = alias?.model ?? undefined;
      }
      return this.defaultModel;
    } catch {
      return undefined;
    }
  }

  // ── REST API ──

  async listConversations(): Promise<ConversationSummary[]> {
    const data = await this.get<{
      trajectorySummaries: Record<string, Record<string, unknown>>;
    }>("/api/conversations");

    const summaries = data.trajectorySummaries ?? {};
    return Object.entries(summaries)
      .map(([id, s]) => ({
        id,
        summary: (s.summary as string) ?? id.slice(0, 8),
        stepCount: (s.stepCount as number) ?? 0,
        status: (s.status as string) ?? "unknown",
        lastModifiedTime: s.lastModifiedTime as string | undefined,
        workspaces: s.workspaces as
          | { workspaceFolderAbsoluteUri?: string }[]
          | undefined,
      }))
      .sort(
        (a, b) =>
          new Date(b.lastModifiedTime ?? 0).getTime() -
          new Date(a.lastModifiedTime ?? 0).getTime(),
      );
  }

  async createConversation(
    workspaceUri?: string,
  ): Promise<{ cascadeId: string }> {
    const body: Record<string, unknown> = { fileAccessGranted: true };
    if (workspaceUri) {
      body.workspaceFolderAbsoluteUri = workspaceUri;
    }
    return this.post<{ cascadeId: string }>("/api/conversations", body);
  }

  /**
   * Force-load a conversation from disk into the LS.
   * Uses a read-only GET which triggers the try-all fallback,
   * causing the LS to load the .pb file and learn routing affinity.
   */
  async recallConversation(cascadeId: string): Promise<void> {
    await this.get(`/api/conversations/${cascadeId}`);
  }

  async sendMessage(
    cascadeId: string,
    text: string,
    model?: string,
    media?: { mimeType: string; data: string }[],
    plannerType?: string,
  ): Promise<void> {
    // Always include a model — LS won't run the agent without one
    const resolvedModel = model ?? (await this.getDefaultModel());
    const body: Record<string, unknown> = {
      items: [{ text }],
      fileAccessGranted: true,
    };
    if (resolvedModel) body.model = resolvedModel;
    if (media && media.length > 0) body.media = media;
    if (plannerType) body.plannerType = plannerType;
    await this.post(`/api/conversations/${cascadeId}/messages`, body);
  }

  async stopConversation(cascadeId: string): Promise<void> {
    await this.post(`/api/conversations/${cascadeId}/stop`, {});
  }

  async approveCommand(
    cascadeId: string,
    trajectoryId: string,
    stepIndex: number,
  ): Promise<void> {
    await this.post(`/api/conversations/${cascadeId}/command-action`, {
      trajectoryId,
      stepIndex,
      approved: true,
    });
  }

  async rejectCommand(
    cascadeId: string,
    trajectoryId: string,
    stepIndex: number,
  ): Promise<void> {
    await this.post(`/api/conversations/${cascadeId}/command-action`, {
      trajectoryId,
      stepIndex,
      approved: false,
    });
  }

  async approveFileAccess(
    cascadeId: string,
    trajectoryId: string,
    stepIndex: number,
    absolutePathUri: string,
  ): Promise<void> {
    await this.post(`/api/conversations/${cascadeId}/file-permission`, {
      trajectoryId,
      stepIndex,
      allow: true,
      scope: 2, // CONVERSATION scope
      absolutePathUri,
    });
  }

  async rejectFileAccess(
    cascadeId: string,
    trajectoryId: string,
    stepIndex: number,
    absolutePathUri: string,
  ): Promise<void> {
    await this.post(`/api/conversations/${cascadeId}/file-permission`, {
      trajectoryId,
      stepIndex,
      allow: false,
      scope: 0,
      absolutePathUri,
    });
  }

  async getHealth(): Promise<Record<string, unknown>> {
    return this.get("/api/health");
  }

  async listWorkspaces(): Promise<{ workspaceUri: string; gitRootUri?: string }[]> {
    const data = await this.get<{
      workspaceInfos?: { workspaceUri: string; gitRootUri?: string }[];
    }>("/api/workspaces");
    return data.workspaceInfos ?? [];
  }

  async setAutoApprove(enabled: boolean): Promise<boolean> {
    const data = await this.post<{ enabled: boolean }>("/api/config/auto-approve", { enabled });
    return data.enabled;
  }

  async listModels(): Promise<{ name: string; displayName: string }[]> {
    const data = await this.get<Record<string, unknown>>("/api/models");
    // Extract model data from the LS response (field is clientModelConfigs)
    const models = (data.clientModelConfigs ?? data.models ?? data.modelConfigs ?? []) as Record<string, unknown>[];
    return models.map((m) => {
      const modelOrAlias = m.modelOrAlias as Record<string, unknown> | undefined;
      const modelId = (modelOrAlias?.model as string) ?? (m.model as string) ?? (m.name as string) ?? "unknown";
      const label = (m.label as string) ?? modelId;
      return { name: modelId, displayName: label };
    });
  }

  /**
   * Get the latest steps from a conversation (for /latest command).
   * Returns the raw conversation detail from the proxy.
   */
  async getConversationDetail(
    cascadeId: string,
  ): Promise<Record<string, unknown>> {
    return this.get(`/api/conversations/${cascadeId}`);
  }

  /** Fetch conversation steps for export. */
  async getSteps(
    cascadeId: string,
    offset = 0,
    count = 500,
  ): Promise<Record<string, unknown>[]> {
    const data = await this.get<{ steps?: Record<string, unknown>[] }>(
      `/api/conversations/${cascadeId}/steps?offset=${offset}&count=${count}`,
    );
    return data.steps ?? [];
  }

  /** Delete a conversation. Mirrors web's DELETE endpoint. */
  async deleteConversation(cascadeId: string): Promise<void> {
    await this.httpDelete(`/api/conversations/${cascadeId}`);
  }

  /**
   * Execute a shell command on the proxy host.
   * Runs with a timeout and size cap for safety.
   */
  async runShellCommand(
    command: string,
    timeoutMs = 30_000,
  ): Promise<{ stdout: string; stderr: string; exitCode: number }> {
    const { exec } = await import("node:child_process");
    return new Promise((resolve) => {
      exec(
        command,
        { timeout: timeoutMs, maxBuffer: 1024 * 1024 * 2 },
        (error, stdout, stderr) => {
          resolve({
            stdout: stdout ?? "",
            stderr: stderr ?? "",
            exitCode: error?.code ?? 0,
          });
        },
      );
    });
  }

  // ── WebSocket ──

  connectWebSocket(cascadeId: string, onMessage: StepCallback): WSConnection {
    const wsUrl = this.baseUrl
      .replace(/^http/, "ws")
      .concat(`/api/conversations/${cascadeId}/ws`);

    const shortId = cascadeId.slice(0, 8);
    console.log(`[ws:${shortId}] connecting to ${wsUrl}`);

    const protocols: string[] = [];
    const headers: Record<string, string> = {};
    if (this.authToken) {
      headers["Authorization"] = `Bearer ${this.authToken}`;
    }

    const ws = new WebSocket(`${wsUrl}?token=${this.authToken ?? ""}`, protocols, {
      headers,
    });

    let msgCount = 0;

    ws.on("open", () => {
      console.log(`[ws:${shortId}] connected`);
    });

    ws.on("message", (raw) => {
      try {
        const msg = JSON.parse(raw.toString()) as WSMessage;
        msgCount++;
        if (msgCount <= 3 || msgCount % 20 === 0) {
          console.log(`[ws:${shortId}] msg #${msgCount} type=${msg.type}`);
        }
        onMessage(msg);
      } catch {
        // Skip non-JSON messages
      }
    });

    ws.on("error", (err) => {
      console.error(`[ws:${shortId}] error: ${err.message}`);
    });

    ws.on("close", (code, reason) => {
      console.log(`[ws:${shortId}] closed code=${code} reason=${reason?.toString() ?? ""} msgs=${msgCount}`);
    });

    return {
      ws,
      close: () => {
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
          ws.close();
        }
      },
    };
  }

  // ── Internal HTTP helpers ──

  private buildHeaders(mutation: boolean): Record<string, string> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
    };
    if (this.authToken) {
      headers["Authorization"] = `Bearer ${this.authToken}`;
    }
    if (mutation) {
      headers["X-Porta-Request"] = "1";
    }
    return headers;
  }

  private get<T>(path: string): Promise<T> {
    return new Promise((resolve, reject) => {
      const url = new URL(path, this.baseUrl);
      const headers = this.buildHeaders(false);

      const req = request(
        {
          hostname: url.hostname,
          port: url.port,
          path: url.pathname + url.search,
          method: "GET",
          headers,
        },
        (res: IncomingMessage) => {
          this.consumeResponse<T>(res, resolve, reject);
        },
      );
      req.on("error", reject);
      req.end();
    });
  }

  private post<T>(path: string, body: Record<string, unknown>): Promise<T> {
    return new Promise((resolve, reject) => {
      const url = new URL(path, this.baseUrl);
      const headers = this.buildHeaders(true);
      const payload = JSON.stringify(body);
      headers["Content-Length"] = String(Buffer.byteLength(payload));

      const req = request(
        {
          hostname: url.hostname,
          port: url.port,
          path: url.pathname + url.search,
          method: "POST",
          headers,
        },
        (res: IncomingMessage) => {
          this.consumeResponse<T>(res, resolve, reject);
        },
      );
      req.on("error", reject);
      req.write(payload);
      req.end();
    });
  }

  private httpDelete<T>(path: string): Promise<T> {
    return new Promise((resolve, reject) => {
      const url = new URL(path, this.baseUrl);
      const headers = this.buildHeaders(true);

      const req = request(
        {
          hostname: url.hostname,
          port: url.port,
          path: url.pathname + url.search,
          method: "DELETE",
          headers,
        },
        (res: IncomingMessage) => {
          this.consumeResponse<T>(res, resolve, reject);
        },
      );
      req.on("error", reject);
      req.end();
    });
  }

  private consumeResponse<T>(
    res: IncomingMessage,
    resolve: (value: T) => void,
    reject: (reason: Error) => void,
  ): void {
    const chunks: Buffer[] = [];
    res.on("data", (chunk: Buffer) => chunks.push(chunk));
    res.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf-8");
      if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) {
        reject(new Error(`HTTP ${res.statusCode}: ${raw}`));
        return;
      }
      try {
        resolve(JSON.parse(raw) as T);
      } catch {
        resolve(raw as unknown as T);
      }
    });
  }
}
