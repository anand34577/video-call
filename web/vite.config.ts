import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // changeOrigin: false keeps the browser's Host (localhost:5173) so it
    // matches the Origin header — the server rejects mutating requests and
    // WebSocket upgrades whose Origin doesn't match Host. Vite's string
    // shorthand defaults changeOrigin to true, which broke login in dev.
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: false },
      "/ws": { target: "ws://localhost:8080", ws: true, changeOrigin: false },
    },
  },
});
