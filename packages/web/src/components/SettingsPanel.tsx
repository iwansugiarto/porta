import { useState, useEffect, useCallback } from "react";
import { useParams } from "react-router-dom";
import { IconChevronLeft, IconCheck } from "./Icons";
import { api } from "../api/client";
import { usePwaInstall } from "../hooks/usePwaInstall";
import { useWorkspaces, slugFromUri } from "../hooks/useWorkspaces";
import { useConversations } from "../hooks/useConversations";
import type { ClientSettings } from "../types";
import type { PlannerType } from "./ChatInput";

/** Apply a theme by toggling a data attribute on the root element. */
export function applyTheme(theme: string) {
  const root = document.documentElement;
  if (theme === "system") {
    const prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    root.setAttribute("data-theme", prefersDark ? "dark" : "light");
  } else {
    root.setAttribute("data-theme", theme);
  }
}

// Apply the saved theme on import
applyTheme(localStorage.getItem("porta:theme") ?? "dark");

interface ModelConfig {
  label: string;
  modelOrAlias: { model: string };
  supportsImages: boolean;
  isRecommended: boolean;
  quotaInfo?: { remainingFraction: number };
}

interface HealthData {
  status: string;
  proxy: { port: number; uptime: number };
  languageServers: {
    pid: number;
    httpsPort: number;
    workspaceId?: string;
    source: string;
  }[];
}

function formatUptime(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return `${Math.floor(seconds)}s`;
}

interface Props {
  settings: ClientSettings;
  onUpdate: (patch: Partial<ClientSettings>) => void;
  onBack: () => void;
}

export function SettingsPanel({ settings, onUpdate, onBack }: Props) {
  const [models, setModels] = useState<ModelConfig[]>([]);
  const [fetchError, setFetchError] = useState(false);
  const [savedFlash, setSavedFlash] = useState(false);
  const { canInstall, isInstalled, promptInstall } = usePwaInstall();
  const [health, setHealth] = useState<HealthData | null>(null);

  // Share links state
  const { projectSlug } = useParams<{ projectSlug: string }>();
  const { conversations } = useConversations(60_000);
  const { workspaces, currentWorkspaceUri } = useWorkspaces(
    conversations,
    projectSlug,
  );
  const [shareLinks, setShareLinks] = useState<
    {
      shareToken: string;
      workspaceUri: string;
      label?: string;
      createdAt: string;
      enabled: boolean;
    }[]
  >([]);
  const [newPin, setNewPin] = useState("");
  const [newLabel, setNewLabel] = useState("");
  const [shareCreating, setShareCreating] = useState(false);
  const [shareError, setShareError] = useState<string | null>(null);
  const [copiedToken, setCopiedToken] = useState<string | null>(null);

  const fetchModels = useCallback(async (retries = 3) => {
    for (let i = 0; i < retries; i++) {
      try {
        const data = await api.models();
        setModels(data.clientModelConfigs ?? []);
        setFetchError(false);
        return;
      } catch {
        if (i < retries - 1) {
          await new Promise((r) => setTimeout(r, 1000 * (i + 1)));
        }
      }
    }
    setFetchError(true);
  }, []);

  useEffect(() => {
    fetchModels();
    api.health().then(setHealth).catch(() => {});
    // Fetch share links
    api.listShares().then((data) => setShareLinks(data.shares)).catch(() => {});
  }, [fetchModels]);

  const flashSaved = useCallback(() => {
    setSavedFlash(true);
    const timer = setTimeout(() => setSavedFlash(false), 1500);
    return () => clearTimeout(timer);
  }, []);

  const handleModelChange = useCallback(
    (modelId: string) => {
      const value = modelId === "__none__" ? null : modelId;
      onUpdate({ defaultModel: value });
      flashSaved();
    },
    [onUpdate, flashSaved],
  );

  const handlePlannerChange = useCallback(
    (value: string) => {
      onUpdate({ defaultPlannerType: value as PlannerType });
      flashSaved();
    },
    [onUpdate, flashSaved],
  );

  const handleReset = useCallback(() => {
    onUpdate({ defaultModel: null, defaultPlannerType: "conversational" });
    flashSaved();
  }, [onUpdate, flashSaved]);

  // Share link management
  const handleCreateShare = useCallback(async () => {
    if (!currentWorkspaceUri || !newPin.trim()) return;
    if (newPin.trim().length < 4) {
      setShareError("PIN must be at least 4 characters");
      return;
    }
    setShareCreating(true);
    setShareError(null);
    try {
      const share = await api.createShare(
        currentWorkspaceUri,
        newPin.trim(),
        newLabel.trim() || undefined,
      );
      setShareLinks((prev) => [...prev, share]);
      setNewPin("");
      setNewLabel("");
      flashSaved();
    } catch (err) {
      setShareError(
        err instanceof Error ? err.message : "Failed to create share link",
      );
    } finally {
      setShareCreating(false);
    }
  }, [currentWorkspaceUri, newPin, newLabel, flashSaved]);

  const handleToggleShare = useCallback(async (shareToken: string, enabled: boolean) => {
    try {
      if (enabled) {
        await api.enableShare(shareToken);
      } else {
        await api.revokeShare(shareToken);
      }
      setShareLinks((prev) =>
        prev.map((s) =>
          s.shareToken === shareToken ? { ...s, enabled } : s,
        ),
      );
    } catch {
      // Refresh from server
      api.listShares().then((data) => setShareLinks(data.shares)).catch(() => {});
    }
  }, []);

  const handleDeleteShare = useCallback(async (shareToken: string) => {
    try {
      await api.deleteShareLink(shareToken);
      setShareLinks((prev) => prev.filter((s) => s.shareToken !== shareToken));
    } catch {
      api.listShares().then((data) => setShareLinks(data.shares)).catch(() => {});
    }
  }, []);

  const copyShareUrl = useCallback((shareToken: string) => {
    const url = `${window.location.origin}/s/${shareToken}`;
    navigator.clipboard.writeText(url).then(() => {
      setCopiedToken(shareToken);
      setTimeout(() => setCopiedToken(null), 2000);
    });
  }, []);

  return (
    <div className="settings-panel">
      <div className="settings-header">
        <button
          className="settings-back-btn"
          onClick={onBack}
          title="Back to chat"
        >
          <IconChevronLeft size={18} />
        </button>
        <h1 className="settings-title">Settings</h1>
        <span className={`settings-saved-badge ${savedFlash ? "visible" : ""}`}>
          <IconCheck size={12} /> Saved
        </span>
      </div>

      <div className="settings-body">
        {/* ── Model ── */}
        <div className="settings-section">
          <h2 className="settings-section-title">Model</h2>
          <div className="settings-row">
            <div className="settings-row-info">
              <span className="settings-row-label">Default Model</span>
              <span className="settings-row-desc">
                The model used when you haven't explicitly selected one
                per-message. Changes apply to new messages only.
              </span>
            </div>
            <select
              className="settings-select"
              value={settings.defaultModel ?? "__none__"}
              onChange={(e) => handleModelChange(e.target.value)}
            >
              <option value="__none__">Server default</option>
              {fetchError && (
                <option disabled>⚠ Failed to load models</option>
              )}
              {models.map((m) => (
                <option key={m.modelOrAlias.model} value={m.modelOrAlias.model}>
                  {m.label}
                  {m.supportsImages ? " [Vision]" : ""}
                  {m.isRecommended ? " (Recommended)" : ""}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* ── Planner ── */}
        <div className="settings-section">
          <h2 className="settings-section-title">Planner</h2>
          <div className="settings-row">
            <div className="settings-row-info">
              <span className="settings-row-label">Default Mode</span>
              <span className="settings-row-desc">
                Fast gives direct single-step responses. Plan uses a
                multi-step structured approach for complex tasks.
              </span>
            </div>
            <select
              className="settings-select"
              value={settings.defaultPlannerType}
              onChange={(e) => handlePlannerChange(e.target.value)}
            >
              <option value="conversational">Fast</option>
              <option value="planning">Plan</option>
            </select>
          </div>
        </div>

        {/* ── Appearance ── */}
        <div className="settings-section">
          <h2 className="settings-section-title">Appearance</h2>
          <div className="settings-row">
            <div className="settings-row-info">
              <span className="settings-row-label">Theme</span>
              <span className="settings-row-desc">
                Choose between dark and light mode, or follow your system
                preference.
              </span>
            </div>
            <select
              className="settings-select"
              value={localStorage.getItem("porta:theme") ?? "dark"}
              onChange={(e) => {
                const theme = e.target.value;
                localStorage.setItem("porta:theme", theme);
                applyTheme(theme);
                flashSaved();
              }}
            >
              <option value="dark">Dark</option>
              <option value="light">Light</option>
              <option value="system">System</option>
            </select>
          </div>
        </div>

        {/* ── Share Links ── */}
        {currentWorkspaceUri && (
          <div className="settings-section">
            <h2 className="settings-section-title">Share Links</h2>
            <div className="settings-row-info" style={{ marginBottom: 16 }}>
              <span className="settings-row-desc">
                Create a public share link for this workspace. Viewers will
                need to enter a PIN to access conversations (read-only).
              </span>
            </div>

            {/* Create new share */}
            <div className="share-create-form">
              <div className="share-create-row">
                <input
                  className="login-input share-input"
                  type="text"
                  placeholder="Label (optional)"
                  value={newLabel}
                  onChange={(e) => setNewLabel(e.target.value)}
                />
                <input
                  className="login-input share-input"
                  type="password"
                  placeholder="PIN (min 4 chars)"
                  value={newPin}
                  onChange={(e) => {
                    setNewPin(e.target.value);
                    if (shareError) setShareError(null);
                  }}
                  autoComplete="off"
                />
                <button
                  className="share-create-btn"
                  onClick={handleCreateShare}
                  disabled={shareCreating || !newPin.trim()}
                >
                  {shareCreating ? "Creating…" : "Create"}
                </button>
              </div>
              {shareError && (
                <div className="share-error">{shareError}</div>
              )}
            </div>

            {/* Existing share links */}
            {shareLinks.filter((s) => s.workspaceUri === currentWorkspaceUri).length > 0 && (
              <div className="share-list">
                {shareLinks
                  .filter((s) => s.workspaceUri === currentWorkspaceUri)
                  .map((share) => (
                    <div
                      key={share.shareToken}
                      className={`share-item${!share.enabled ? " disabled" : ""}`}
                    >
                      <div className="share-item-info">
                        <span className="share-item-label">
                          {share.label || "Share link"}
                        </span>
                        <span className="share-item-meta">
                          Created{" "}
                          {new Date(share.createdAt).toLocaleDateString()}
                          {!share.enabled && " · Disabled"}
                        </span>
                      </div>
                      <div className="share-item-actions">
                        <button
                          className="share-action-btn"
                          onClick={() => copyShareUrl(share.shareToken)}
                          title="Copy share URL"
                        >
                          {copiedToken === share.shareToken ? (
                            <IconCheck size={14} />
                          ) : (
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                              <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
                            </svg>
                          )}
                        </button>
                        <button
                          className={`share-action-btn${share.enabled ? " warn" : " success"}`}
                          onClick={() => handleToggleShare(share.shareToken, !share.enabled)}
                          title={share.enabled ? "Disable" : "Enable"}
                        >
                          {share.enabled ? (
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                              <path d="M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z" />
                            </svg>
                          ) : (
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                              <path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z" />
                            </svg>
                          )}
                        </button>
                        <button
                          className="share-action-btn danger"
                          onClick={() => handleDeleteShare(share.shareToken)}
                          title="Delete share link"
                        >
                          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
                          </svg>
                        </button>
                      </div>
                    </div>
                  ))}
              </div>
            )}
          </div>
        )}

        {/* ── Keyboard Shortcuts ── */}
        <div className="settings-section">
          <h2 className="settings-section-title">Keyboard Shortcuts</h2>
          <div className="settings-shortcuts">
            <div className="shortcut-row">
              <span className="shortcut-keys">
                <kbd>⌘</kbd><kbd>K</kbd>
              </span>
              <span className="shortcut-desc">Search conversations</span>
            </div>
            <div className="shortcut-row">
              <span className="shortcut-keys">
                <kbd>⌘</kbd><kbd>N</kbd>
              </span>
              <span className="shortcut-desc">New chat</span>
            </div>
            <div className="shortcut-row">
              <span className="shortcut-keys">
                <kbd>Esc</kbd>
              </span>
              <span className="shortcut-desc">Close modal / sidebar</span>
            </div>
          </div>
        </div>

        {/* ── System Status ── */}
        {health && (
          <div className="settings-section">
            <h2 className="settings-section-title">System Status</h2>
            <div className="settings-shortcuts">
              <div className="shortcut-row">
                <span className="shortcut-desc">Proxy</span>
                <span style={{ color: health.status === "ok" ? "rgb(var(--c-success))" : "var(--status-error)", fontSize: 13, fontWeight: 500 }}>
                  {health.status === "ok" ? "● Running" : "● Error"}
                </span>
              </div>
              <div className="shortcut-row">
                <span className="shortcut-desc">Port</span>
                <span style={{ fontSize: 13, color: "var(--text-primary)", fontFamily: "var(--font-mono)" }}>
                  {health.proxy.port}
                </span>
              </div>
              <div className="shortcut-row">
                <span className="shortcut-desc">Uptime</span>
                <span style={{ fontSize: 13, color: "var(--text-primary)" }}>
                  {formatUptime(health.proxy.uptime)}
                </span>
              </div>
              <div className="shortcut-row">
                <span className="shortcut-desc">Language Servers</span>
                <span style={{ fontSize: 13, color: health.languageServers.length > 0 ? "rgb(var(--c-success))" : "var(--text-tertiary)", fontWeight: 500 }}>
                  {health.languageServers.length > 0
                    ? `${health.languageServers.length} connected`
                    : "None detected"}
                </span>
              </div>
              {health.languageServers.map((ls, i) => (
                <div key={i} className="shortcut-row" style={{ paddingLeft: 32 }}>
                  <span className="shortcut-desc" style={{ fontFamily: "var(--font-mono)", fontSize: 12 }}>
                    PID {ls.pid} · :{ls.httpsPort}
                  </span>
                  <span style={{ fontSize: 11, color: "var(--text-tertiary)" }}>
                    {ls.source}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* ── Install App ── */}
        {(canInstall || isInstalled) && (
          <div className="settings-section">
            <h2 className="settings-section-title">App</h2>
            <div className="settings-row">
              <div className="settings-row-info">
                <span className="settings-row-label">
                  {isInstalled ? "Installed" : "Install Porta"}
                </span>
                <span className="settings-row-desc">
                  {isInstalled
                    ? "Porta is installed as an app on this device."
                    : "Install Porta as a standalone app for quick access."}
                </span>
              </div>
              {canInstall && (
                <button
                  className="settings-select"
                  style={{ cursor: "pointer", textAlign: "center", minWidth: 100 }}
                  onClick={() => promptInstall()}
                >
                  Install
                </button>
              )}
            </div>
          </div>
        )}

        {/* ── Reset ── */}
        <button className="settings-reset-btn" onClick={handleReset}>
          Reset all settings to defaults
        </button>
      </div>
    </div>
  );
}
