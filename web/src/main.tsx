import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./index.css";
import { initTheme } from "./lib/theme";

initTheme();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

// PWA installability (home screen icon, standalone window). Registered
// after load so it never delays first paint; safe no-op over plain HTTP
// (service workers require a secure context, which this app already needs
// for camera/mic anyway).
if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => {
      /* not a secure context or unsupported — app works fine without it */
    });
  });
}
