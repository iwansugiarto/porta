import { useState, useMemo } from "react";
import {
  IconPackage,
  IconFile,
  IconPencil,
  IconDownload,
  IconExternalLink,
} from "./Icons";
import type { TrajectoryStep } from "../types";

interface ArtifactFile {
  uri: string;
  name: string;
  isNew: boolean;
}

/** Build a URL to the /api/files endpoint for a given file URI */
function fileApiUrl(fileUri: string, mode?: "download"): string {
  const base = import.meta.env.VITE_API_BASE ?? "";
  const params = new URLSearchParams({ uri: fileUri });
  if (mode) params.set("mode", mode);
  return `${base}/api/files?${params}`;
}

/** Extract file basename from a URI or path */
function basename(uriOrPath: string): string {
  const cleaned = uriOrPath.replace(/^file:\/\//, "");
  return cleaned.split("/").pop() ?? cleaned;
}

/** Extract unique files from code action steps */
export function extractArtifactFiles(steps: TrajectoryStep[]): ArtifactFile[] {
  const seen = new Map<string, ArtifactFile>();

  for (const step of steps) {
    const ca = step.codeAction;
    if (!ca?.actionResult?.edit?.absoluteUri) continue;

    const uri = ca.actionResult.edit.absoluteUri;
    if (seen.has(uri)) continue;

    const toolName = step.metadata?.toolCall?.name ?? "";
    const isNew = toolName === "write_to_file" || ca.actionResult.edit.createFile === true;

    seen.set(uri, {
      uri,
      name: basename(uri),
      isNew,
    });
  }

  return Array.from(seen.values());
}

interface Props {
  files: ArtifactFile[];
}

export function ArtifactsPanel({ files }: Props) {
  const [expanded, setExpanded] = useState(false);

  const { newFiles, modifiedFiles } = useMemo(() => {
    const n: ArtifactFile[] = [];
    const m: ArtifactFile[] = [];
    for (const f of files) {
      if (f.isNew) n.push(f);
      else m.push(f);
    }
    return { newFiles: n, modifiedFiles: m };
  }, [files]);

  if (files.length === 0) return null;

  return (
    <div className="artifacts-panel">
      <button
        className="artifacts-header"
        onClick={() => setExpanded((v) => !v)}
      >
        <span className="artifacts-icon">
          <IconPackage size={14} />
        </span>
        <span className="artifacts-title">
          Artifacts
        </span>
        <span className="artifacts-count">{files.length}</span>
        <span className={`artifacts-chevron ${expanded ? "open" : ""}`}>
          ▾
        </span>
      </button>

      {expanded && (
        <div className="artifacts-body">
          {newFiles.length > 0 && (
            <div className="artifacts-group">
              <div className="artifacts-group-label">Created</div>
              {newFiles.map((f) => (
                <div key={f.uri} className="artifacts-file">
                  <span className="artifacts-file-icon new">
                    <IconFile size={12} />
                  </span>
                  <span className="artifacts-file-name" title={f.uri.replace("file://", "")}>
                    {f.name}
                  </span>
                  <span className="artifacts-file-actions">
                    <a
                      className="artifacts-file-btn"
                      href={fileApiUrl(f.uri)}
                      target="_blank"
                      rel="noopener noreferrer"
                      title="Open file"
                    >
                      <IconExternalLink size={11} />
                    </a>
                    <a
                      className="artifacts-file-btn"
                      href={fileApiUrl(f.uri, "download")}
                      title="Download file"
                    >
                      <IconDownload size={11} />
                    </a>
                  </span>
                </div>
              ))}
            </div>
          )}

          {modifiedFiles.length > 0 && (
            <div className="artifacts-group">
              <div className="artifacts-group-label">Modified</div>
              {modifiedFiles.map((f) => (
                <div key={f.uri} className="artifacts-file">
                  <span className="artifacts-file-icon modified">
                    <IconPencil size={12} />
                  </span>
                  <span className="artifacts-file-name" title={f.uri.replace("file://", "")}>
                    {f.name}
                  </span>
                  <span className="artifacts-file-actions">
                    <a
                      className="artifacts-file-btn"
                      href={fileApiUrl(f.uri)}
                      target="_blank"
                      rel="noopener noreferrer"
                      title="Open file"
                    >
                      <IconExternalLink size={11} />
                    </a>
                    <a
                      className="artifacts-file-btn"
                      href={fileApiUrl(f.uri, "download")}
                      title="Download file"
                    >
                      <IconDownload size={11} />
                    </a>
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
