import { expect, test } from "@playwright/test";

/**
 * Full-stack lifecycle test. Requires:
 *   docker compose -f docker/docker-compose.yml up -d postgres redis app
 *   E2E_API_KEY=<seeded-tenant-api-key> npx playwright test --config=playwright.e2e.config.ts
 *
 * Asserts: submit -> job becomes READY -> worker LEASEs -> SUCCEEDS, all visible in the UI
 * via the /v1/jobs/{id} polling and (when wired) the WS feed.
 */

test.describe("job lifecycle @e2e", () => {
  test.skip(true, "enable once BE controllers + worker handler are wired (Session 3-4)");

  test("submitted job reaches SUCCEEDED", async ({ page, request }) => {
    const apiBase = process.env.E2E_API_BASE ?? "http://localhost:8080";
    const apiKey = process.env.E2E_API_KEY!;
    expect(apiKey, "E2E_API_KEY env var is required").toBeTruthy();

    const submit = await request.post(`${apiBase}/v1/jobs`, {
      headers: { "X-API-Key": apiKey, "Idempotency-Key": crypto.randomUUID() },
      data: { queue: "default", type: "echo", payload: { msg: "hi" } },
    });
    expect(submit.status()).toBe(202);
    const { id } = (await submit.json()) as { id: string };

    await page.goto(`/jobs?id=${id}`);
    await expect(page.getByText(id)).toBeVisible();

    await expect
      .poll(async () => {
        const r = await request.get(`${apiBase}/v1/jobs/${id}`, {
          headers: { "X-API-Key": apiKey },
        });
        const body = (await r.json()) as { status: string };
        return body.status;
      }, { timeout: 30_000, intervals: [500, 1000, 2000] })
      .toBe("SUCCEEDED");

    await expect(page.getByText("SUCCEEDED")).toBeVisible({ timeout: 10_000 });
  });
});
