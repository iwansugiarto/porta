/**
 * Shared metadata and disk-scanning utilities for the proxy.
 */

import { readdir, stat } from "node:fs/promises";
import { join } from "node:path";
import { homedir } from "node:os";

const CONVERSATIONS_DIR = join(
  homedir(),
  ".gemini",
  "antigravity",
  "conversations",
);

/**
 * Whether the server-side auto-approve setting is enabled.
 *
 * Reads `PORTA_AUTO_APPROVE` from the environment (loaded via `--env-file`).
 *   - `true` (default) — the LS auto-approves commands and file access.
 *   - `false` — the LS enters WAITING status for unsafe commands and
 *     file access outside the workspace, enabling approval UI in the frontend.
 */
export function isAutoApproveEnabled(): boolean {
  const val = process.env.PORTA_AUTO_APPROVE;
  if (val === undefined || val === "") return true; // Default: on
  return val.toLowerCase() !== "false" && val !== "0";
}

/**
 * Build the metadata object that the LS requires on write RPCs.
 * Mirrors what the VS Code extension sends via MetadataProvider.
 *
 * When `PORTA_AUTO_APPROVE=false`, the metadata omits `allWorkspaceTrustGranted`
 * so the LS pauses for user approval on unsafe operations.
 */
export async function getMetadata(
  fileAccessGranted = false,
): Promise<Record<string, unknown>> {
  const meta: Record<string, unknown> = {
    ideName: "porta",
    ideVersion: "0.1.0",
    extensionVersion: "0.1.0",
  };
  const autoApprove = isAutoApproveEnabled();
  if (fileAccessGranted) {
    meta.allowFileAccess = true;
    // Only auto-grant full workspace trust when env allows it
    if (autoApprove) {
      meta.allWorkspaceTrustGranted = true;
    }
  }
  return meta;
}

/** Scan disk for .pb conversation files not loaded in memory */
export async function scanDiskConversations(): Promise<
  { id: string; mtime: string }[]
> {
  try {
    const files = await readdir(CONVERSATIONS_DIR);
    const results: { id: string; mtime: string }[] = [];
    for (const file of files) {
      if (!file.endsWith(".pb")) continue;
      const id = file.replace(".pb", "");
      try {
        const s = await stat(join(CONVERSATIONS_DIR, file));
        results.push({ id, mtime: s.mtime.toISOString() });
      } catch {
        results.push({ id, mtime: new Date().toISOString() });
      }
    }
    return results;
  } catch {
    return [];
  }
}
