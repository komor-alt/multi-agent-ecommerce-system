import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

export default defineConfig({
  base: process.env.VITE_ASSET_BASE || "/ui-static/",
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: "http://127.0.0.1:3000",
        changeOrigin: true,
        router(req) {
          const url = req.url || "";
          if (url.startsWith("/api/v1/data") || /^\/api\/v1\/recommend(\/|\?|$)/.test(url)) {
            return "http://127.0.0.1:8080";
          }
          return "http://127.0.0.1:3000";
        },
      },
      "/health": {
        target: "http://127.0.0.1:8080",
        rewrite: () => "/api/v1/health",
      },
    },
  },
  build: {
    outDir: resolve(__dirname, "../python/frontend"),
    emptyOutDir: true
  }
});
