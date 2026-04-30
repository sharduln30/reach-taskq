import { defineConfig, devices } from "@playwright/test";

/**
 * Full-stack E2E config: assumes Postgres + Redis + the Spring Boot backend are already running
 * (via `docker compose -f docker/docker-compose.yml up -d postgres redis app`).
 *
 * Runs only tests under e2e/full/ which exercise the real backend through the dashboard.
 */
const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:5173";
const API_BASE = process.env.E2E_API_BASE ?? "http://localhost:8080";

export default defineConfig({
  testDir: "./e2e/full",
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report-e2e" }]],

  use: {
    baseURL: BASE_URL,
    trace: "on",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    extraHTTPHeaders: { "X-API-Key": process.env.E2E_API_KEY ?? "" },
  },

  projects: [
    {
      name: "e2e-desktop",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } },
    },
  ],

  webServer: [
    {
      command: "npm run dev -- --host 127.0.0.1 --port 5173",
      url: BASE_URL,
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
    {
      command: "node ./e2e/full/wait-for-backend.mjs",
      url: `${API_BASE}/actuator/health/readiness`,
      reuseExistingServer: true,
      timeout: 60_000,
    },
  ],
});
