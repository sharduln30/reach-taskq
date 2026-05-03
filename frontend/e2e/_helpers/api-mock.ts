import type { Page } from "@playwright/test";

/**
 * Intercept the BE so flow tests can run without Docker / Postgres / Redis.
 * Keeps the contract close to the real OpenAPI spec — when the real BE lands,
 * only the URL changes; the test itself is unchanged.
 */
export type Stubs = {
  jobs?: unknown[];
  jobById?: Record<string, unknown>;
  dlq?: unknown[];
  submitResponse?: unknown;
  queueStats?: unknown;
};

export async function mockApi(page: Page, stubs: Stubs = {}) {
  await page.route("**/v1/info", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        name: "reach-taskq",
        version: "0.1.0",
        now: new Date().toISOString(),
      }),
    }),
  );

  await page.route("**/v1/jobs**", async (route, request) => {
    const url = new URL(request.url());
    if (request.method() === "POST") {
      return route.fulfill({
        status: 202,
        contentType: "application/json",
        body: JSON.stringify(
          stubs.submitResponse ?? {
            id: "00000000-0000-0000-0000-000000000001",
            status: "PENDING",
          },
        ),
      });
    }
    const id = url.pathname.split("/").pop();
    if (id && id !== "jobs") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(stubs.jobById?.[id] ?? { id, status: "READY" }),
      });
    }
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: stubs.jobs ?? [], total: stubs.jobs?.length ?? 0 }),
    });
  });

  await page.route("**/v1/dlq**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: stubs.dlq ?? [], total: stubs.dlq?.length ?? 0 }),
    }),
  );

  await page.route("**/v1/queues/*/stats", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        stubs.queueStats ?? { name: "default", ready: 0, leased: 0, succeeded: 0, dead: 0 },
      ),
    }),
  );
}
