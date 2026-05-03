import { defineConfig, devices } from "@playwright/test";

const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:5173";

export default defineConfig({
  testDir: "./e2e",
  testIgnore: ["**/full/**"],
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI
    ? [["github"], ["html", { open: "never" }]]
    : [["list"], ["html", { open: "never" }]],
  outputDir: "test-results",

  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    actionTimeout: 5_000,
    navigationTimeout: 15_000,
  },

  expect: {
    timeout: 5_000,
    toHaveScreenshot: { maxDiffPixelRatio: 0.02 },
  },

  projects: [
    {
      name: "desktop-chrome",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } },
    },
    ...(process.env.PW_FIREFOX
      ? [
          {
            name: "desktop-firefox",
            use: { ...devices["Desktop Firefox"], viewport: { width: 1440, height: 900 } },
            testIgnore: /.*\.a11y\.spec\.ts/,
          },
        ]
      : []),
    {
      name: "tablet",
      use: {
        browserName: "chromium",
        viewport: { width: 834, height: 1194 },
        deviceScaleFactor: 2,
        isMobile: true,
        hasTouch: true,
      },
    },
    {
      name: "mobile",
      use: {
        browserName: "chromium",
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 3,
        isMobile: true,
        hasTouch: true,
      },
    },
  ],

  webServer: {
    command: "npm run dev -- --host 127.0.0.1 --port 5173",
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    stdout: "pipe",
    stderr: "pipe",
  },
});
