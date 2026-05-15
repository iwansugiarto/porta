import { useState, useCallback, useEffect } from "react";
import { api, setShareSession } from "../api/client";

interface ShareGateProps {
  shareToken: string;
  onAuthenticated: (workspaceUri: string, workspaceName: string) => void;
}

/**
 * PIN entry page for public share links.
 * Validates the share token, prompts for PIN, and authenticates.
 */
export function ShareGate({ shareToken, onAuthenticated }: ShareGateProps) {
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [shareInfo, setShareInfo] = useState<{
    workspaceName: string;
    label?: string;
  } | null>(null);
  const [notFound, setNotFound] = useState(false);

  // Fetch share info on mount
  useEffect(() => {
    api
      .shareInfo(shareToken)
      .then((info) => {
        setShareInfo({
          workspaceName: info.workspaceName,
          label: info.label,
        });
      })
      .catch(() => {
        setNotFound(true);
      });
  }, [shareToken]);

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!pin.trim()) {
        setError("Please enter the PIN");
        return;
      }

      setLoading(true);
      setError(null);

      try {
        const result = await api.shareAuth(shareToken, pin.trim());
        setShareSession(result.token, result.workspaceUri);
        onAuthenticated(result.workspaceUri, result.workspaceName);
      } catch (err) {
        setError(
          err instanceof Error ? err.message : "Authentication failed",
        );
      } finally {
        setLoading(false);
      }
    },
    [pin, shareToken, onAuthenticated],
  );

  if (notFound) {
    return (
      <div className="login-page">
        <div className="login-card" style={{ textAlign: "center" }}>
          <div className="login-logo">
            <div className="login-logo-icon share-logo-icon">
              <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path
                  d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"
                  fill="white"
                />
              </svg>
            </div>
            <div className="login-title">Link Not Found</div>
            <div className="login-subtitle">
              This share link is invalid, expired, or has been revoked.
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-logo">
          <div className="login-logo-icon share-logo-icon">
            <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path
                d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"
                fill="white"
              />
            </svg>
          </div>
          <div className="login-title">
            {shareInfo?.label || shareInfo?.workspaceName || "Shared Workspace"}
          </div>
          <div className="login-subtitle">
            Enter the PIN to view this shared workspace
          </div>
          {shareInfo?.label && shareInfo.workspaceName && (
            <div className="share-workspace-badge">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
                <path d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z" />
              </svg>
              {shareInfo.workspaceName}
            </div>
          )}
        </div>

        <form className="login-form" onSubmit={handleSubmit}>
          {error && (
            <div className="login-error">
              <svg
                className="login-error-icon"
                viewBox="0 0 24 24"
                fill="currentColor"
              >
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" />
              </svg>
              <span>{error}</span>
            </div>
          )}

          <div className="login-field">
            <label className="login-label" htmlFor="share-pin">
              PIN
            </label>
            <input
              id="share-pin"
              className={`login-input${error ? " error" : ""}`}
              type="password"
              placeholder="Enter PIN"
              value={pin}
              onChange={(e) => {
                setPin(e.target.value);
                if (error) setError(null);
              }}
              autoFocus
              autoComplete="off"
              inputMode="numeric"
            />
          </div>

          <button
            type="submit"
            className="login-submit"
            disabled={loading || !pin.trim()}
          >
            {loading ? (
              <>
                <span className="login-spinner" />
                Verifying…
              </>
            ) : (
              "View Workspace"
            )}
          </button>
        </form>
      </div>
    </div>
  );
}
