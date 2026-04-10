/**
 * Response compression middleware.
 *
 * Compresses responses with gzip or deflate based on the
 * Accept-Encoding header. Only compresses responses larger
 * than a minimum threshold to avoid overhead on small payloads.
 */

import type { Context, Next } from "hono";
import { gzipSync, deflateSync } from "node:zlib";

/** Minimum response size in bytes before compression is applied. */
const MIN_SIZE = 1024;

/** Content types eligible for compression. */
const COMPRESSIBLE_TYPES = [
  "application/json",
  "text/",
  "application/javascript",
  "application/xml",
];

function isCompressible(contentType: string | undefined): boolean {
  if (!contentType) return false;
  return COMPRESSIBLE_TYPES.some((t) => contentType.includes(t));
}

export function compressionMiddleware() {
  return async (c: Context, next: Next) => {
    await next();

    // Skip if no body, already encoded, or not compressible
    const encoding = c.res.headers.get("Content-Encoding");
    if (encoding) return;

    const contentType = c.res.headers.get("Content-Type");
    if (!isCompressible(contentType ?? undefined)) return;

    // Read the response body — this "disturbs" the original ReadableStream.
    // We MUST replace c.res with a fresh Response to avoid Node 25
    // "ReadableStream is locked" errors downstream.
    const status = c.res.status;
    const headers = new Headers(c.res.headers);
    const body = await c.res.arrayBuffer();
    const buf = Buffer.from(body);

    if (buf.byteLength < MIN_SIZE) {
      // Too small — not worth compressing, but still need a fresh Response
      c.res = new Response(buf, { status, headers });
      return;
    }

    const accept = c.req.header("Accept-Encoding") ?? "";

    if (accept.includes("gzip")) {
      const compressed = gzipSync(buf);
      headers.set("Content-Encoding", "gzip");
      headers.set("Content-Length", String(compressed.byteLength));
      c.res = new Response(compressed, { status, headers });
    } else if (accept.includes("deflate")) {
      const compressed = deflateSync(buf);
      headers.set("Content-Encoding", "deflate");
      headers.delete("Content-Length");
      c.res = new Response(compressed, { status, headers });
    } else {
      // No compression accepted — still need a fresh Response
      c.res = new Response(buf, { status, headers });
    }
  };
}
