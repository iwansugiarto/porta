import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { ErrorBoundary } from "./components/ErrorBoundary";
import "./index.css";
import App from "./App";

// ── PWA viewport height fix ──
// In standalone/fullscreen PWA mode, 100dvh can be wrong on the initial
// render until the browser fires a resize event (e.g. after rotation).
// We measure the real viewport height via JS and expose it as a CSS
// custom property so the layout is correct from the very first paint.
function setAppHeight() {
  document.documentElement.style.setProperty(
    "--app-height",
    `${window.innerHeight}px`,
  );
}

// Set immediately, then listen for changes
setAppHeight();
window.addEventListener("resize", setAppHeight);
window.addEventListener("orientationchange", () => {
  // orientationchange fires before the viewport has fully settled;
  // a short delay ensures we read the final innerHeight.
  setTimeout(setAppHeight, 150);
});

// Also re-measure when the visual viewport changes (keyboard open/close on mobile)
if (window.visualViewport) {
  window.visualViewport.addEventListener("resize", setAppHeight);
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ErrorBoundary>
  </StrictMode>,
);
