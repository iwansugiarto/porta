import { useState, useEffect, useCallback, useRef } from "react";

/**
 * Persistent banner that shows when the proxy server is unreachable.
 * Auto-detects disconnection via periodic health checks and disappears
 * when connectivity is restored.
 */
export function ConnectionBanner() {
  const [disconnected, setDisconnected] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const API_BASE = import.meta.env.VITE_API_BASE ?? "";

  const checkConnection = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/api/health`, {
        signal: AbortSignal.timeout(5000),
      });
      if (res.ok) {
        setDisconnected(false);
        return;
      }
    } catch {
      // Connection failed
    }
    setDisconnected(true);
  }, [API_BASE]);

  const handleRetry = useCallback(async () => {
    setRetrying(true);
    await checkConnection();
    setRetrying(false);
  }, [checkConnection]);

  // Periodic health check every 30s
  useEffect(() => {
    timerRef.current = setInterval(checkConnection, 30_000);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [checkConnection]);

  // Also check on online/offline events
  useEffect(() => {
    const handleOnline = () => checkConnection();
    const handleOffline = () => setDisconnected(true);
    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, [checkConnection]);

  if (!disconnected) return null;

  return (
    <div className="connection-banner">
      <div className="connection-banner-content">
        <svg
          className="connection-banner-icon"
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <line x1="1" y1="1" x2="23" y2="23" />
          <path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55" />
          <path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39" />
          <path d="M10.71 5.05A16 16 0 0 1 22.56 9" />
          <path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88" />
          <path d="M8.53 16.11a6 6 0 0 1 6.95 0" />
          <line x1="12" y1="20" x2="12.01" y2="20" />
        </svg>
        <span>Unable to connect to the proxy server</span>
        <button
          className="connection-banner-retry"
          onClick={handleRetry}
          disabled={retrying}
        >
          {retrying ? "Retrying…" : "Retry"}
        </button>
      </div>
    </div>
  );
}
