import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  server: {
    port: 5173,
    proxy: {
      "/api/v1": { target: "http://localhost:8080", rewrite: (p) => p.replace(/^\/api/, "") },
      "/api/actuator": {
        target: "http://localhost:8080",
        rewrite: (p) => p.replace(/^\/api/, ""),
      },
      "/api/ws": {
        target: "ws://localhost:8080",
        ws: true,
        rewrite: (p) => p.replace(/^\/api/, ""),
      },
    },
  },
});
