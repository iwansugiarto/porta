/**
 * Connect RPC relay to Language Server.
 *
 * Translates REST API calls into Connect RPC (JSON over HTTP/S) calls
 * to the Antigravity Language Server. Uses node:https directly to
 * handle self-signed certificates reliably across Node versions.
 */

import { request as httpRequest } from "node:http";
import { request as httpsRequest, type RequestOptions } from "node:https";
import { LSDiscovery, type LSInstance } from "./discovery.js";
import {
  forgetTransportHint,
  getTransportOrder,
  rememberSuccessfulTransport,
  type TransportProtocol,
} from "./transport-hints.js";

const SERVICE_PREFIX = "exa.language_server_pb.LanguageServerService";

/** Maximum response body size (100 MB) — reject before JSON.parse to avoid OOM. */
const MAX_RESPONSE_BYTES = 100 * 1024 * 1024;

/** Errors indicating we tried TLS on a plaintext port. */
export function isTlsProtocolError(err: unknown): boolean {
  if (!(err instanceof Error)) return false;
  const code = (err as NodeJS.ErrnoException).code;
  return (
    code === "EPROTO" ||
    code === "ECONNRESET" ||
    err.message.includes("packet length too long") ||
    err.message.includes("wrong version number")
  );
}

class TransportFailure extends Error {
  public readonly attempted: TransportProtocol[];

  constructor(
    cause: unknown,
    attempted: TransportProtocol[],
  ) {
    super(String(cause), { cause });
    this.name = "TransportFailure";
    this.attempted = attempted;
  }
}

export class RPCClient {
  constructor(private readonly discovery: LSDiscovery) {}

  /**
   * Call a Connect RPC method on the Language Server.
   * Tries the last successful transport first, then the alternate transport.
   */
  async call<T = unknown>(
    method: string,
    body: Record<string, unknown> = {},
    instance?: LSInstance,
  ): Promise<T> {
    const ls = instance ?? (await this.discovery.getInstance());
    if (!ls) {
      throw new RPCError("No Language Server instance found", "unavailable");
    }

    try {
      const { value } = await this.withTransportFallback(ls, (protocol) =>
        protocol === "https"
          ? this.httpsPost(ls, method, body)
          : this.httpPost(ls, method, body),
      );

      if (value.statusCode < 200 || value.statusCode >= 300) {
        let parsed: { code?: string; message?: string } = {};
        try {
          parsed = JSON.parse(value.body);
        } catch {
          // Not JSON
        }

        if (
          value.statusCode === 401 ||
          value.statusCode === 403 ||
          parsed.code === "unauthenticated"
        ) {
          this.discovery.invalidate();
          throw new RPCError(
            parsed.message ??
              "Authentication failed - CSRF token may have changed",
            "unauthenticated",
          );
        }

        throw new RPCError(
          parsed.message ??
            `RPC ${method} failed with status ${value.statusCode}`,
          parsed.code ?? "unknown",
        );
      }

      return JSON.parse(value.body) as T;
    } catch (err) {
      if (err instanceof RPCError) {
        throw err;
      }

      forgetTransportHint(ls);
      this.discovery.invalidate();
      const failure =
        err instanceof TransportFailure
          ? err
          : new TransportFailure(err, [getTransportOrder(ls)[0]]);
      const attemptLabel =
        failure.attempted.length > 1
          ? ` (tried ${failure.attempted.map((p) => p.toUpperCase()).join("+")})`
          : "";
      throw new RPCError(
        `Failed to connect to Language Server on port ${ls.httpsPort}${attemptLabel}: ${failure.cause}`,
        "unavailable",
      );
    }
  }

  /**
   * Call a streaming RPC method, returning a readable node stream.
   * The caller is responsible for piping or consuming the stream.
   */
  async streamRaw(
    method: string,
    body: Record<string, unknown> = {},
    instance?: LSInstance,
  ): Promise<NodeJS.ReadableStream> {
    const ls = instance ?? (await this.discovery.getInstance());
    if (!ls) {
      throw new RPCError("No Language Server instance found", "unavailable");
    }

    try {
      const { value } = await this.withTransportFallback(ls, (protocol) =>
        this._streamRequest(
          protocol === "https" ? httpsRequest : httpRequest,
          ls,
          method,
          body,
          protocol === "https",
        ),
      );
      return value;
    } catch (err) {
      if (err instanceof TransportFailure) {
        throw err.cause;
      }
      throw err;
    }
  }

  private _streamRequest(
    requestFn: typeof httpsRequest | typeof httpRequest,
    ls: LSInstance,
    method: string,
    body: Record<string, unknown>,
    useTls: boolean,
  ): Promise<NodeJS.ReadableStream> {
    return new Promise((resolve, reject) => {
      const payload = JSON.stringify(body);
      const opts: RequestOptions = {
        hostname: "127.0.0.1",
        port: ls.httpsPort,
        path: `/${SERVICE_PREFIX}/${method}`,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-codeium-csrf-token": ls.csrfToken,
          "Content-Length": Buffer.byteLength(payload),
        },
        ...(useTls ? { rejectUnauthorized: false } : {}),
      };

      const req = requestFn(opts, (res) => {
        if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) {
          let responseBody = "";
          res.on("data", (chunk: Buffer) => {
            responseBody += chunk.toString();
          });
          res.on("end", () => {
            reject(
              new RPCError(`Stream ${method} failed: ${responseBody}`, "unknown"),
            );
          });
          return;
        }
        resolve(res);
      });

      req.on("error", reject);
      req.write(payload);
      req.end();
    });
  }

  private async withTransportFallback<T>(
    ls: LSInstance,
    attempt: (protocol: TransportProtocol) => Promise<T>,
  ): Promise<{ value: T; protocol: TransportProtocol }> {
    const order = getTransportOrder(ls);
    const attempted: TransportProtocol[] = [];
    let lastError: unknown;

    for (let index = 0; index < order.length; index++) {
      const protocol = order[index];
      attempted.push(protocol);
      try {
        const value = await attempt(protocol);
        rememberSuccessfulTransport(ls, protocol);
        return { value, protocol };
      } catch (err) {
        lastError = err;
        if (index === order.length - 1) break;
        // Transport hints only reorder the probe. We only switch protocols
        // when the failure still looks like a protocol mismatch.
        if (isTlsProtocolError(err)) continue;
        break;
      }
    }

    throw new TransportFailure(lastError, attempted);
  }

  private httpsPost(
    ls: LSInstance,
    method: string,
    body: Record<string, unknown>,
  ): Promise<{ statusCode: number; body: string }> {
    return new Promise((resolve, reject) => {
      const payload = JSON.stringify(body);

      const opts: RequestOptions = {
        hostname: "127.0.0.1",
        port: ls.httpsPort,
        path: `/${SERVICE_PREFIX}/${method}`,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-codeium-csrf-token": ls.csrfToken,
          "Content-Length": Buffer.byteLength(payload),
        },
        rejectUnauthorized: false,
      };

      const req = httpsRequest(opts, (res) => {
        const chunks: Buffer[] = [];
        let totalBytes = 0;
        res.on("data", (chunk: Buffer) => {
          totalBytes += chunk.length;
          if (totalBytes > MAX_RESPONSE_BYTES) {
            res.destroy();
            reject(new RPCError(
              `Response too large (${(totalBytes / 1024 / 1024).toFixed(1)} MB) for ${method} — aborting to prevent OOM`,
              "resource_exhausted",
            ));
            return;
          }
          chunks.push(chunk);
        });
        res.on("end", () => {
          const data = Buffer.concat(chunks).toString("utf-8");
          resolve({ statusCode: res.statusCode ?? 500, body: data });
        });
      });

      req.on("error", reject);
      req.write(payload);
      req.end();
    });
  }

  private httpPost(
    ls: LSInstance,
    method: string,
    body: Record<string, unknown>,
  ): Promise<{ statusCode: number; body: string }> {
    return new Promise((resolve, reject) => {
      const payload = JSON.stringify(body);

      const opts = {
        hostname: "127.0.0.1",
        port: ls.httpsPort,
        path: `/${SERVICE_PREFIX}/${method}`,
        method: "POST" as const,
        headers: {
          "Content-Type": "application/json",
          "x-codeium-csrf-token": ls.csrfToken,
          "Content-Length": Buffer.byteLength(payload),
        },
      };

      const req = httpRequest(opts, (res) => {
        const chunks: Buffer[] = [];
        let totalBytes = 0;
        res.on("data", (chunk: Buffer) => {
          totalBytes += chunk.length;
          if (totalBytes > MAX_RESPONSE_BYTES) {
            res.destroy();
            reject(new RPCError(
              `Response too large (${(totalBytes / 1024 / 1024).toFixed(1)} MB) for ${method} — aborting to prevent OOM`,
              "resource_exhausted",
            ));
            return;
          }
          chunks.push(chunk);
        });
        res.on("end", () => {
          const data = Buffer.concat(chunks).toString("utf-8");
          resolve({ statusCode: res.statusCode ?? 500, body: data });
        });
      });

      req.on("error", reject);
      req.write(payload);
      req.end();
    });
  }
}

export class RPCError extends Error {
  constructor(
    message: string,
    public readonly code: string,
  ) {
    super(message);
    this.name = "RPCError";
  }
}
