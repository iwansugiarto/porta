import { useMemo, useState, useRef, useEffect, useCallback } from "react";
import type { ConversationEntry } from "../hooks/useConversations";
import {
  IconPlus,
  IconSearch,
  IconMenu,
  IconMore,
  IconX,
  IconSpinner,
  IconGear,
  IconAlertCircle,
  IconClock,
  IconCheckCircle,
  IconFolderPlus,
  IconFilter,
  IconEdit,
  IconFolder,
  IconHistory,
  IconPin,
} from "./Icons";
import { api } from "../api/client";
import { exportConversation } from "../utils/exportConversation";
import { useProjects } from "../hooks/useProjects";
import type { Project, ProjectStatus } from "../types";

interface Props {
  conversations: ConversationEntry[];
  activeId: string | null;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (id: string) => void;
  onSettings: () => void;
  onLogout?: () => void;
  loading: boolean;
  connected: boolean;
  isOpen: boolean;
  onToggle: () => void;
}

const PREVIEW_COUNT = 6;

function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60_000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  return `${days}d`;
}

function isArchived(conv: ConversationEntry): boolean {
  return conv.summary.status === "CASCADE_RUN_STATUS_UNLOADED";
}

/** Three-dot context menu */
function ContextMenu({
  isPinned,
  onTogglePin,
  onDelete,
  onExportMarkdown,
  onExportJson,
  onClose,
}: {
  isPinned: boolean;
  onTogglePin: () => void;
  onDelete: () => void;
  onExportMarkdown: () => void;
  onExportJson: () => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        onClose();
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [onClose]);

  return (
    <div ref={ref} className="context-menu">
      <button
        className="context-menu-item"
        onClick={(e) => {
          e.stopPropagation();
          onTogglePin();
          onClose();
        }}
      >
        {isPinned ? "Unpin Conversation" : "Pin Conversation"}
      </button>
      <button
        className="context-menu-item"
        onClick={(e) => {
          e.stopPropagation();
          onExportMarkdown();
          onClose();
        }}
      >
        Export as Markdown
      </button>
      <button
        className="context-menu-item"
        onClick={(e) => {
          e.stopPropagation();
          onExportJson();
          onClose();
        }}
      >
        Export as JSON
      </button>
      <button
        className="context-menu-item danger"
        onClick={(e) => {
          e.stopPropagation();
          onDelete();
          onClose();
        }}
      >
        Delete
      </button>
    </div>
  );
}

// ── Sidebar action items ──

interface SidebarAction {
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
  active?: boolean;
}

export function Sidebar({
  conversations,
  activeId,
  onSelect,
  onNew,
  onDelete,
  onSettings,
  onLogout,
  loading,
  connected,
  isOpen,
  onToggle,
}: Props) {
  // Collapsed status groups state for Projects
  const [projectCollapse, setProjectCollapse] = useState<Record<string, boolean>>({
    Blocked: false,
    "In Progress": false,
    Idle: false,
  });

  const toggleProjectGroup = useCallback((group: string) => {
    setProjectCollapse((prev) => ({ ...prev, [group]: !prev[group] }));
  }, []);


  const [menuOpen, setMenuOpen] = useState<string | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<
    | {
        id: string;
        title: string;
        snippets: string[];
        matchCount: number;
      }[]
    | null
  >(null);
  const [searching, setSearching] = useState(false);
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  // Modals state for Projects
  const [createProjectOpen, setCreateProjectOpen] = useState(false);
  const [selectedEditProject, setSelectedEditProject] = useState<Project | null>(null);
  const [projectsExplorerOpen, setProjectsExplorerOpen] = useState(false);

  // Project forms state
  const [newProjTitle, setNewProjTitle] = useState("");
  const [newProjStatus, setNewProjStatus] = useState<ProjectStatus>("Idle");
  const [editProjTitle, setEditProjTitle] = useState("");
  const [editProjStatus, setEditProjStatus] = useState<ProjectStatus>("Idle");

  // Explorer modal filter/search state
  const [explorerQuery, setExplorerQuery] = useState("");
  const [explorerFilter, setExplorerFilter] = useState<"All" | ProjectStatus>("All");
  const [explorerSort, setExplorerSort] = useState<"newest" | "oldest" | "alpha">("newest");

  // Load Projects from custom local storage hook
  const {
    projects,
    activeProjectId,
    setActiveProjectId,
    createProject,
    updateProjectStatus,
    updateProjectTitle,
    deleteProject,
  } = useProjects();

  // Pinned conversations state with persistence
  const [pinnedIds, setPinnedIds] = useState<Set<string>>(() => {
    try {
      const saved = localStorage.getItem("porta:pinned_conversation_ids");
      return saved ? new Set(JSON.parse(saved)) : new Set();
    } catch {
      return new Set();
    }
  });

  const togglePin = useCallback((id: string) => {
    setPinnedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      try {
        localStorage.setItem("porta:pinned_conversation_ids", JSON.stringify(Array.from(next)));
      } catch {}
      return next;
    });
  }, []);

  // Helper to extract workspace repository folder/computed name
  const getWorkspaceRepoName = useCallback((conv: ConversationEntry): string | null => {
    const workspace = conv.summary.workspaces?.[0];
    if (!workspace) return null;
    const computedName = workspace.repository?.computedName;
    if (computedName) {
      const parts = computedName.split("/");
      return parts[parts.length - 1];
    }
    const uri = workspace.workspaceFolderAbsoluteUri;
    if (uri) {
      const parts = uri.replace(/\/$/, "").split("/");
      return parts[parts.length - 1] || null;
    }
    return null;
  }, []);

  // Helper to extract repository subtitle for a project title
  const getProjectRepoName = useCallback((projTitle: string): string | null => {
    const matched = conversations.find(
      (c) => c.summary?.summary?.toLowerCase() === projTitle.toLowerCase()
    );
    if (matched) {
      const repo = getWorkspaceRepoName(matched);
      if (repo) return repo;
    }
    const titleLower = projTitle.toLowerCase();
    if (titleLower.includes("porta")) return "porta";
    if (titleLower.includes("odoo")) return "odoo-addons";
    return null;
  }, [conversations, getWorkspaceRepoName]);

  // Sort and filter project arrays
  const blockedProjects = useMemo(() => projects.filter((p) => p.status === "Blocked"), [projects]);
  const inProgressProjects = useMemo(() => projects.filter((p) => p.status === "In Progress"), [projects]);
  const idleProjects = useMemo(() => projects.filter((p) => p.status === "Idle"), [projects]);

  // Unified search matching local projects
  const matchedLocalProjects = useMemo(() => {
    if (!searchQuery.trim()) return [];
    return projects.filter((p) =>
      p.title.toLowerCase().includes(searchQuery.toLowerCase())
    );
  }, [projects, searchQuery]);

  // Listen for external search trigger (Cmd+K)
  useEffect(() => {
    const openSearch = () => {
      setSearchOpen(true);
      setTimeout(() => searchInputRef.current?.focus(), 50);
    };
    window.addEventListener("porta:open-search", openSearch);
    return () => window.removeEventListener("porta:open-search", openSearch);
  }, []);

  const closeMenu = useCallback(() => setMenuOpen(null), []);

  // Flat recent conversations list
  const recentConversations = useMemo<ConversationEntry[]>(() => {
    return [...conversations].sort((a, b) => {
      const aRunning = a.summary.status === "CASCADE_RUN_STATUS_RUNNING";
      const bRunning = b.summary.status === "CASCADE_RUN_STATUS_RUNNING";
      if (aRunning !== bRunning) return aRunning ? -1 : 1;
      return (
        new Date(b.summary.lastModifiedTime).getTime() -
        new Date(a.summary.lastModifiedTime).getTime()
      );
    });
  }, [conversations]);

  // Split recent conversations into pinned and non-pinned lists
  const pinnedConversations = useMemo(() => {
    return recentConversations.filter((c) => pinnedIds.has(c.id));
  }, [recentConversations, pinnedIds]);

  const nonPinnedConversations = useMemo(() => {
    return recentConversations.filter((c) => !pinnedIds.has(c.id));
  }, [recentConversations, pinnedIds]);

  const actions: SidebarAction[] = [
    { icon: <IconPlus size={14} />, label: "New Chat", onClick: onNew },
    {
      icon: <IconSearch size={14} />,
      label: "Search",
      onClick: () => {
        setSearchOpen(true);
        setTimeout(() => searchInputRef.current?.focus(), 50);
      },
    },
    { icon: <IconGear size={14} />, label: "Settings", onClick: onSettings },
    ...(onLogout
      ? [
          {
            icon: (
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                <polyline points="16 17 21 12 16 7" />
                <line x1="21" y1="12" x2="9" y2="12" />
              </svg>
            ),
            label: "Logout",
            onClick: onLogout,
          },
        ]
      : []),
  ];

  // Debounced search
  const handleSearchInput = useCallback((value: string) => {
    setSearchQuery(value);
    if (searchTimer.current) clearTimeout(searchTimer.current);

    if (!value.trim()) {
      setSearchResults(null);
      setSearching(false);
      return;
    }

    setSearching(true);
    searchTimer.current = setTimeout(async () => {
      try {
        const data = await api.search(value.trim());
        setSearchResults(data.results);
      } catch {
        setSearchResults([]);
      } finally {
        setSearching(false);
      }
    }, 400);
  }, []);

  const closeSearch = useCallback(() => {
    setSearchOpen(false);
    setSearchQuery("");
    setSearchResults(null);
    setSearching(false);
  }, []);

  // Track when each conversation was last "seen" by the user.
  const [seenAt, setSeenAt] = useState<Record<string, string>>(() => {
    try {
      return JSON.parse(localStorage.getItem("porta:seenAt") ?? "{}");
    } catch {
      return {};
    }
  });

  const markSeen = useCallback(
    (convId: string) => {
      const conv = conversations.find((c) => c.id === convId);
      if (!conv) return;
      setSeenAt((prev) => {
        const next = { ...prev, [convId]: conv.summary.lastModifiedTime };
        try {
          localStorage.setItem("porta:seenAt", JSON.stringify(next));
        } catch {}
        return next;
      });
    },
    [conversations],
  );

  // Auto-mark active thread as seen when it receives updates
  useEffect(() => {
    if (activeId) markSeen(activeId);
  }, [activeId, conversations, markSeen]);

  // Project row renderer
  const renderProjectItem = (proj: Project) => {
    const isActive = proj.id === activeProjectId;
    const isSpinner = proj.status === "In Progress" && isActive;
    const repoName = getProjectRepoName(proj.title);

    return (
      <div
        key={proj.id}
        className={`sidebar-item project-item ${isActive ? "active" : ""}`}
        onClick={() => {
          setActiveProjectId(proj.id);
          // Highlight/select corresponding chat if title matches exactly
          const matched = conversations.find(
            (c) => c.summary?.summary?.toLowerCase() === proj.title?.toLowerCase()
          );
          if (matched) {
            onSelect(matched.id);
          } else {
            onNew();
            // Pre-fill prompt to associate next conversation
            setTimeout(() => {
              const input = document.querySelector("textarea") as HTMLTextAreaElement;
              if (input) {
                input.value = `Let's work on Project: ${proj.title}`;
                input.dispatchEvent(new Event("input", { bubbles: true }));
              }
            }, 250);
          }
        }}
      >
        <div className="sidebar-item-content">
          <div className="sidebar-item-title" title={proj.title}>
            {proj.title}
          </div>
          <div className="sidebar-item-subtitle-row">
            {repoName && <span className="sidebar-item-subtitle">{repoName}</span>}
            {proj.timeBadge && (
              <span className="sidebar-item-meta">
                {repoName && " · "}
                {proj.timeBadge}
              </span>
            )}
          </div>
        </div>
        <div className="sidebar-item-right" onClick={(e) => e.stopPropagation()}>
          {isSpinner ? (
            <IconSpinner size={13} className="item-indicator icon-spin" />
          ) : proj.hasIndicator ? (
            <span className="item-dot blue-dot" />
          ) : proj.timeBadge ? (
            <span className="sidebar-item-time-badge">{proj.timeBadge}</span>
          ) : proj.status === "Blocked" ? (
            <button
              className="project-row-action-btn feedback-btn"
              onClick={() => {
                setEditProjTitle(proj.title);
                setEditProjStatus(proj.status);
                setSelectedEditProject(proj);
              }}
              title="Feedback/Edit"
            >
              <IconEdit size={12} />
            </button>
          ) : (
            <button
              className="sidebar-item-menu-btn"
              onClick={() => {
                setEditProjTitle(proj.title);
                setEditProjStatus(proj.status);
                setSelectedEditProject(proj);
              }}
              title="Project settings"
            >
              <IconMore size={13} />
            </button>
          )}
        </div>
      </div>
    );
  };

  // Conversation row renderer
  const renderItem = (conv: ConversationEntry) => {
    const isRunning = conv.summary.status === "CASCADE_RUN_STATUS_RUNNING";
    const lastSeen = seenAt[conv.id];
    const hasUpdates =
      !isRunning &&
      conv.id !== activeId &&
      !!lastSeen &&
      new Date(conv.summary.lastModifiedTime).getTime() >
        new Date(lastSeen).getTime();
    const isPinned = pinnedIds.has(conv.id);
    const repoName = getWorkspaceRepoName(conv);

    return (
      <div
        key={conv.id}
        className={`sidebar-item ${conv.id === activeId ? "active" : ""} ${isArchived(conv) ? "dimmed" : ""}`}
        onClick={() => {
          markSeen(conv.id);
          onSelect(conv.id);
        }}
      >
        <div className="sidebar-item-content">
          <div className="sidebar-item-title" title={conv.summary.summary || "Untitled Conversation"}>
            {conv.summary.summary || "Untitled Conversation"}
          </div>
          <div className="sidebar-item-subtitle-row">
            {repoName && <span className="sidebar-item-subtitle">{repoName}</span>}
            <span className="sidebar-item-meta">
              {repoName && " · "}
              {relativeTime(conv.summary.lastModifiedTime)}
              {" · "}
              {conv.summary.stepCount} steps
            </span>
          </div>
        </div>
        <div className="sidebar-item-right">
          {isRunning && <IconSpinner size={13} className="item-indicator" />}
          {hasUpdates && <span className="item-dot" />}
          {isPinned && <IconPin size={11} className="pinned-indicator-icon" />}
          <button
            className="sidebar-item-menu-btn"
            onClick={(e) => {
              e.stopPropagation();
              setMenuOpen(menuOpen === conv.id ? null : conv.id);
            }}
            title="More options"
          >
            <IconMore size={13} />
          </button>
          {menuOpen === conv.id && (
            <ContextMenu
              isPinned={isPinned}
              onTogglePin={() => togglePin(conv.id)}
              onDelete={() => onDelete(conv.id)}
              onExportMarkdown={() =>
                exportConversation(
                  conv.id,
                  conv.summary.summary || "conversation",
                  "markdown",
                )
              }
              onExportJson={() =>
                exportConversation(
                  conv.id,
                  conv.summary.summary || "conversation",
                  "json",
                )
              }
              onClose={closeMenu}
            />
          )}
        </div>
      </div>
    );
  };

  // Sort and filter projects in Full-Screen Explorer modal
  const filteredExplorerProjects = useMemo(() => {
    let result = [...projects];

    if (explorerQuery.trim()) {
      const q = explorerQuery.toLowerCase();
      result = result.filter((p) => p.title.toLowerCase().includes(q));
    }

    if (explorerFilter !== "All") {
      result = result.filter((p) => p.status === explorerFilter);
    }

    if (explorerSort === "newest") {
      result.sort((a, b) => new Date(b.lastUpdated).getTime() - new Date(a.lastUpdated).getTime());
    } else if (explorerSort === "oldest") {
      result.sort((a, b) => new Date(a.lastUpdated).getTime() - new Date(b.lastUpdated).getTime());
    } else if (explorerSort === "alpha") {
      result.sort((a, b) => a.title.localeCompare(b.title));
    }

    return result;
  }, [projects, explorerQuery, explorerFilter, explorerSort]);

  // ── Collapsed state: icon strip ──
  if (!isOpen) {
    return (
      <aside className="sidebar sidebar-collapsed">
        <div className="sidebar-collapsed-icons">
          <button
            className="sidebar-icon-btn"
            onClick={onToggle}
            title="Expand sidebar"
          >
            <IconMenu size={16} />
          </button>
          {actions.map((action, i) => (
            <button
              key={i}
              className={`sidebar-icon-btn ${action.active ? "active" : ""}`}
              onClick={action.onClick}
              title={action.label}
            >
              {action.icon}
            </button>
          ))}
        </div>
        <div
          className="sidebar-collapsed-bottom"
          title={connected ? "Connected" : "Disconnected"}
        />
      </aside>
    );
  }

  // ── Open state ──
  return (
    <aside className="sidebar">
      {/* Header: brand + collapse */}
      <div className="sidebar-header">
        <span
          className="sidebar-brand"
          title={connected ? "Connected" : "Disconnected"}
        >
          Porta
        </span>
        <button
          className="sidebar-icon-btn"
          onClick={onToggle}
          title="Collapse sidebar"
        >
          <IconMenu size={16} />
        </button>
      </div>

      {/* Prominent "+ New Conversation" Action Button */}
      <button className="new-conversation-btn" onClick={onNew}>
        <IconPlus size={14} />
        <span>New Conversation</span>
      </button>

      {/* Flat Navigation Rows for Conversation History and Scheduled Tasks */}
      <div className="flat-nav-rows">
        <button
          className="flat-nav-row-btn"
          onClick={() => {
            setSearchOpen(true);
            setTimeout(() => searchInputRef.current?.focus(), 50);
          }}
        >
          <IconHistory size={15} className="flat-nav-icon" />
          <span className="flat-nav-label">Conversation History</span>
        </button>
        <button
          className="flat-nav-row-btn"
          onClick={() => {
            alert("Scheduled Tasks feature coming soon! You can configure background tasks via the schedule tool.");
          }}
        >
          <IconClock size={15} className="flat-nav-icon" />
          <span className="flat-nav-label">Scheduled Tasks</span>
        </button>
      </div>

      {/* Main scrolling list container */}
      <div className="sidebar-list">
        {/* Pinned Conversations Section */}
        {pinnedConversations.length > 0 && (
          <div className="sidebar-section-group pinned-conversations-group">
            <div className="sidebar-section-header">
              <span className="sidebar-section-title">Pinned Conversations</span>
            </div>
            <div className="conversations-flat-list pinned-list">
              {pinnedConversations.map(renderItem)}
            </div>
            <div className="sidebar-divider" />
          </div>
        )}

        {/* Projects Section Header */}
        <div className="sidebar-section-header">
          <span className="sidebar-section-title">Projects</span>
          <div className="sidebar-section-actions">
            <button
              onClick={() => setProjectsExplorerOpen(true)}
              className="sidebar-section-btn"
              title="Filter / View all"
            >
              <IconFilter size={13} />
            </button>
            <button
              onClick={() => {
                setNewProjTitle("");
                setNewProjStatus("Idle");
                setCreateProjectOpen(true);
              }}
              className="sidebar-section-btn"
              title="Create new project"
            >
              <IconFolderPlus size={13} />
            </button>
          </div>
        </div>

        {/* 1. Blocked Projects (Collapsible) */}
        <div className="workspace-group">
          <button
            className="workspace-group-header"
            onClick={() => toggleProjectGroup("Blocked")}
          >
            <span className={`workspace-group-chevron ${projectCollapse["Blocked"] ? "collapsed" : ""}`}>
              ▾
            </span>
            <IconAlertCircle size={13} className="status-icon blocked-icon" />
            <span className="workspace-group-name">Blocked</span>
            <span className="workspace-group-count">{blockedProjects.length}</span>
          </button>
          {!projectCollapse["Blocked"] && (
            <div className="workspace-group-items">
              {blockedProjects.map(renderProjectItem)}
            </div>
          )}
        </div>

        {/* 2. In Progress Projects (Collapsible) */}
        <div className="workspace-group">
          <button
            className="workspace-group-header"
            onClick={() => toggleProjectGroup("In Progress")}
          >
            <span className={`workspace-group-chevron ${projectCollapse["In Progress"] ? "collapsed" : ""}`}>
              ▾
            </span>
            <IconClock size={13} className="status-icon inprogress-icon" />
            <span className="workspace-group-name">In Progress</span>
            <span className="workspace-group-count">{inProgressProjects.length}</span>
          </button>
          {!projectCollapse["In Progress"] && (
            <div className="workspace-group-items">
              {inProgressProjects.map(renderProjectItem)}
            </div>
          )}
        </div>

        {/* 3. Idle Projects (Collapsible with "See all") */}
        <div className="workspace-group">
          <button
            className="workspace-group-header"
            onClick={() => toggleProjectGroup("Idle")}
          >
            <span className={`workspace-group-chevron ${projectCollapse["Idle"] ? "collapsed" : ""}`}>
              ▾
            </span>
            <IconCheckCircle size={13} className="status-icon idle-icon" />
            <span className="workspace-group-name">Idle</span>
            <span className="workspace-group-count">{idleProjects.length}</span>
          </button>
          {!projectCollapse["Idle"] && (
            <div className="workspace-group-items">
              {idleProjects.slice(0, PREVIEW_COUNT).map(renderProjectItem)}
              {idleProjects.length > PREVIEW_COUNT && (
                <button
                  className="see-all-btn"
                  onClick={() => {
                    setExplorerFilter("Idle");
                    setProjectsExplorerOpen(true);
                  }}
                >
                  See all ({idleProjects.length})
                </button>
              )}
            </div>
          )}
        </div>

        <div className="sidebar-divider" />

        {/* Conversations Section Header */}
        <div className="sidebar-section-header">
          <span className="sidebar-section-title">Conversations</span>
        </div>

        {/* Flat Conversations List (Excluding Pinned) */}
        {loading && conversations.length === 0 ? (
          <div style={{ display: "flex", justifyContent: "center", padding: 20 }}>
            <div className="loading-spinner" />
          </div>
        ) : (
          <div className="conversations-flat-list">
            {nonPinnedConversations.map(renderItem)}
          </div>
        )}
      </div>

      {/* Sidebar Footer with Settings and Logout */}
      <div className="sidebar-footer">
        <button className="sidebar-footer-btn" onClick={onSettings} title="Settings">
          <IconGear size={15} />
          <span>Settings</span>
        </button>
        {onLogout && (
          <button className="sidebar-footer-btn logout" onClick={onLogout} title="Logout">
            <svg
              width="15"
              height="15"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
            <span>Logout</span>
          </button>
        )}
      </div>

      {/* ── Search Modal with Unified Results ── */}
      {searchOpen && (
        <div className="search-modal-overlay" onClick={closeSearch}>
          <div
            className="search-modal"
            onClick={(e) => e.stopPropagation()}
            onKeyDown={(e) => {
              if (e.key === "Escape") closeSearch();
            }}
          >
            <div className="search-modal-header">
              <IconSearch size={16} className="search-modal-icon" />
              <input
                ref={searchInputRef}
                className="search-modal-input"
                type="text"
                placeholder="Search conversations or projects..."
                value={searchQuery}
                onChange={(e) => handleSearchInput(e.target.value)}
                autoFocus
              />
              <button className="search-modal-close" onClick={closeSearch}>
                <IconX size={13} />
              </button>
            </div>
            <div className="search-modal-results">
              {searching ? (
                <div className="search-modal-status">
                  <div className="loading-spinner" />
                </div>
              ) : searchQuery.trim() === "" ? (
                <div className="search-modal-status">
                  Type to search across both conversations and projects
                </div>
              ) : (
                <>
                  {/* Local Project Matches */}
                  {matchedLocalProjects.length > 0 && (
                    <div className="search-category-group">
                      <div className="search-category-title">Projects Matched</div>
                      {matchedLocalProjects.map((p) => (
                        <button
                          key={p.id}
                          className="search-result-item"
                          onClick={() => {
                            setActiveProjectId(p.id);
                            closeSearch();
                          }}
                        >
                          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                            <span style={{ color: "var(--accent)", display: "inline-flex" }}><IconFolder size={13} /></span>
                            <div className="search-result-title">{p.title}</div>
                          </div>
                          <div className="search-result-meta" style={{ display: "inline-flex", gap: 6, alignItems: "center" }}>
                            <span className={`status-badge-inline ${(p.status || "idle").toLowerCase().replace(" ", "-")}`}>
                              {p.status}
                            </span>
                            {p.timeBadge && <span>· {p.timeBadge}</span>}
                          </div>
                        </button>
                      ))}
                    </div>
                  )}

                  {/* Conversation Matches */}
                  <div className="search-category-group" style={{ marginTop: 8 }}>
                    <div className="search-category-title">Conversations Matched</div>
                    {searchResults === null || searchResults.length === 0 ? (
                      <div className="search-modal-status" style={{ padding: 12 }}>
                        No conversations match "{searchQuery}"
                      </div>
                    ) : (
                      searchResults.map((result) => (
                        <button
                          key={result.id}
                          className="search-result-item"
                          onClick={() => {
                            onSelect(result.id);
                            closeSearch();
                          }}
                        >
                          <div className="search-result-title">{result.title}</div>
                          <div className="search-result-snippets">
                            {result.snippets.map((s, i) => (
                              <div key={i} className="search-result-snippet">
                                {s}
                              </div>
                            ))}
                          </div>
                          <div className="search-result-meta">
                            {result.matchCount} match
                            {result.matchCount !== 1 ? "es" : ""}
                          </div>
                        </button>
                      ))
                    )}
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ── Create Project Modal ── */}
      {createProjectOpen && (
        <div className="modal-overlay-generic" onClick={() => setCreateProjectOpen(false)}>
          <div className="custom-modal-content" onClick={(e) => e.stopPropagation()}>
            <div className="custom-modal-header">
              <h3>Create New Project</h3>
              <button className="close-btn" onClick={() => setCreateProjectOpen(false)}>
                <IconX size={14} />
              </button>
            </div>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (newProjTitle.trim()) {
                  createProject(newProjTitle.trim(), newProjStatus);
                  setCreateProjectOpen(false);
                }
              }}
            >
              <div className="form-group">
                <label>Project Title</label>
                <input
                  type="text"
                  placeholder="e.g. Building API documentation..."
                  value={newProjTitle}
                  onChange={(e) => setNewProjTitle(e.target.value)}
                  required
                  autoFocus
                />
              </div>
              <div className="form-group">
                <label>Initial Status</label>
                <select
                  value={newProjStatus}
                  onChange={(e) => setNewProjStatus(e.target.value as ProjectStatus)}
                >
                  <option value="Idle">Idle</option>
                  <option value="In Progress">In Progress</option>
                  <option value="Blocked">Blocked</option>
                </select>
              </div>
              <div className="custom-modal-actions">
                <button type="button" className="btn btn-secondary" onClick={() => setCreateProjectOpen(false)}>
                  Cancel
                </button>
                <button type="submit" className="btn btn-primary">
                  Create Project
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ── Edit Project Modal ── */}
      {selectedEditProject && (
        <div className="modal-overlay-generic" onClick={() => setSelectedEditProject(null)}>
          <div className="custom-modal-content" onClick={(e) => e.stopPropagation()}>
            <div className="custom-modal-header">
              <h3>Project Settings</h3>
              <button className="close-btn" onClick={() => setSelectedEditProject(null)}>
                <IconX size={14} />
              </button>
            </div>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (editProjTitle.trim()) {
                  updateProjectTitle(selectedEditProject.id, editProjTitle.trim());
                  updateProjectStatus(selectedEditProject.id, editProjStatus);
                  setSelectedEditProject(null);
                }
              }}
            >
              <div className="form-group">
                <label>Project Title</label>
                <input
                  type="text"
                  value={editProjTitle}
                  onChange={(e) => setEditProjTitle(e.target.value)}
                  required
                />
              </div>
              <div className="form-group">
                <label>Status</label>
                <select
                  value={editProjStatus}
                  onChange={(e) => setEditProjStatus(e.target.value as ProjectStatus)}
                >
                  <option value="Idle">Idle</option>
                  <option value="In Progress">In Progress</option>
                  <option value="Blocked">Blocked</option>
                </select>
              </div>
              <div className="custom-modal-actions">
                <button
                  type="button"
                  className="btn btn-danger"
                  onClick={() => {
                    if (confirm("Delete this project? This will not delete any associated chats.")) {
                      deleteProject(selectedEditProject.id);
                      setSelectedEditProject(null);
                    }
                  }}
                >
                  Delete Project
                </button>
                <div style={{ display: "flex", gap: 8 }}>
                  <button type="button" className="btn btn-secondary" onClick={() => setSelectedEditProject(null)}>
                    Cancel
                  </button>
                  <button type="submit" className="btn btn-primary">
                    Save Changes
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ── Fullscreen Projects Explorer Modal ── */}
      {projectsExplorerOpen && (
        <div className="explorer-modal-overlay" onClick={() => setProjectsExplorerOpen(false)}>
          <div className="explorer-modal" onClick={(e) => e.stopPropagation()}>
            {/* Header */}
            <div className="explorer-modal-header">
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <span style={{ color: "var(--accent)", display: "inline-flex" }}><IconFolder size={20} /></span>
                <h2>Projects Explorer ({projects.length})</h2>
              </div>
              <button className="explorer-close-btn" onClick={() => setProjectsExplorerOpen(false)}>
                <IconX size={18} />
              </button>
            </div>

            {/* Toolbar: Search, Filters, Sort */}
            <div className="explorer-toolbar">
              <div className="explorer-search-wrapper">
                <IconSearch size={14} className="toolbar-search-icon" />
                <input
                  type="text"
                  placeholder="Search projects..."
                  value={explorerQuery}
                  onChange={(e) => setExplorerQuery(e.target.value)}
                />
              </div>

              <div className="explorer-filters">
                <div className="filter-group">
                  <span className="toolbar-label">Status:</span>
                  <div className="filter-tags">
                    {(["All", "Blocked", "In Progress", "Idle"] as const).map((tag) => (
                      <button
                        key={tag}
                        className={`filter-tag ${explorerFilter === tag ? "active" : ""}`}
                        onClick={() => setExplorerFilter(tag)}
                      >
                        {tag}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="filter-group">
                  <span className="toolbar-label">Sort:</span>
                  <select
                    value={explorerSort}
                    onChange={(e) => setExplorerSort(e.target.value as any)}
                    className="toolbar-select"
                  >
                    <option value="newest">Last Updated</option>
                    <option value="oldest">Oldest First</option>
                    <option value="alpha">Alphabetical</option>
                  </select>
                </div>
              </div>
            </div>

            {/* Grid display of all projects */}
            <div className="explorer-grid">
              {filteredExplorerProjects.length === 0 ? (
                <div className="explorer-empty">
                  <IconFolderPlus size={48} className="empty-icon" />
                  <p>No projects match your filter criteria.</p>
                </div>
              ) : (
                filteredExplorerProjects.map((p) => {
                  const isCurrentActive = p.id === activeProjectId;
                  return (
                    <div
                      key={p.id}
                      className={`explorer-card ${(p.status || "idle").toLowerCase().replace(" ", "-")} ${isCurrentActive ? "active" : ""}`}
                    >
                      <div className="card-top">
                        <span className={`status-badge ${(p.status || "idle").toLowerCase().replace(" ", "-")}`}>
                          {p.status === "Blocked" && <span style={{ marginRight: 3, display: "inline-flex" }}><IconAlertCircle size={10} /></span>}
                          {p.status === "In Progress" && <span style={{ marginRight: 3, display: "inline-flex" }}><IconClock size={10} /></span>}
                          {p.status === "Idle" && <span style={{ marginRight: 3, display: "inline-flex" }}><IconCheckCircle size={10} /></span>}
                          {p.status}
                        </span>
                        <span className="card-time">Updated {new Date(p.lastUpdated).toLocaleDateString()}</span>
                      </div>
                      <h4 className="card-title" title={p.title}>
                        {p.title}
                      </h4>
                      <div className="card-actions">
                        <button
                          className={`btn-action select-btn ${isCurrentActive ? "active" : ""}`}
                          onClick={() => {
                            setActiveProjectId(p.id);
                            // Highlight chat
                            const matched = conversations.find(
                              (c) => c.summary?.summary?.toLowerCase() === p.title?.toLowerCase()
                            );
                            if (matched) onSelect(matched.id);
                          }}
                        >
                          {isCurrentActive ? "Active Project" : "Activate"}
                        </button>
                        <div style={{ display: "flex", gap: 4 }}>
                          <button
                            className="btn-action-icon"
                            onClick={() => {
                              setEditProjTitle(p.title);
                              setEditProjStatus(p.status);
                              setSelectedEditProject(p);
                            }}
                            title="Edit project settings"
                          >
                            <IconGear size={12} />
                          </button>
                          <button
                            className="btn-action-icon danger"
                            onClick={() => {
                              if (confirm("Delete this project?")) {
                                deleteProject(p.id);
                              }
                            }}
                            title="Delete project"
                          >
                            <IconX size={12} />
                          </button>
                        </div>
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      )}
    </aside>
  );
}
