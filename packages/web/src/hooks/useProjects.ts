import { useState, useEffect } from "react";
import type { Project, ProjectStatus } from "../types";

const LOCAL_STORAGE_KEY = "porta:projects";
const ACTIVE_PROJECT_KEY = "porta:active_project_id";

// Pre-populated high-fidelity mock data matching the screenshot
const INITIAL_PROJECTS: Project[] = [
  {
    id: "p-blocked-1",
    title: "Analyzing Odoo 8 Perpetual Inventory Logic & Verification",
    status: "Blocked",
    lastUpdated: new Date(Date.now() - 3600000 * 2).toISOString(), // 2h ago
  },
  {
    id: "p-inprogress-1",
    title: "Fixing Porta Android Auto Callback & Media Session Synchronization",
    status: "In Progress",
    lastUpdated: new Date().toISOString(), // just now
  },
  {
    id: "p-inprogress-2",
    title: "Integrating Porta With Rokid Glass CXR-L Bluetooth Stack",
    status: "In Progress",
    lastUpdated: new Date(Date.now() - 3600000 * 4).toISOString(), // 4h ago
  },
  {
    id: "p-idle-1",
    title: "Investigating Fail2ban Blocklist Synchronization & Logs",
    status: "Idle",
    lastUpdated: new Date(Date.now() - 3600000 * 5).toISOString(),
    hasIndicator: true, // blue dot
  },
  {
    id: "p-idle-2",
    title: "Updating Local Repository Packages & Locking Dependencies",
    status: "Idle",
    lastUpdated: new Date(Date.now() - 3600000 * 18).toISOString(),
    timeBadge: "18h",
  },
  {
    id: "p-idle-3",
    title: "Running The Web Dashboard Profiler & Checking Memory Leak",
    status: "Idle",
    lastUpdated: new Date(Date.now() - 3600000 * 6).toISOString(),
    hasIndicator: true, // blue dot
  },
  {
    id: "p-idle-4",
    title: "Tracing Peterongan Musical Notation Parser & Engine Logs",
    status: "Idle",
    lastUpdated: new Date(Date.now() - 3600000 * 22).toISOString(),
    timeBadge: "22h",
  },
  {
    id: "p-idle-5",
    title: "Diagnosing Infinia Server Latency Spikes Under Load",
    status: "Idle",
    lastUpdated: new Date(Date.now() - 3600000 * 48).toISOString(),
    timeBadge: "2d",
  },
  {
    id: "p-idle-6",
    title: "Displaying Last Login Odoo System Log Audit Trail",
    status: "Idle",
    lastUpdated: new Date(Date.now() - 3600000 * 48).toISOString(),
    timeBadge: "2d",
  },
];

// Fill up to 100 idle projects with beautiful tech task names
const generateIdleProjects = () => {
  const extraIdleCount = 91; // 100 total projects, 9 are already defined
  const templates = [
    "Refactoring Auth Middleware & Token Rotation",
    "Optimizing Largest Contentful Paint (LCP) performance",
    "Translating Web Extension UI & Popup Content",
    "Implementing SQLite Shard Backups & Maintenance Schedule",
    "Upgrading TailwindCSS to Version 4 & Linting Classes",
    "Setting up Firebase Crashlytics on Android Debug APK",
    "Auditing Ensembl Database Variant Consequences Fetcher",
    "Profiling PWA Service Worker Cache Expiration Logic",
    "Resolving Dart Tooling Daemon WS Port Collisions",
    "Debugging Voice Input Noise Reduction Thresholds",
    "Reviewing Firebase Data Connect PostgreSQL Relations",
    "Testing WebUSB Connectivity with Rokid AR Glasses",
    "Improving HSL Adaptive Colors contrast for Accessibility",
    "Benchmarking MMseqs2 Sequence Similarity Searches",
    "Updating CLI build scripts with UV Package Manager",
    "Investigating memory leaks inside local Proxy connections",
    "Generating OpenAPI schema specifications from Express routes",
    "Formatting CSS layout systems for Ultra-Wide displays",
    "Pre-indexing Cloud Firestore databases with Composite Indexes",
    "Drafting App Store description and Fastlane automated metadata",
  ];

  const list = [...INITIAL_PROJECTS];
  for (let i = 0; i < extraIdleCount; i++) {
    const template = templates[i % templates.length];
    const index = i + 7;
    const daysAgo = Math.floor(index / 3) + 2;
    list.push({
      id: `p-idle-${index}`,
      title: `${template} (Sprint #${Math.ceil(index / 10)})`,
      status: "Idle",
      lastUpdated: new Date(Date.now() - 3600000 * 24 * daysAgo).toISOString(),
      timeBadge: `${daysAgo}d`,
    });
  }
  return list;
};

export function useProjects() {
  const [projects, setProjects] = useState<Project[]>(() => {
    try {
      const stored = localStorage.getItem(LOCAL_STORAGE_KEY);
      if (stored) {
        return JSON.parse(stored);
      }
    } catch (e) {
      console.error("Failed to load projects", e);
    }
    const defaults = generateIdleProjects();
    localStorage.setItem(LOCAL_STORAGE_KEY, JSON.stringify(defaults));
    return defaults;
  });

  const [activeProjectId, setActiveProjectId] = useState<string | null>(() => {
    try {
      const stored = localStorage.getItem(ACTIVE_PROJECT_KEY);
      if (stored) return stored;
    } catch {}
    // Default to the first in-progress project: Fixing Porta Android Auto...
    return "p-inprogress-1";
  });

  useEffect(() => {
    try {
      localStorage.setItem(LOCAL_STORAGE_KEY, JSON.stringify(projects));
    } catch (e) {
      console.error("Failed to save projects", e);
    }
  }, [projects]);

  useEffect(() => {
    try {
      if (activeProjectId) {
        localStorage.setItem(ACTIVE_PROJECT_KEY, activeProjectId);
      } else {
        localStorage.removeItem(ACTIVE_PROJECT_KEY);
      }
    } catch {}
  }, [activeProjectId]);

  const createProject = (title: string, status: ProjectStatus): Project => {
    const newProj: Project = {
      id: `p-custom-${Date.now()}`,
      title,
      status,
      lastUpdated: new Date().toISOString(),
      hasIndicator: status === "Idle" ? true : undefined,
    };
    setProjects((prev) => [newProj, ...prev]);
    return newProj;
  };

  const updateProjectStatus = (id: string, status: ProjectStatus) => {
    setProjects((prev) =>
      prev.map((p) =>
        p.id === id
          ? { ...p, status, lastUpdated: new Date().toISOString() }
          : p
      )
    );
  };

  const deleteProject = (id: string) => {
    setProjects((prev) => prev.filter((p) => p.id !== id));
    if (activeProjectId === id) {
      setActiveProjectId(null);
    }
  };

  const updateProjectTitle = (id: string, title: string) => {
    setProjects((prev) =>
      prev.map((p) =>
        p.id === id
          ? { ...p, title, lastUpdated: new Date().toISOString() }
          : p
      )
    );
  };

  return {
    projects,
    activeProjectId,
    setActiveProjectId,
    createProject,
    updateProjectStatus,
    updateProjectTitle,
    deleteProject,
  };
}
