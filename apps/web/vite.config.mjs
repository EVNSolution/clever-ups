import { defineConfig } from "vite";

// Dev only: forward API paths to a locally running services/api.
const api = { target: "http://127.0.0.1:8080", changeOrigin: false };

export default defineConfig({
  // Firebase is loaded on demand. Pre-bundling both parts together keeps one copy of the app module, so the
  // auth part registers with the same app that initializes it ("Component auth has not been registered").
  optimizeDeps: { include: ["firebase/app", "firebase/auth"] },
  server: { proxy: { "/actuator": api, "/api": api } },
});
