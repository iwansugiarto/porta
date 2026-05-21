import { useState, useCallback, useRef } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { ChatHeader } from "./ChatHeader";
import { ChatPanel } from "./ChatPanel";
import { IconFolder } from "./Icons";
import { useConversations } from "../hooks/useConversations";
import { usePolling } from "../hooks/usePolling";
import { api, clearShareSession } from "../api/client";
import type { HealthResponse } from "../types";

/**
 * Read-only chat viewer for share link sessions.
 *
 * Reuses the standard ChatPanel for rendering but strips all mutation controls:
 * no ChatInput, no send/stop/revert/delete, simplified sidebar.
 */
export function SharedChatView({
  workspaceName,
}: {
  workspaceName: string;
}) {
  const { chatId } = useParams<{ chatId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const activeId = chatId ?? null;

  const { conversations, loading } = useConversations(15_000);
  usePolling<HealthResponse>(api.health, 30_000);

  // Sidebar state
  const [sidebarOpen, setSidebarOpen] = useState(
    () => window.innerWidth > 480,
  );
  const isMobile = () => window.innerWidth <= 480;

  const activeConv = conversations.find((c) => c.id === activeId);
  const isRunning =
    activeConv?.summary.status === "CASCADE_RUN_STATUS_RUNNING";

  // Derive the base path from location
  const basePath = location.pathname.split("/").slice(0, 3).join("/");

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
      const dx = e.changedTouches[0].clientX - touchStartX.current;
      const dy = e.changedTouches[0].clientY - touchStartY.current;
      if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 40) {
        if (dx > 0 && touchStartX.current < 30) {
          setSidebarOpen(true);
        } else if (dx < 0 && sidebarOpen) {
          setSidebarOpen(false);
        }
      }
    },
    [sidebarOpen],
  );

  const handleLeave = useCallback(() => {
    clearShareSession();
    navigate("/");
  }, [navigate]);

  // Header title
  const headerTitle = activeId
    ? activeConv?.summary.summary ?? "Session"
    : "Shared Workspace";

  return (
    <div
      className="app-layout"
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
    >
      {/* Simplified sidebar for shared view */}
      <aside className={`sidebar${sidebarOpen ? " open" : ""}`}>
        <div className="sidebar-header">
          <div className="sidebar-title-row">
            <span className="sidebar-title">Conversations</span>
          </div>
          <div className="share-badge-container">
            <span className="share-badge">
              <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor">
                <path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z" />
              </svg>
              Shared · Read-only
            </span>
          </div>
        </div>

        <div className="sidebar-conversations">
          {loading && conversations.length === 0 ? (
            <div className="sidebar-loading">Loading…</div>
          ) : conversations.length === 0 ? (
            <div className="sidebar-empty">No conversations yet</div>
          ) : (
            conversations.map((conv) => (
              <button
                key={conv.id}
                className={`sidebar-item${activeId === conv.id ? " active" : ""}`}
                onClick={() => {
                  navigate(`${basePath}/${conv.id}`);
                  if (isMobile()) setSidebarOpen(false);
                }}
              >
                <div className="sidebar-item-title">
                  {conv.summary.summary || conv.id.slice(0, 8) + "…"}
                </div>
                <div className="sidebar-item-meta">
                  {conv.summary.stepCount} steps
                  {conv.summary.status === "CASCADE_RUN_STATUS_RUNNING" && (
                    <span className="sidebar-running-dot" />
                  )}
                </div>
              </button>
            ))
          )}
        </div>

        <div className="sidebar-footer">
          <button className="sidebar-footer-btn" onClick={handleLeave}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
              <path d="M10.09 15.59L11.5 17l5-5-5-5-1.41 1.41L12.67 11H3v2h9.67l-2.58 2.59zM19 3H5a2 2 0 00-2 2v4h2V5h14v14H5v-4H3v4a2 2 0 002 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z" />
            </svg>
            Leave
          </button>
        </div>
      </aside>

      {sidebarOpen && (
        <div
          className="sidebar-backdrop"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      <div className="main-panel">
        <ChatHeader
          title={headerTitle}
          projectName={workspaceName}
          onMenuToggle={() => setSidebarOpen(true)}
        />
        {/* Shared view badge */}
        <div className="share-view-banner">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
            <path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z" />
          </svg>
          Shared view — read-only access
        </div>

        {activeId ? (
          <ChatPanel
            key={activeId}
            cascadeId={activeId}
            onRevert={() => {}}
            onFilePermission={() => {}}
            onCommandAction={async () => {}}
            onConfirmOptimistic={() => {}}
            optimisticMessages={[]}
            refreshKey={0}
            hardRefreshKey={0}
            totalStepCount={activeConv?.summary.stepCount}
            isConversationRunning={isRunning}
            onSidebarRefresh={() => {}}
          />
        ) : (
          <div className="chat-area">
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
                      id="shareGrad"
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
                    d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"
                    fill="url(#shareGrad)"
                    opacity="0.25"
                  />
                  <path
                    d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"
                    stroke="url(#shareGrad)"
                    strokeWidth="1.5"
                    fill="none"
                  />
                </svg>
              </div>
              <div className="chat-empty-text">
                Select a conversation to view
              </div>
              <div className="chat-empty-project">
                <IconFolder size={13} /> {workspaceName}
              </div>
            </div>
          </div>
        )}
        {/* No ChatInput for shared view — read-only */}
      </div>
    </div>
  );
}
