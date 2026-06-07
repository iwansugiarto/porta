/**
 * /api/workspaces route
 */

import type { Hono } from "hono";
import { discovery, rpc } from "../routing.js";
import { handleRPCError } from "../errors.js";

export function registerWorkspaceRoutes(app: Hono): void {
  app.get("/api/workspaces", async (c) => {
    try {
      const instances = await discovery.getInstances();
      const allInfos: { workspaceUri: string; gitRootUri?: string }[] = [];
      let homeDirPath = "";
      let homeDirUri = "";

      await Promise.allSettled(
        instances.map(async (inst) => {
          try {
            const data = (await rpc.call("GetWorkspaceInfos", {}, inst)) as {
              homeDirPath?: string;
              homeDirUri?: string;
              workspaceInfos?: { workspaceUri: string; gitRootUri?: string }[];
            };
            if (data.homeDirPath) homeDirPath = data.homeDirPath;
            if (data.homeDirUri) homeDirUri = data.homeDirUri;
            if (data.workspaceInfos) allInfos.push(...data.workspaceInfos);
          } catch {
            // Skip unreachable instances
          }
        }),
      );

      // Enrich: also extract unique workspace URIs from conversation metadata.
      // This surfaces workspaces (e.g. focuscast, RSFlaskApp) that don't have
      // a running LS but do have historical conversations.
      const knownUris = new Set(allInfos.map((i) => i.workspaceUri));

      await Promise.allSettled(
        instances.map(async (inst) => {
          try {
            const data = await rpc.call<{
              trajectorySummaries: Record<
                string,
                {
                  workspaces?: {
                    workspaceFolderAbsoluteUri?: string;
                    repository?: { computedName?: string };
                  }[];
                }
              >;
            }>("GetAllCascadeTrajectories", {}, inst);
            const summaries = data.trajectorySummaries ?? {};
            for (const summary of Object.values(summaries)) {
              const wsUri =
                summary.workspaces?.[0]?.workspaceFolderAbsoluteUri;
              if (wsUri && !knownUris.has(wsUri)) {
                // Skip home directory — it's too broad to be a useful workspace
                if (homeDirUri && wsUri === homeDirUri) continue;
                knownUris.add(wsUri);
                allInfos.push({ workspaceUri: wsUri });
              }
            }
          } catch {
            // Skip unreachable instances
          }
        }),
      );

      // Sort by workspace name (last path segment) for consistent ordering
      allInfos.sort((a, b) => {
        const nameA = a.workspaceUri.split("/").pop() ?? "";
        const nameB = b.workspaceUri.split("/").pop() ?? "";
        return nameA.localeCompare(nameB);
      });

      return c.json({ homeDirPath, homeDirUri, workspaceInfos: allInfos });
    } catch (err) {
      return handleRPCError(c, err);
    }
  });
}

