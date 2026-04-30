import { expect, test } from "@playwright/test";
import { mockApi } from "../_helpers/api-mock";

/**
 * Mocked-API flow tests. These run today (no BE required) and prove the FE wiring once
 * the submit form is implemented. Marked skip until the form ships, then enable.
 */
test.describe("submit job @flow", () => {
  test.skip(true, "enable once /jobs/new form is implemented (Session 4)");

  test("user submits a job and sees the new job id", async ({ page }) => {
    await mockApi(page, {
      submitResponse: { id: "11111111-1111-1111-1111-111111111111", status: "PENDING" },
    });
    await page.goto("/jobs/new");
    await page.getByLabel(/queue/i).fill("default");
    await page.getByLabel(/type/i).fill("send-email");
    await page.getByLabel(/payload/i).fill('{"to":"test@example.com"}');
    await page.getByRole("button", { name: /submit/i }).click();
    await expect(page.getByText(/11111111-1111-1111-1111-111111111111/)).toBeVisible();
  });

  test("server-side rate limit shows a 429 toast", async ({ page }) => {
    await page.route("**/v1/jobs", (route) =>
      route.fulfill({
        status: 429,
        headers: { "Retry-After": "5" },
        contentType: "application/json",
        body: JSON.stringify({ error: "rate_limited", retry_after_seconds: 5 }),
      }),
    );
    await page.goto("/jobs/new");
    await page.getByRole("button", { name: /submit/i }).click();
    await expect(page.getByText(/rate.?limit|too many/i)).toBeVisible();
  });
});
