import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test } from "@playwright/test";

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * Screenshot spec for the top-level README gallery. Drives the live frontend container,
 * seeds two real jobs (one success, one fail-then-replay) so JobDetail and DLQ pages have
 * meaningful content, and writes 13 PNGs into ../docs/screenshots/.
 *
 * Gated behind E2E_SCREENSHOTS=1 in playwright.e2e.config.ts so it doesn't run in the
 * normal e2e:full pass.
 */

const apiBase = process.env.E2E_API_BASE ?? "http://localhost:8080";
const apiKey = process.env.E2E_API_KEY ?? "demo-api-key-do-not-use-in-prod";
const outDir = resolve(__dirname, "../../../docs/screenshots");

const VIEWPORTS = {
  desktop: { width: 1440, height: 900 },
  tablet: { width: 1024, height: 768 },
  mobile: { width: 390, height: 844 },
} as const;

test.describe("readme screenshots @screenshots", () => {
  let successJobId = "";
  let dlqJobId = "";

  test.beforeAll(async ({ request }) => {
    mkdirSync(outDir, { recursive: true });

    const ok = await request.post(`${apiBase}/v1/jobs`, {
      headers: { "X-API-Key": apiKey, "Idempotency-Key": crypto.randomUUID() },
      data: {
        queue: "default",
        type: "echo",
        payload: { outcome: "success", note: "screenshot seed" },
      },
    });
    expect(ok.status()).toBe(202);
    successJobId = ((await ok.json()) as { id: string }).id;

    const bad = await request.post(`${apiBase}/v1/jobs`, {
      headers: { "X-API-Key": apiKey, "Idempotency-Key": crypto.randomUUID() },
      data: {
        queue: "default",
        type: "echo",
        payload: { outcome: "fail", note: "dlq seed" },
        maxAttempts: 1,
      },
    });
    expect(bad.status()).toBe(202);
    dlqJobId = ((await bad.json()) as { id: string }).id;

    await expect
      .poll(
        async () => {
          const r = await request.get(`${apiBase}/v1/jobs/${dlqJobId}`, {
            headers: { "X-API-Key": apiKey },
          });
          return ((await r.json()) as { status: string }).status;
        },
        { timeout: 30_000, intervals: [500, 1000, 2000] },
      )
      .toBe("DEAD");

    await expect
      .poll(
        async () => {
          const r = await request.get(`${apiBase}/v1/jobs/${successJobId}`, {
            headers: { "X-API-Key": apiKey },
          });
          return ((await r.json()) as { status: string }).status;
        },
        { timeout: 30_000, intervals: [500, 1000, 2000] },
      )
      .toBe("SUCCEEDED");
  });

  test("desktop gallery: 7 pages at 1440x900", async ({ page }) => {
    await page.setViewportSize(VIEWPORTS.desktop);

    const shots = [
      { path: "/", file: "01-overview.png", wait: /overview/i },
      { path: "/jobs", file: "02-jobs.png", wait: /jobs/i },
      { path: "/jobs/new", file: "03-submit.png", wait: /submit a job|submit/i },
      { path: `/jobs/${successJobId}`, file: "04-job-detail.png", wait: /job detail/i },
      { path: "/dlq", file: "05-dlq.png", wait: /dead-letter|dlq/i },
      { path: "/tenants", file: "06-tenants.png", wait: /tenant settings/i },
      { path: "/workers", file: "07-workers.png", wait: /workers/i },
    ];

    for (const s of shots) {
      await page.goto(s.path);
      await expect(page.getByRole("heading", { name: s.wait })).toBeVisible({ timeout: 10_000 });
      await page.waitForLoadState("networkidle").catch(() => undefined);
      await page.screenshot({ path: `${outDir}/${s.file}`, fullPage: true });
    }
  });

  test("responsive: tablet + mobile snapshots of 3 key pages", async ({ page }) => {
    const pages = [
      { path: "/", slug: "overview", wait: /overview/i },
      { path: "/jobs", slug: "jobs", wait: /jobs/i },
      { path: "/jobs/new", slug: "submit", wait: /submit a job|submit/i },
    ];

    for (const vp of ["tablet", "mobile"] as const) {
      await page.setViewportSize(VIEWPORTS[vp]);
      for (const p of pages) {
        await page.goto(p.path);
        await expect(page.getByRole("heading", { name: p.wait })).toBeVisible({ timeout: 10_000 });
        await page.waitForLoadState("networkidle").catch(() => undefined);
        await page.screenshot({
          path: `${outDir}/responsive-${vp}-${p.slug}.png`,
          fullPage: true,
        });
      }
    }
  });
});
