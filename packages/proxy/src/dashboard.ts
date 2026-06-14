/**
 * Porta Dashboard — System Monitor & Control Panel
 *
 * Serves a web-based dashboard at /dashboard with:
 * - Real-time LSP status monitoring
 * - Active conversations overview
 * - Memory/CPU metrics
 * - Connected clients
 * - Live log viewer via WebSocket
 * - Control panel (restart, auto-approve toggle)
 */

import type { Hono } from "hono";
import { existsSync, readFileSync, utimesSync, openSync, readSync, closeSync, statSync } from "node:fs";
import { join } from "node:path";
import { discovery } from "./routing.js";
import { isAutoApproveEnabled } from "./metadata.js";

const DASHBOARD_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Porta — Dashboard</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg-primary: #0a0a0f;
      --bg-card: #12121a;
      --bg-card-hover: #1a1a27;
      --bg-input: #1e1e2e;
      --border: #2a2a3e;
      --border-active: #818cf8;
      --text-primary: #f1f5f9;
      --text-secondary: #94a3b8;
      --text-muted: #64748b;
      --accent: #818cf8;
      --accent-glow: rgba(129, 140, 248, 0.15);
      --success: #34d399;
      --warning: #fbbf24;
      --error: #f87171;
      --gradient-1: linear-gradient(135deg, #818cf8 0%, #a78bfa 100%);
      --gradient-2: linear-gradient(135deg, #34d399 0%, #2dd4bf 100%);
      --radius: 12px;
      --shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
    }

    * { margin: 0; padding: 0; box-sizing: border-box; }

    body {
      font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
      background: var(--bg-primary);
      color: var(--text-primary);
      min-height: 100vh;
      overflow-x: hidden;
    }

    /* ── Header ── */
    .header {
      background: linear-gradient(180deg, rgba(129, 140, 248, 0.08) 0%, transparent 100%);
      border-bottom: 1px solid var(--border);
      padding: 16px 24px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      position: sticky;
      top: 0;
      z-index: 100;
      backdrop-filter: blur(12px);
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .logo {
      width: 32px;
      height: 32px;
      background: var(--gradient-1);
      border-radius: 8px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 700;
      font-size: 16px;
    }

    .header-title {
      font-size: 18px;
      font-weight: 600;
      background: var(--gradient-1);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }

    .header-subtitle {
      font-size: 12px;
      color: var(--text-muted);
      margin-left: 4px;
    }

    .connection-status {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: var(--text-secondary);
    }

    .status-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--error);
      transition: background 0.3s;
    }

    .status-dot.connected { background: var(--success); box-shadow: 0 0 8px var(--success); }
    .status-dot.connecting { background: var(--warning); animation: pulse 1.5s infinite; }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.4; }
    }

    /* ── Layout ── */
    .dashboard {
      max-width: 1400px;
      margin: 0 auto;
      padding: 24px;
      display: grid;
      grid-template-columns: 1fr 1fr 1fr;
      gap: 16px;
    }

    @media (max-width: 1024px) {
      .dashboard { grid-template-columns: 1fr 1fr; }
    }

    @media (max-width: 640px) {
      .dashboard { grid-template-columns: 1fr; }
    }

    /* ── Cards ── */
    .card {
      background: var(--bg-card);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 20px;
      transition: border-color 0.2s, transform 0.2s;
    }

    .card:hover {
      border-color: rgba(129, 140, 248, 0.3);
    }

    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 16px;
    }

    .card-title {
      font-size: 14px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: var(--text-secondary);
    }

    .card-icon { font-size: 20px; }

    .card.span-2 { grid-column: span 2; }
    .card.span-3 { grid-column: span 3; }

    /* ── Metric Cards ── */
    .metric-value {
      font-size: 36px;
      font-weight: 700;
      font-family: 'JetBrains Mono', monospace;
      line-height: 1;
      margin-bottom: 4px;
    }

    .metric-value.accent { color: var(--accent); }
    .metric-value.success { color: var(--success); }
    .metric-value.warning { color: var(--warning); }

    .metric-label {
      font-size: 12px;
      color: var(--text-muted);
    }

    .metric-row {
      display: flex;
      gap: 24px;
      flex-wrap: wrap;
    }

    .metric-item {
      flex: 1;
      min-width: 80px;
    }

    /* ── LS Instances ── */
    .ls-list {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .ls-item {
      background: var(--bg-input);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 12px 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      transition: border-color 0.2s;
    }

    .ls-item:hover { border-color: var(--accent); }

    .ls-info { display: flex; flex-direction: column; gap: 2px; }

    .ls-name {
      font-size: 14px;
      font-weight: 500;
      font-family: 'JetBrains Mono', monospace;
    }

    .ls-meta {
      font-size: 11px;
      color: var(--text-muted);
    }

    .ls-badge {
      font-size: 11px;
      padding: 2px 8px;
      border-radius: 4px;
      font-weight: 500;
    }

    .ls-badge.hub { background: rgba(129, 140, 248, 0.15); color: var(--accent); }
    .ls-badge.ide { background: rgba(52, 211, 153, 0.15); color: var(--success); }

    /* ── Log Viewer ── */
    .log-viewer {
      background: #0d0d14;
      border: 1px solid var(--border);
      border-radius: 8px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 12px;
      line-height: 1.6;
      padding: 12px 16px;
      max-height: 300px;
      overflow-y: auto;
      overflow-x: hidden;
      scroll-behavior: smooth;
    }

    .log-line {
      white-space: pre-wrap;
      word-break: break-all;
    }

    .log-line .ts { color: var(--text-muted); }
    .log-line .info { color: var(--accent); }
    .log-line .warn { color: var(--warning); }
    .log-line .err { color: var(--error); }
    .log-line .ok { color: var(--success); }

    /* ── Controls ── */
    .controls {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .control-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 0;
    }

    .control-label {
      font-size: 14px;
      color: var(--text-primary);
    }

    .control-desc {
      font-size: 11px;
      color: var(--text-muted);
    }

    .toggle {
      position: relative;
      width: 44px;
      height: 24px;
      background: var(--bg-input);
      border: 1px solid var(--border);
      border-radius: 12px;
      cursor: pointer;
      transition: background 0.3s, border-color 0.3s;
    }

    .toggle.active {
      background: var(--accent);
      border-color: var(--accent);
    }

    .toggle::after {
      content: '';
      position: absolute;
      width: 18px;
      height: 18px;
      border-radius: 50%;
      background: white;
      top: 2px;
      left: 2px;
      transition: transform 0.3s;
    }

    .toggle.active::after { transform: translateX(20px); }

    .btn {
      padding: 8px 16px;
      border-radius: 8px;
      border: 1px solid var(--border);
      background: var(--bg-input);
      color: var(--text-primary);
      font-family: inherit;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.2s;
    }

    .btn:hover { border-color: var(--accent); background: var(--accent-glow); }
    .btn.warning:hover { border-color: var(--warning); background: rgba(251, 191, 36, 0.15); }
    .btn.danger:hover { border-color: var(--error); background: rgba(248, 113, 113, 0.15); }

    /* ── Conversations ── */
    .conv-list { display: flex; flex-direction: column; gap: 6px; }

    .conv-item {
      background: var(--bg-input);
      border-radius: 6px;
      padding: 8px 12px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: 13px;
    }

    .conv-id {
      font-family: 'JetBrains Mono', monospace;
      font-size: 11px;
      color: var(--text-secondary);
    }

    .conv-status {
      width: 6px;
      height: 6px;
      border-radius: 50%;
    }

    .conv-status.running { background: var(--success); box-shadow: 0 0 6px var(--success); }
    .conv-status.idle { background: var(--text-muted); }

    /* ── Empty state ── */
    .empty {
      text-align: center;
      padding: 24px;
      color: var(--text-muted);
      font-size: 13px;
    }

    /* Scrollbar */
    ::-webkit-scrollbar { width: 6px; }
    ::-webkit-scrollbar-track { background: transparent; }
    ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
    ::-webkit-scrollbar-thumb:hover { background: var(--text-muted); }
  </style>
</head>
<body>

  <header class="header">
    <div class="header-left">
      <div class="logo">P</div>
      <span class="header-title">Porta Dashboard</span>
      <span class="header-subtitle">System Monitor</span>
    </div>
    <div class="connection-status">
      <div class="status-dot" id="wsStatus"></div>
      <span id="wsLabel">Connecting...</span>
    </div>
  </header>

  <div class="dashboard">
    <!-- Row 1: Key Metrics -->
    <div class="card">
      <div class="card-header">
        <span class="card-title">Language Servers</span>
        <span class="card-icon">🔧</span>
      </div>
      <div class="metric-value accent" id="lsCount">-</div>
      <div class="metric-label">Active instances</div>
    </div>

    <div class="card">
      <div class="card-header">
        <span class="card-title">Uptime</span>
        <span class="card-icon">⏱️</span>
      </div>
      <div class="metric-value success" id="uptime">-</div>
      <div class="metric-label">Proxy uptime</div>
    </div>

    <div class="card">
      <div class="card-header">
        <span class="card-title">Memory</span>
        <span class="card-icon">💾</span>
      </div>
      <div class="metric-value warning" id="memory">-</div>
      <div class="metric-label" id="memoryLabel">RSS</div>
    </div>

    <!-- Row 2: LS Instances + Conversations -->
    <div class="card span-2">
      <div class="card-header">
        <span class="card-title">LS Instances</span>
        <span class="card-icon">🖥️</span>
      </div>
      <div class="ls-list" id="lsList">
        <div class="empty">Loading...</div>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <span class="card-title">Controls</span>
        <span class="card-icon">⚙️</span>
      </div>
      <div class="controls">
        <div class="control-row">
          <div>
            <div class="control-label">Auto-Approve</div>
            <div class="control-desc">Skip agent confirmation prompts</div>
          </div>
          <div class="toggle" id="autoApproveToggle" onclick="toggleAutoApprove()"></div>
        </div>
        <hr style="border: none; border-top: 1px solid var(--border);">
        <div class="control-row">
          <div>
            <div class="control-label">System Load</div>
            <div class="control-desc" id="loadAvg">-</div>
          </div>
        </div>
        <button class="btn" onclick="refreshAll()">🔄 Refresh Now</button>
        <button class="btn danger" onclick="rediscover()">♻️ Re-discover LS</button>
        <button class="btn warning" onclick="restartService()" style="width: 100%; margin-top: 8px;">⚡ Restart Porta Service</button>
      </div>
    </div>

    <!-- Row 3: Conversations -->
    <div class="card span-3">
      <div class="card-header">
        <span class="card-title">Recent Conversations</span>
        <span class="card-icon">💬</span>
      </div>
      <div class="conv-list" id="convList">
        <div class="empty">Loading...</div>
      </div>
    </div>

    <!-- Row 4: Log Viewer -->
    <div class="card span-3">
      <div class="card-header" style="display: flex; justify-content: space-between; align-items: center;">
        <div style="display: flex; align-items: center; gap: 8px;">
          <span class="card-title" id="logTitle">Activity Log</span>
          <span class="card-icon">📋</span>
        </div>
        <div style="display: flex; gap: 6px; align-items: center;">
          <select id="logSourceSelect" onchange="changeLogSource(this.value)" style="background: var(--bg-input); color: var(--text-primary); border: 1px solid var(--border); padding: 6px 12px; border-radius: 6px; font-size: 12px; outline: none; cursor: pointer; font-family: inherit;">
            <option value="activity">Browser Events</option>
            <option value="proxy">Proxy Log (proxy.log)</option>
            <option value="web">Web Log (web.log)</option>
            <option value="telegram">Telegram Log (telegram.log)</option>
          </select>
          <button class="btn" style="padding: 6px 12px; font-size: 12px;" onclick="refreshLogs()">🔄 Refresh Log</button>
        </div>
      </div>
      <div class="log-viewer" id="logViewer">
        <div class="log-line"><span class="ts">[--:--:--]</span> Initializing...</div>
      </div>
    </div>
  </div>

  <script>
    const BASE = location.origin;
    const API = BASE + '/dashboard/api';
    let authToken = localStorage.getItem('porta_token') || '';
    let pollOk = false;

    function headers() {
      const h = { 'Content-Type': 'application/json', 'X-Porta-Request': '1' };
      if (authToken) h['Authorization'] = 'Bearer ' + authToken;
      return h;
    }

    async function fetchJSON(path) {
      const res = await fetch(API + path, { headers: headers() });
      if (res.status === 401) {
        const token = prompt('Enter Porta auth token:');
        if (token) {
          authToken = token;
          localStorage.setItem('porta_token', token);
          return fetchJSON(path);
        }
        throw new Error('Auth required');
      }
      return res.json();
    }

    async function postJSON(path, body = {}) {
      const res = await fetch(API + path, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify(body)
      });
      return res.json();
    }

    function formatUptime(seconds) {
      const d = Math.floor(seconds / 86400);
      const h = Math.floor((seconds % 86400) / 3600);
      const m = Math.floor((seconds % 3600) / 60);
      const s = Math.floor(seconds % 60);
      if (d > 0) return d + 'd ' + h + 'h';
      if (h > 0) return h + 'h ' + m + 'm';
      if (m > 0) return m + 'm ' + s + 's';
      return s + 's';
    }

    function formatBytes(bytes) {
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
      return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }

    const browserLogs = [];

    function addLog(message, level = 'info') {
      const now = new Date().toLocaleTimeString('en-US', { hour12: false });
      const logEntry = { now, message, level };
      browserLogs.push(logEntry);
      if (browserLogs.length > 200) browserLogs.shift();

      const select = document.getElementById('logSourceSelect');
      if (select && select.value === 'activity') {
        appendLogToUI(logEntry);
        const viewer = document.getElementById('logViewer');
        if (viewer) {
          while (viewer.children.length > 200) viewer.removeChild(viewer.firstChild);
          viewer.scrollTop = viewer.scrollHeight;
        }
      }
    }

    function appendLogToUI(entry) {
      const viewer = document.getElementById('logViewer');
      if (!viewer) return;
      const line = document.createElement('div');
      line.className = 'log-line';
      line.innerHTML = '<span class="ts">[' + entry.now + ']</span> <span class="' + entry.level + '">' + escapeHtml(entry.message) + '</span>';
      viewer.appendChild(line);
    }

    function changeLogSource(value) {
      const viewer = document.getElementById('logViewer');
      if (!viewer) return;
      viewer.innerHTML = '';

      if (value === 'activity') {
        document.getElementById('logTitle').textContent = 'Activity Log';
        browserLogs.forEach(appendLogToUI);
        viewer.scrollTop = viewer.scrollHeight;
      } else {
        const fileNames = {
          proxy: 'Proxy Log (proxy.log)',
          web: 'Web Log (web.log)',
          telegram: 'Telegram Log (telegram.log)'
        };
        document.getElementById('logTitle').textContent = fileNames[value] || 'System Log';
        fetchLogFiles(value);
      }
    }

    async function fetchLogFiles(type) {
      const viewer = document.getElementById('logViewer');
      if (!viewer) return;
      viewer.innerHTML = '<div class="log-line"><span class="ts">[--:--:--]</span> Loading logs for ' + type + '...</div>';
      try {
        const data = await fetchJSON('/control/logs?type=' + type);
        viewer.innerHTML = '';
        if (data.error) {
          const line = document.createElement('div');
          line.className = 'log-line';
          line.innerHTML = '<span class="ts">[--:--:--]</span> <span class="err">Error: ' + escapeHtml(data.error) + '</span>';
          viewer.appendChild(line);
          return;
        }
        if (!data.lines || data.lines.length === 0) {
          const line = document.createElement('div');
          line.className = 'log-line';
          line.innerHTML = '<span class="ts">[--:--:--]</span> <span class="warning">Log file is empty</span>';
          viewer.appendChild(line);
          return;
        }
        data.lines.forEach(rawLine => {
          if (!rawLine.trim()) return;
          const line = document.createElement('div');
          line.className = 'log-line';
          let levelClass = 'info';
          if (rawLine.includes('[ERROR]') || rawLine.includes('[FATAL]') || rawLine.toLowerCase().includes('error:') || rawLine.toLowerCase().includes('fail')) {
            levelClass = 'err';
          } else if (rawLine.includes('[WARN]') || rawLine.includes('warn')) {
            levelClass = 'warn';
          } else if (rawLine.includes('[OK]') || rawLine.includes('✓')) {
            levelClass = 'ok';
          }
          line.innerHTML = '<span class="ts">[log]</span> <span class="' + levelClass + '">' + escapeHtml(rawLine) + '</span>';
          viewer.appendChild(line);
        });
        viewer.scrollTop = viewer.scrollHeight;
      } catch (e) {
        viewer.innerHTML = '<div class="log-line"><span class="ts">[--:--:--]</span> <span class="err">Failed to fetch logs: ' + escapeHtml(e.message) + '</span></div>';
      }
    }

    function refreshLogs() {
      const select = document.getElementById('logSourceSelect');
      if (select) changeLogSource(select.value);
    }

    async function restartService() {
      if (!confirm('Are you sure you want to restart the Porta Service? This will restart the proxy, web interface, and telegram bot.')) {
        return;
      }
      addLog('Sending restart command...', 'warning');
      try {
        const res = await fetch(API + '/control/restart', {
          method: 'POST',
          headers: headers()
        });
        const data = await res.json();
        if (data.success) {
          addLog('Restart initiated. Reconnecting in 5 seconds...', 'ok');
          updateConnectionStatus(false);
          pollOk = false;
        } else {
          addLog('Restart failed: ' + (data.message || 'unknown error'), 'err');
        }
      } catch (e) {
        // Since server exits, the connection will drop. That means success!
        addLog('Restart request completed: Service is offline and restarting...', 'ok');
        updateConnectionStatus(false);
        pollOk = false;
      }
    }

    function escapeHtml(s) {
      return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function updateConnectionStatus(ok) {
      const dot = document.getElementById('wsStatus');
      const label = document.getElementById('wsLabel');
      if (ok) {
        dot.className = 'status-dot connected';
        label.textContent = 'Connected';
      } else {
        dot.className = 'status-dot';
        label.textContent = 'Disconnected';
      }
    }

    async function refreshHealth() {
      try {
        const data = await fetchJSON('/health');
        if (!pollOk) { pollOk = true; addLog('Connected to proxy', 'ok'); }
        updateConnectionStatus(true);

        document.getElementById('uptime').textContent = formatUptime(data.proxy.uptime);
        document.getElementById('memory').textContent = formatBytes(data.proxy.memory.rss);
        document.getElementById('memoryLabel').textContent = 'RSS (' + formatBytes(data.proxy.memory.heapUsed) + ' heap)';
        document.getElementById('loadAvg').textContent = 'Load: ' + data.proxy.loadavg.map(l => l.toFixed(2)).join(' / ');
        document.getElementById('lsCount').textContent = data.languageServers.length;

        const toggle = document.getElementById('autoApproveToggle');
        toggle.classList.toggle('active', data.autoApprove);
        renderLSInstances(data.languageServers);
      } catch (e) {
        if (pollOk) { addLog('Connection lost: ' + e.message, 'err'); pollOk = false; }
        updateConnectionStatus(false);
      }
    }

    async function refreshConversations() {
      try {
        const data = await fetchJSON('/conversations');
        const summaries = data.trajectorySummaries || {};
        const convs = Object.entries(summaries)
          .map(([id, s]) => ({ id, ...s }))
          .sort((a, b) => (b.stepCount || 0) - (a.stepCount || 0))
          .slice(0, 10); // Top 10

        const list = document.getElementById('convList');
        if (!convs.length) {
          list.innerHTML = '<div class="empty">No conversations</div>';
          return;
        }

        list.innerHTML = convs.map(c => {
          const running = c.status === 'CASCADE_RUN_STATUS_RUNNING';
          const statusCls = running ? 'running' : 'idle';
          const shortId = c.id.slice(0, 8);
          const title = c.summary?.title || c.summary?.description || shortId;
          const displayTitle = title.length > 50 ? title.slice(0, 50) + '…' : title;
          const steps = c.stepCount || 0;
          return '<div class="conv-item">' +
            '<div>' +
              '<div style="font-weight:500;font-size:13px">' + escapeHtml(displayTitle) + '</div>' +
              '<div class="conv-id">' + shortId + ' · ' + steps + ' steps</div>' +
            '</div>' +
            '<div class="conv-status ' + statusCls + '"></div>' +
          '</div>';
        }).join('');
      } catch (e) {
        // Silently ignore — health check will report connection issues
      }
    }

    function renderLSInstances(instances) {
      const list = document.getElementById('lsList');
      if (!instances.length) {
        list.innerHTML = '<div class="empty">⚠️ No Language Servers found. Is Antigravity running?</div>';
        return;
      }
      list.innerHTML = instances.map(ls => {
        const type = ls.subclientType || 'unknown';
        const badgeClass = type === 'hub' ? 'hub' : 'ide';
        const wsName = ls.workspaceId
          ? ls.workspaceId.replace(/^file_/, '').replace(/_/g, '/').slice(-30)
          : 'no workspace';
        return '<div class="ls-item">' +
          '<div class="ls-info">' +
            '<div class="ls-name">PID ' + ls.pid + '</div>' +
            '<div class="ls-meta">' + escapeHtml(wsName) + ' · port ' + ls.httpsPort + '</div>' +
          '</div>' +
          '<span class="ls-badge ' + badgeClass + '">' + type + '</span>' +
        '</div>';
      }).join('');
    }

    async function toggleAutoApprove() {
      const toggle = document.getElementById('autoApproveToggle');
      const current = toggle.classList.contains('active');
      try {
        const data = await postJSON('/config/auto-approve', { enabled: !current });
        toggle.classList.toggle('active', data.enabled);
        addLog('Auto-approve ' + (data.enabled ? 'ENABLED' : 'DISABLED'), data.enabled ? 'ok' : 'warn');
      } catch (e) { addLog('Failed: ' + e.message, 'err'); }
    }

    async function rediscover() {
      addLog('Re-discovering Language Servers...', 'info');
      await refreshHealth();
      addLog('Discovery complete', 'ok');
    }

    function refreshAll() {
      addLog('Refreshing...', 'info');
      refreshHealth();
      refreshConversations();
    }

    // Init — poll-based, no auth required
    refreshHealth();
    setInterval(refreshHealth, 5000);
    addLog('Dashboard initialized — polling every 5s', 'ok');
  </script>
</body>
</html>`;

export function registerDashboardRoute(app: Hono) {
  app.get("/dashboard", (c) => {
    return c.html(DASHBOARD_HTML);
  });

  app.get("/dashboard/", (c) => {
    return c.html(DASHBOARD_HTML);
  });

  // ── Dashboard API (no auth required) ──

  app.get("/dashboard/api/health", async (c) => {
    const instances = await discovery.getInstances();
    const os = await import("node:os");
    return c.json({
      status: "ok",
      proxy: {
        port: parseInt(process.env.PORTA_PORT ?? "3170", 10),
        uptime: process.uptime(),
        memory: process.memoryUsage(),
        loadavg: os.loadavg(),
      },
      autoApprove: isAutoApproveEnabled(),
      languageServers: instances.map((i) => ({
        pid: i.pid,
        httpsPort: i.httpsPort,
        workspaceId: i.workspaceId,
        subclientType: i.subclientType ?? "unknown",
        appDataDir: i.appDataDir ?? "unknown",
        source: i.source,
      })),
    });
  });

  app.get("/dashboard/api/config/auto-approve", (c) => {
    return c.json({ enabled: isAutoApproveEnabled() });
  });

  app.post("/dashboard/api/config/auto-approve", async (c) => {
    const body = await c.req.json().catch(() => ({}));
    if (typeof body.enabled === "boolean") {
      process.env.PORTA_AUTO_APPROVE = body.enabled ? "true" : "false";
    }
    return c.json({ enabled: isAutoApproveEnabled() });
  });

  app.get("/dashboard/api/control/logs", (c) => {
    const type = c.req.query("type") ?? "proxy";
    const allowedTypes = ["proxy", "web", "telegram"];
    if (!allowedTypes.includes(type)) {
      return c.json({ error: "Invalid log type" }, 400);
    }
    const repoRoot = process.cwd();
    const logPath = join(repoRoot, "..", "..", "logs", `${type}.log`);

    if (!existsSync(logPath)) {
      return c.json({ lines: [`[System] No log file found at ${logPath}`] });
    }

    try {
      // Memory-safe tail: only read the last 32KB of the file
      const TAIL_BYTES = 32 * 1024;
      const stat = statSync(logPath);
      const fileSize = stat.size;
      const readSize = Math.min(TAIL_BYTES, fileSize);
      const startPos = Math.max(0, fileSize - readSize);

      const fd = openSync(logPath, "r");
      const buf = Buffer.alloc(readSize);
      readSync(fd, buf, 0, readSize, startPos);
      closeSync(fd);

      const content = buf.toString("utf-8");
      let lines = content.split("\n");
      // If we started mid-line (not at file start), drop the first partial line
      if (startPos > 0 && lines.length > 1) {
        lines = lines.slice(1);
      }
      // Take last 150 lines
      lines = lines.slice(-150);
      return c.json({ lines });
    } catch (err) {
      return c.json({ error: (err as Error).message }, 500);
    }
  });

  app.post("/dashboard/api/control/restart", (c) => {
    console.log("⚠️ [dashboard] Restart request received.");
    const repoRoot = process.cwd();
    const indexPath = join(repoRoot, "src", "index.ts");

    if (existsSync(indexPath)) {
      console.log("Touching src/index.ts to trigger tsx watch reload...");
      try {
        const now = new Date();
        utimesSync(indexPath, now, now);
        return c.json({ success: true, message: "Porta proxy is reloading (development mode)..." });
      } catch (err) {
        console.warn("Touch failed, falling back to process.exit:", err);
      }
    }

    // Fallback/production: exit process and let launchd/orchestrator restart it
    setTimeout(() => {
      process.exit(0);
    }, 1000);
    return c.json({ success: true, message: "Porta service is restarting (production mode)..." });
  });
}
