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

    // Read the response body
    const body = await c.res.arrayBuffer();
    if (body.byteLength < MIN_SIZE) {
      // Too small — not worth compressing, re-set the body
      c.res = new Response(body, {
        status: c.res.status,
        headers: c.res.headers,
      });
      return;
    }

    const accept = c.req.header("Accept-Encoding") ?? "";
    const buf = Buffer.from(body);

    if (accept.includes("gzip")) {
      const compressed = gzipSync(buf);
      c.res = new Response(compressed, {
        status: c.res.status,
        headers: c.res.headers,
      });
      c.res.headers.set("Content-Encoding", "gzip");
      c.res.headers.set("Content-Length", String(compressed.byteLength));
      c.res.headers.delete("Content-Length"); // Let runtime handle it
    } else if (accept.includes("deflate")) {
      const compressed = deflateSync(buf);
      c.res = new Response(compressed, {
        status: c.res.status,
        headers: c.res.headers,
      });
      c.res.headers.set("Content-Encoding", "deflate");
      c.res.headers.delete("Content-Length");
    }
  };
}
