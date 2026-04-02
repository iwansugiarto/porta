import { useState, useEffect, useCallback } from "react";
import { IconChevronLeft, IconCheck } from "./Icons";
import { api } from "../api/client";
import { usePwaInstall } from "../hooks/usePwaInstall";
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
