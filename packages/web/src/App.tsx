import { useState, useCallback, useEffect, useRef } from "react";
import {
  Routes,
  Route,
  useParams,
  useNavigate,
  Navigate,
  useLocation,
} from "react-router-dom";
import { Sidebar } from "./components/Sidebar";
import { ChatHeader } from "./components/ChatHeader";
import { ChatPanel } from "./components/ChatPanel";
import { ChatInput } from "./components/ChatInput";
import { SettingsPanel } from "./components/SettingsPanel";
import { WorkspaceSelector } from "./components/WorkspaceSelector";
import { LoginPage } from "./components/LoginPage";
import { ConnectionBanner } from "./components/ConnectionBanner";
import { ShareGate } from "./components/ShareGate";
import { SharedChatView } from "./components/SharedChatView";
import { IconFolder } from "./components/Icons";
import { useConversations } from "./hooks/useConversations";
import { usePolling } from "./hooks/usePolling";
import { useWorkspaces, slugFromUri } from "./hooks/useWorkspaces";
import { useDraftText } from "./hooks/useDraftText";
import { useChatActions } from "./hooks/useChatActions";
import { useClientSettings } from "./hooks/useClientSettings";
import { useKeyboardShortcuts } from "./hooks/useKeyboardShortcuts";
import { api, getAuthToken, clearAuthToken } from "./api/client";
import { isUnconfirmedOptimisticMessage } from "./utils/optimisticMessages";
import type { HealthResponse, MediaAttachment } from "./types";
import type { PlannerType } from "./components/ChatInput";

// ── Auth State ──

type AuthState = "loading" | "login" | "authenticated";
type AuthMethods = { token: boolean; password: boolean };

function useAuthGate(): {
  authState: AuthState;
  authMethods: AuthMethods | undefined;
  onAuthenticated: () => void;
  onLogout: () => Promise<void>;
} {
  const [authState, setAuthState] = useState<AuthState>("loading");
  const [authMethods, setAuthMethods] = useState<AuthMethods | undefined>();

  useEffect(() => {
    let cancelled = false;

    const checkAuth = async () => {
      try {
        const API_BASE = import.meta.env.VITE_API_BASE ?? "";
        const token = getAuthToken();
        const headers: Record<string, string> = {};
        if (token) headers["Authorization"] = `Bearer ${token}`;

        const res = await fetch(`${API_BASE}/api/auth/check`, { headers });
        if (cancelled) return;

        if (!res.ok) {
          setAuthState("login");
          return;
        }

        const data = await res.json();
        if (cancelled) return;

        // Store available auth methods
        if (data.methods) setAuthMethods(data.methods);

        if (!data.authRequired) {
          setAuthState("authenticated");
        } else if (data.authenticated) {
          setAuthState("authenticated");
        } else {
          clearAuthToken();
          setAuthState("login");
        }
      } catch {
        if (!cancelled) setAuthState("authenticated");
      }
    };

    checkAuth();

    const handleAuthRequired = () => {
      clearAuthToken();
      setAuthState("login");
    };
    window.addEventListener("porta:auth-required", handleAuthRequired);

    return () => {
      cancelled = true;
      window.removeEventListener("porta:auth-required", handleAuthRequired);
    };
  }, []);

  const onAuthenticated = useCallback(() => {
    setAuthState("authenticated");
  }, []);

  const onLogout = useCallback(async () => {
    await api.logout();
    setAuthState("login");
  }, []);

  return { authState, authMethods, onAuthenticated, onLogout };
}

export default function App() {
  const { authState, authMethods, onAuthenticated, onLogout } = useAuthGate();

  if (authState === "loading") {
    return (
      <div className="login-page">
        <div className="login-card" style={{ textAlign: "center", padding: "48px 32px" }}>
          <div className="login-logo">
            <div className="login-logo-icon">
              <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path
                  d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zM12 17c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1s3.1 1.39 3.1 3.1v2z"
                  fill="white"
                />
              </svg>
            </div>
            <div className="login-title">Porta</div>
            <div className="login-subtitle">Connecting…</div>
          </div>
        </div>
      </div>
    );
  }

  if (authState === "login") {
    return <LoginPage onAuthenticated={onAuthenticated} methods={authMethods} />;
  }

  return (
    <>
      <ConnectionBanner />
      <Routes>
        <Route path="/" element={<RootRedirect />} />
        <Route path="/s/:shareToken" element={<ShareRoute />} />
        <Route path="/s/:shareToken/:chatId" element={<ShareRoute />} />
        <Route path="/:projectSlug/settings" element={<ChatView onLogout={onLogout} />} />
        <Route path="/:projectSlug" element={<ChatView onLogout={onLogout} />} />
        <Route path="/:projectSlug/:chatId" element={<ChatView onLogout={onLogout} />} />
      </Routes>
    </>
  );
}

// ── Share route: PIN gate → read-only viewer ──

function ShareRoute() {
  const { shareToken } = useParams<{ shareToken: string }>();
  const [authenticated, setAuthenticated] = useState(false);
  const [workspaceName, setWorkspaceName] = useState("");

  const handleAuthenticated = useCallback(
    (_workspaceUri: string, name: string) => {
      setWorkspaceName(name);
      setAuthenticated(true);
    },
    [],
  );

  if (!shareToken) return <Navigate to="/" replace />;

  if (!authenticated) {
    return (
      <ShareGate
        shareToken={shareToken}
        onAuthenticated={handleAuthenticated}
      />
    );
  }

  return <SharedChatView workspaceName={workspaceName} />;
}

// ── Root redirect: go to the first workspace's new-chat page ──

function RootRedirect() {
  const [target, setTarget] = useState<string | null>(null);

  useEffect(() => {
    api
      .getWorkspaces()
      .then((data) => {
        const first = data.workspaceInfos?.[0];
        if (first) {
          setTarget(`/${slugFromUri(first.workspaceUri)}`);
        } else {
          setTarget("/unknown");
        }
      })
      .catch(() => {
        // If the API fails, stay put — ChatView will handle empty state
        // We still need to bounce the user to ChatView, though.
        setTarget("/unknown");
      });
  }, []);

  if (target) return <Navigate to={target} replace />;
  return null; // Loading…
}

// ── Main Chat View ──

function ChatView({ onLogout }: { onLogout?: () => void }) {
  const { projectSlug, chatId } = useParams<{
    projectSlug: string;
    chatId: string;
  }>();
  const navigate = useNavigate();
  const location = useLocation();
  const isSettingsPage = location.pathname.endsWith("/settings");
  const activeId = chatId ?? null;
  const [sidebarOpen, setSidebarOpen] = useState(() => window.innerWidth > 480);
  const isMobile = () => window.innerWidth <= 480;
  const { conversations, loading, refresh, optimisticRemove } = useConversations(15_000);
  const { data: health } = usePolling<HealthResponse>(api.health, 30_000);

  // ── Hooks ──
  const { workspaces, currentWorkspaceUri } = useWorkspaces(
    conversations,
    projectSlug,
  );
  const { draftText, handleDraftChange } = useDraftText(activeId);
  const { settings, updateSettings } = useClientSettings();

  const activeConv = conversations.find((c) => c.id === activeId);
  const polledRunning = activeConv?.summary.status === "CASCADE_RUN_STATUS_RUNNING";
  // Real-time running state from WebSocket — instant, no 15s delay
  const [wsRunning, setWsRunning] = useState(false);
  const isRunning = wsRunning || polledRunning;

  // Reset wsRunning when switching conversations
  useEffect(() => {
    setWsRunning(false);
  }, [activeId]);
  const connected = !!health && health.languageServers.length > 0;

  const {
    optimisticMessages,
    setOptimisticMessages,
    confirmOptimisticMessages,
    stepsRefreshKey,
    hardRefreshKey,
    handleSend: doSend,
    handleStop,

    handleRevert: rawHandleRevert,
    handleDelete,
    chatUrl,
    triggerSoftRefresh,
  } = useChatActions({
    activeId,
    currentWorkspaceUri,
    projectSlug,
    refresh,
    conversations,
    optimisticRemove,
  });

  // Wire handleRevert to also update draft text
  const handleRevert = useCallback(
    async (stepIndex: number, draftContent?: string) => {
      await rawHandleRevert(stepIndex, draftContent);
      if (draftContent) {
        handleDraftChange(draftContent);
      }
    },
    [rawHandleRevert, handleDraftChange],
  );

  // ── Send: always grant file access ──
  const handleSend = useCallback(
    async (
      text: string,
      model: string | null,
      media?: MediaAttachment[],
      plannerType?: PlannerType,
    ) => {
      doSend(text, model, media, plannerType, true);
    },
    [doSend],
  );

  // ── Per-file permission response ──
  const handleFilePermission = useCallback(
    async (
      trajectoryId: string,
      stepIndex: number,
      allow: boolean,
      scope: number,
      absolutePathUri: string,
    ) => {
      if (!activeId) return;
      try {
        await api.filePermission(
          activeId,
          trajectoryId,
          stepIndex,
          allow,
          scope,
          absolutePathUri,
        );
        // WS activate signal (emitted by proxy) handles real-time push.
        // Soft refresh as insurance — non-destructive merge, no screen blank.
        triggerSoftRefresh();
        refresh();
      } catch (err) {
        console.error("Failed to respond to file permission:", err);
      }
    },
    [activeId, refresh, triggerSoftRefresh],
  );

  // ── Command action (approve/reject proposed command) ──
  const handleCommandAction = useCallback(
    async (
      trajectoryId: string,
      stepIndex: number,
      approved: boolean,
    ) => {
      if (!activeId) return;
      try {
        await api.commandAction(
          activeId,
          trajectoryId,
          stepIndex,
          approved,
        );
        triggerSoftRefresh();
        refresh();
      } catch (err) {
        console.error("Failed to respond to command action:", err);
        throw err; // Propagate so CommandCard can restore buttons
      }
    },
    [activeId, refresh, triggerSoftRefresh],
  );

  // ── Answer question (respond to ask_question interactions) ──
  const handleAnswerQuestion = useCallback(
    async (
      trajectoryId: string,
      stepIndex: number,
      selectedOptions: number[],
      writeInText?: string,
    ) => {
      if (!activeId) return;
      try {
        await api.answerQuestion(
          activeId,
          trajectoryId,
          stepIndex,
          selectedOptions,
          writeInText,
        );
        triggerSoftRefresh();
        refresh();
      } catch (err) {
        console.error("Failed to answer question:", err);
      }
    },
    [activeId, refresh, triggerSoftRefresh],
  );

  // ── Navigate helpers ──
  const handleNew = useCallback(() => {
    navigate(`/${projectSlug ?? "unknown"}`);
    setOptimisticMessages([]);
    if (isMobile()) setSidebarOpen(false);
  }, [navigate, projectSlug, setOptimisticMessages]);

  // Global keyboard shortcuts
  useKeyboardShortcuts({
    onSearch: useCallback(() => {
      // Dispatch custom event that Sidebar listens for
      window.dispatchEvent(new CustomEvent("porta:open-search"));
    }, []),
    onNewChat: handleNew,
    onEscape: useCallback(() => {
      if (isMobile()) setSidebarOpen(false);
    }, []),
  });

  // Header info
  const headerTitle = activeId
    ? (activeConv?.summary.summary ?? "Session")
    : "New Chat";

  // ── Mobile Swipe Gestures ──
  const touchStartX = useRef(0);
  const touchStartY = useRef(0);

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX;
    touchStartY.current = e.touches[0].clientY;
  }, []);

  const handleTouchEnd = useCallback(
    (e: React.TouchEvent) => {
      if (!isMobile()) return;
      const touchEndX = e.changedTouches[0].clientX;
      const touchEndY = e.changedTouches[0].clientY;

      const dx = touchEndX - touchStartX.current;
      const dy = touchEndY - touchStartY.current;

      // Must be primarily horizontal
      if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 40) {
        if (dx > 0 && touchStartX.current < 30) {
          // Swipe right from the far left edge → open
          setSidebarOpen(true);
        } else if (dx < 0 && sidebarOpen) {
          // Swipe left anywhere → close
          setSidebarOpen(false);
        }
      }
    },
    [sidebarOpen],
  );

  return (
    <div
      className="app-layout"
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
    >
      <Sidebar
        conversations={conversations}
        activeId={activeId}
        onSelect={(id) => {
          setOptimisticMessages([]);
          navigate(chatUrl(id));
          if (isMobile()) setSidebarOpen(false);
        }}
        onNew={handleNew}
        onDelete={handleDelete}
        onSettings={() => {
          navigate(`/${projectSlug ?? "unknown"}/settings`);
          if (isMobile()) setSidebarOpen(false);
        }}
        onLogout={onLogout}
        loading={loading}
        connected={connected}
        isOpen={sidebarOpen}
        onToggle={() => setSidebarOpen((v) => !v)}
      />
      {/* Mobile backdrop: tap to close sidebar */}
      {sidebarOpen && (
        <div
          className="sidebar-backdrop"
          onClick={() => setSidebarOpen(false)}
        />
      )}
      <div className="main-panel">
        <ChatHeader
          title={headerTitle}
          projectName={projectSlug ?? undefined}
          onMenuToggle={() => setSidebarOpen(true)}
        />
        {isSettingsPage ? (
          <SettingsPanel
            settings={settings}
            onUpdate={updateSettings}
            onBack={() => navigate(`/${projectSlug ?? "unknown"}`)}
          />
        ) : activeId ? (
          <ChatPanel
            key={activeId}
            cascadeId={activeId}
            onRevert={handleRevert}
            onFilePermission={handleFilePermission}
            onCommandAction={handleCommandAction}
            onAnswerQuestion={handleAnswerQuestion}
            onConfirmOptimistic={confirmOptimisticMessages}
            optimisticMessages={optimisticMessages}
            refreshKey={stepsRefreshKey}
            hardRefreshKey={hardRefreshKey}
            totalStepCount={activeConv?.summary.stepCount}
            isConversationRunning={isRunning}
            onSidebarRefresh={refresh}
            onWsRunningChange={setWsRunning}
            onSendFeedback={(feedback) => handleSend(feedback, null)}
          />
        ) : (
          <div
            className="chat-area"
            onTouchMove={() => {
              const el = document.activeElement;
              if (
                el instanceof HTMLTextAreaElement ||
                el instanceof HTMLInputElement
              ) {
                el.blur();
              }
            }}
          >
            <div className="chat-area-inner">
              {optimisticMessages.map((msg, i) => (
                <div
                  key={msg.optimisticId ?? i}
                  className={`message ${msg.role}${isUnconfirmedOptimisticMessage(msg) ? " unconfirmed" : ""}`}
                >
                  <div className="chat-block message-body">
                    <p>{msg.content}</p>
                  </div>
                </div>
              ))}
              {optimisticMessages.some(isUnconfirmedOptimisticMessage) && (
                <div className="message assistant">
                  <div className="chat-block message-body">
                    <div className="typing-indicator">
                      <span />
                      <span />
                      <span />
                    </div>
                  </div>
                </div>
              )}
            </div>
            {optimisticMessages.length === 0 && (
              <div className="chat-empty">
                <div className="chat-empty-icon">
                  <svg
                    width="48"
                    height="48"
                    viewBox="0 0 24 24"
                    fill="none"
                    xmlns="http://www.w3.org/2000/svg"
                  >
                    <defs>
                      <linearGradient
                        id="chatGrad"
                        x1="0%"
                        y1="0%"
                        x2="100%"
                        y2="100%"
                      >
                        <stop offset="0%" stopColor="var(--accent)" />
                        <stop offset="50%" stopColor="#a78bfa" />
                        <stop offset="100%" stopColor="#f472b6" />
                      </linearGradient>
                    </defs>
                    <path
                      d="M12 2C6.48 2 2 5.92 2 10.67c0 2.72 1.47 5.15 3.78 6.73L4.5 21.5l4.33-2.17c1.02.28 2.09.43 3.17.43 5.52 0 10-3.92 10-8.76S17.52 2 12 2z"
                      fill="url(#chatGrad)"
                      opacity="0.25"
                    />
                    <path
                      d="M12 2C6.48 2 2 5.92 2 10.67c0 2.72 1.47 5.15 3.78 6.73L4.5 21.5l4.33-2.17c1.02.28 2.09.43 3.17.43 5.52 0 10-3.92 10-8.76S17.52 2 12 2z"
                      stroke="url(#chatGrad)"
                      strokeWidth="1.5"
                      fill="none"
                    />
                  </svg>
                </div>
                <div className="chat-empty-text">Start a conversation</div>
                {workspaces.length > 0 && currentWorkspaceUri ? (
                  <WorkspaceSelector
                    workspaces={workspaces}
                    selected={currentWorkspaceUri}
                    onSelect={(uri) => {
                      const slug = slugFromUri(uri);
                      navigate(`/${slug}`);
                    }}
                  />
                ) : (
                  <div className="chat-empty-project">
                    <IconFolder size={13} /> {projectSlug ?? "Others"}
                  </div>
                )}
              </div>
            )}
          </div>
        )}
        {!isSettingsPage && (
          <ChatInput
            onSend={handleSend}
            onStop={handleStop}
            isRunning={isRunning}
            disabled={!connected}
            draft={draftText}
            onDraftChange={handleDraftChange}
            defaultModel={settings.defaultModel}
            defaultPlannerType={settings.defaultPlannerType}
            onModelChange={(m) => updateSettings({ defaultModel: m })}
            onPlannerTypeChange={(p) => updateSettings({ defaultPlannerType: p })}
          />
        )}
      </div>
    </div>
  );
}
