import { expect, test } from "@playwright/test";
import { MockBackend, installMockWebSocket, makeJob, pushWsEvent } from "../_helpers/mock-backend";

test.describe("ui reflects mock api + ws @integration", () => {
  test.describe.configure({ mode: "serial" });

  let backend: MockBackend;

  test.beforeEach(async ({ context, page }) => {
    backend = new MockBackend();
    await backend.install(context);
    await installMockWebSocket(page, backend);
  });

  test("jobs row tracks websocket status transitions", async ({ page }) => {
    await page.goto("/jobs/new");
    const respP = page.waitForResponse(
      (r) =>
        r.url().includes("/api/v1/jobs") && r.request().method() === "POST" && r.status() === 202,
    );
    await page.getByTestId("submit-button").click();
    const { id } = (await (await respP).json()) as { id: string };

    await expect(page.getByTestId(`row-${id}`)).toBeVisible();
    await expect(page.getByTestId(`row-${id}`)).toHaveAttribute("data-job-status", "PENDING");
    await expect(page.getByTestId("jobs-ws-status")).toContainText("live", { timeout: 10_000 });

    await pushWsEvent(page, {
      jobId: id,
      tenantId: backend.tenantId,
      queue: "default",
      status: "LEASED",
      attempt: 1,
    });
    await expect(page.getByTestId(`row-${id}`)).toHaveAttribute("data-job-status", "LEASED");

    await pushWsEvent(page, {
      jobId: id,
      tenantId: backend.tenantId,
      queue: "default",
      status: "SUCCEEDED",
      attempt: 1,
    });
    await expect(page.getByTestId(`row-${id}`)).toHaveAttribute("data-job-status", "SUCCEEDED");
    await expect(page.getByTestId(`row-${id}`).getByTestId("status-succeeded")).toBeVisible();
  });

  test("overview shows live event and succeeded stat", async ({ page }) => {
    await page.goto("/jobs/new");
    const respP = page.waitForResponse(
      (r) =>
        r.url().includes("/api/v1/jobs") && r.request().method() === "POST" && r.status() === 202,
    );
    await page.getByTestId("submit-button").click();
    const { id } = (await (await respP).json()) as { id: string };

    await page.goto("/");
    await expect(page.getByTestId("page-overview")).toBeVisible();
    backend.transition(id, "SUCCEEDED", 1);
    await pushWsEvent(page, {
      jobId: id,
      tenantId: backend.tenantId,
      queue: "default",
      status: "SUCCEEDED",
      attempt: 1,
    });
    const ev = page.getByTestId(`event-${id}`).first();
    await expect(ev).toBeVisible({ timeout: 10_000 });
    await expect(ev).toHaveAttribute("data-status", "SUCCEEDED");
    await expect(page.getByTestId("stat-succeeded").locator(".tabular-nums")).toHaveText("1", {
      timeout: 10_000,
    });
  });

  test("idempotency conflict surfaces as warning toast", async ({ page }) => {
    const key = `idem-ui-${Date.now()}`;
    await page.goto("/jobs/new");
    await page.getByTestId("field-idempotency").fill(key);
    await page.getByTestId("field-payload").fill('{"a":1}');
    const first = page.waitForResponse(
      (r) =>
        r.url().includes("/api/v1/jobs") && r.request().method() === "POST" && r.status() === 202,
    );
    await page.getByTestId("submit-button").click();
    await first;
    await page.goto("/jobs/new");
    await page.getByTestId("field-idempotency").fill(key);
    await page.getByTestId("field-payload").fill('{"a":2}');
    await page.getByTestId("submit-button").click();
    await expect(page.getByTestId("toast-warning")).toBeVisible();
  });
});

test.describe("ui mock edge cases @integration", () => {
  test("payload too large shows error toast", async ({ page, context }) => {
    const b = new MockBackend({ payloadLimitBytes: 48 });
    await b.install(context);
    await installMockWebSocket(page, b);
    await page.goto("/jobs/new");
    await page.getByTestId("field-payload").fill(`{"x":"${"a".repeat(80)}"}`);
    await page.getByTestId("submit-button").click();
    await expect(page.getByTestId("toast-error")).toBeVisible();
  });

  test("dlq replay updates jobs list", async ({ page, context }) => {
    const j = makeJob();
    const b = new MockBackend({ initialJobs: [j] });
    b.moveToDlq(j.id);
    await b.install(context);
    await installMockWebSocket(page, b);
    await page.goto("/dlq");
    await expect(page.getByTestId("dlq-total")).toContainText("total: 1");
    await page.getByTestId(`dlq-replay-${j.id}`).click();
    await expect(page.getByTestId("toast-success")).toBeVisible();
    await page.goto("/jobs");
    await expect(page.getByTestId(`row-${j.id}`)).toHaveAttribute("data-job-status", "READY");
  });
});
