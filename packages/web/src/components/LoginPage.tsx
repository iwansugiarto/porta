import { useState, useCallback } from "react";
import { setAuthToken, clearAuthToken } from "../api/client";

interface LoginPageProps {
  onAuthenticated: () => void;
  /** Available auth methods from /api/auth/check */
  methods?: { token: boolean; password: boolean };
}

type AuthMode = "password" | "token";

export function LoginPage({ onAuthenticated, methods }: LoginPageProps) {
  // Default to password mode if available, otherwise token
  const defaultMode: AuthMode =
    methods?.password ? "password" : "token";
  const showTabs =
    (methods?.password && methods?.token) ?? false;

  const [mode, setMode] = useState<AuthMode>(defaultMode);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const API_BASE = import.meta.env.VITE_API_BASE ?? "";

  const handlePasswordLogin = useCallback(async () => {
    if (!username.trim() || !password.trim()) {
      setError("Username and password are required");
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const res = await fetch(`${API_BASE}/api/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          username: username.trim(),
          password: password.trim(),
        }),
      });

      const data = await res.json();

      if (!res.ok) {
        setError(data.error ?? "Login failed");
        return;
      }

      // Store the session token
      setAuthToken(data.token);
      onAuthenticated();
    } catch (err) {
      setError(
        err instanceof Error
          ? `Connection error: ${err.message}`
          : "Unable to connect to the server",
      );
    } finally {
      setLoading(false);
    }
  }, [username, password, API_BASE, onAuthenticated]);

  const handleTokenLogin = useCallback(async () => {
    const trimmed = token.trim();
    if (!trimmed) {
      setError("Please enter an access token");
      return;
    }

    setLoading(true);
    setError(null);

    try {
      setAuthToken(trimmed);

      const res = await fetch(`${API_BASE}/api/auth/check`, {
        headers: { Authorization: `Bearer ${trimmed}` },
      });

      if (!res.ok) {
        clearAuthToken();
        setError("Failed to verify token. Please try again.");
        return;
      }

      const data = await res.json();

      if (!data.authenticated) {
        clearAuthToken();
        setError("Invalid token. Please check and try again.");
        return;
      }

      onAuthenticated();
    } catch (err) {
      clearAuthToken();
      setError(
        err instanceof Error
          ? `Connection error: ${err.message}`
          : "Unable to connect to the server",
      );
    } finally {
      setLoading(false);
    }
  }, [token, API_BASE, onAuthenticated]);

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      if (mode === "password") {
        handlePasswordLogin();
      } else {
        handleTokenLogin();
      }
    },
    [mode, handlePasswordLogin, handleTokenLogin],
  );

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-logo">
          <div className="login-logo-icon">
            <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zM12 17c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1s3.1 1.39 3.1 3.1v2z" />
            </svg>
          </div>
          <div className="login-title">Porta</div>
          <div className="login-subtitle">
            {mode === "password"
              ? "Sign in to access your Antigravity session"
              : "Enter your access token to connect"}
          </div>
        </div>

        {showTabs && (
          <div className="login-tabs">
            <button
              type="button"
              className={`login-tab${mode === "password" ? " active" : ""}`}
              onClick={() => {
                setMode("password");
                setError(null);
              }}
            >
              Password
            </button>
            <button
              type="button"
              className={`login-tab${mode === "token" ? " active" : ""}`}
              onClick={() => {
                setMode("token");
                setError(null);
              }}
            >
              Token
            </button>
          </div>
        )}

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

          {mode === "password" ? (
            <>
              <div className="login-field">
                <label className="login-label" htmlFor="porta-username">
                  Username
                </label>
                <input
                  id="porta-username"
                  className={`login-input${error ? " error" : ""}`}
                  type="text"
                  placeholder="Enter username"
                  value={username}
                  onChange={(e) => {
                    setUsername(e.target.value);
                    if (error) setError(null);
                  }}
                  autoFocus
                  autoComplete="username"
                />
              </div>
              <div className="login-field">
                <label className="login-label" htmlFor="porta-password">
                  Password
                </label>
                <input
                  id="porta-password"
                  className={`login-input${error ? " error" : ""}`}
                  type="password"
                  placeholder="Enter password"
                  value={password}
                  onChange={(e) => {
                    setPassword(e.target.value);
                    if (error) setError(null);
                  }}
                  autoComplete="current-password"
                />
              </div>
            </>
          ) : (
            <div className="login-field">
              <label className="login-label" htmlFor="porta-token">
                Access Token
              </label>
              <input
                id="porta-token"
                className={`login-input${error ? " error" : ""}`}
                type="password"
                placeholder="Paste your PORTA_AUTH_TOKEN here"
                value={token}
                onChange={(e) => {
                  setToken(e.target.value);
                  if (error) setError(null);
                }}
                autoFocus
                autoComplete="off"
                spellCheck={false}
              />
            </div>
          )}

          <button
            type="submit"
            className="login-submit"
            disabled={
              loading ||
              (mode === "password"
                ? !username.trim() || !password.trim()
                : !token.trim())
            }
          >
            {loading ? (
              <>
                <span className="login-spinner" />
                {mode === "password" ? "Signing in…" : "Verifying…"}
              </>
            ) : mode === "password" ? (
              "Sign In"
            ) : (
              "Authenticate"
            )}
          </button>
        </form>
      </div>
    </div>
  );
}
