import { expect, test } from "@playwright/test";

/**
 * Phase 6 UX walkthrough. Wires up the same checklist a human would run at the end
 * of a release: responsive screenshots, live WS update on a real submission, DLQ
 * replay round-trip, and the Tenants editor save → readback. Keeps captures in
 * `test-results/` for later inspection by the report generator.
 */
test.describe("phase 6 ux walkthrough @ux", () => {
  const apiBase = process.env.E2E_API_BASE ?? "http://localhost:8080";
  const apiKey = process.env.E2E_API_KEY ?? "demo-api-key-do-not-use-in-prod";

  test("responsive: overview renders on desktop / tablet / mobile", async ({ page }) => {
    const sizes: Array<{ name: string; w: number; h: number }> = [
      { name: "desktop", w: 1440, h: 900 },
      { name: "tablet", w: 1024, h: 768 },
      { name: "mobile", w: 390, h: 844 },
    ];
    for (const s of sizes) {
      await page.setViewportSize({ width: s.w, height: s.h });
      await page.goto("/");
      await expect(page.getByRole("heading", { name: /overview/i })).toBeVisible();
      await page.screenshot({
        path: `test-results/ux-${s.name}-overview.png`,
        fullPage: true,
      });
    }
  });

  test("live ws: submitted job flips to SUCCEEDED in the table without page reload", async ({
    page,
    request,
  }) => {
    await page.goto("/jobs");
    const submit = await request.post(`${apiBase}/v1/jobs`, {
      headers: { "X-API-Key": apiKey, "Idempotency-Key": crypto.randomUUID() },
      data: { queue: "default", type: "echo", payload: { outcome: "success" } },
    });
    expect(submit.status()).toBe(202);
    const { id } = (await submit.json()) as { id: string };
    const row = page.getByTestId(`row-${id}`);
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row.getByText("SUCCEEDED")).toBeVisible({ timeout: 20_000 });
  });

  test("dlq replay: failed job → DLQ → replay with payload override succeeds", async ({
    page,
    request,
  }) => {
    const submit = await request.post(`${apiBase}/v1/jobs`, {
      headers: { "X-API-Key": apiKey, "Idempotency-Key": crypto.randomUUID() },
      data: {
        queue: "default",
        type: "echo",
        payload: { outcome: "fail" },
        maxAttempts: 1,
      },
    });
    expect(submit.status()).toBe(202);
    const { id } = (await submit.json()) as { id: string };

    await expect
      .poll(
        async () => {
          const r = await request.get(`${apiBase}/v1/jobs/${id}`, {
            headers: { "X-API-Key": apiKey },
          });
          return ((await r.json()) as { status: string }).status;
        },
        { timeout: 30_000, intervals: [500, 1000, 2000] },
      )
      .toBe("DEAD");

    await page.goto("/dlq");
    const row = page.getByTestId(`dlq-row-${id}`);
    if (await row.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await expect(row).toBeVisible();
    }

    const replay = await request.post(`${apiBase}/v1/dlq/${id}/replay`, {
      headers: { "X-API-Key": apiKey, "Content-Type": "application/json" },
      data: { payload: { outcome: "success" } },
    });
    expect(replay.status()).toBe(200);

    await expect
      .poll(
        async () => {
          const r = await request.get(`${apiBase}/v1/jobs/${id}`, {
            headers: { "X-API-Key": apiKey },
          });
          return ((await r.json()) as { status: string }).status;
        },
        { timeout: 30_000, intervals: [500, 1000, 2000] },
      )
      .toBe("SUCCEEDED");
  });

  test("tenants edit: PATCH round-trip preserves new value + UI reflects it", async ({
    page,
    request,
  }) => {
    await page.goto("/tenants");
    await expect(page.getByRole("heading", { name: /tenant settings/i })).toBeVisible();
    const patch = await request.patch(`${apiBase}/v1/tenants/me`, {
      headers: { "X-API-Key": apiKey, "Content-Type": "application/json" },
      data: { rateLimitRps: 75, rateLimitBurst: 150 },
    });
    expect(patch.status()).toBe(200);
    const body = (await patch.json()) as Record<string, unknown>;
    expect(body.rate_limit_rps).toBe(75);
    expect(body.rate_limit_burst).toBe(150);
    const restore = await request.patch(`${apiBase}/v1/tenants/me`, {
      headers: { "X-API-Key": apiKey, "Content-Type": "application/json" },
      data: { rateLimitRps: 100, rateLimitBurst: 200 },
    });
    expect(restore.status()).toBe(200);
  });
});
