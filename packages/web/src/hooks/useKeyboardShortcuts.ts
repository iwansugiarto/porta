import { useEffect } from "react";

/**
 * Global keyboard shortcuts for the app.
 *
 * - Cmd/Ctrl+K → Open search
 * - Cmd/Ctrl+N → New chat
 * - Escape → Close modals / cancel
 */
export function useKeyboardShortcuts({
  onSearch,
  onNewChat,
  onEscape,
}: {
  onSearch?: () => void;
  onNewChat?: () => void;
  onEscape?: () => void;
}) {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const isModifier = e.metaKey || e.ctrlKey;

      // Don't intercept if user is typing in an input/textarea
      const target = e.target as HTMLElement;
      const isTyping =
        target.tagName === "INPUT" ||
        target.tagName === "TEXTAREA" ||
        target.isContentEditable;

      if (isModifier && e.key === "k") {
        e.preventDefault();
        onSearch?.();
        return;
      }

      if (isModifier && e.key === "n" && !isTyping) {
        e.preventDefault();
        onNewChat?.();
        return;
      }

      if (e.key === "Escape") {
        onEscape?.();
        return;
      }
    };

    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [onSearch, onNewChat, onEscape]);
}
