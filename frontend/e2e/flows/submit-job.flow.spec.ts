import { expect, test } from "@playwright/test";
import { mockApi } from "../_helpers/api-mock";

/**
 * Mocked-API flow tests. These run today (no BE required) and prove the FE wiring once
 * the submit form is implemented. Marked skip until the form ships, then enable.
 */
test.describe("submit job @flow", () => {
  test("user submits a job and is navigated to /jobs", async ({ page }) => {
    await mockApi(page, {
      submitResponse: { id: "11111111-1111-1111-1111-111111111111", status: "READY" },
    });
    await page.goto("/jobs/new");
    await page.getByTestId("field-queue").fill("default");
    await page.getByTestId("field-type").fill("send-email");
    await page.getByRole("button", { name: /submit/i }).click();
    await expect(page).toHaveURL(/\/jobs\/?$/);
  });

  test("server-side rate limit surfaces a toast", async ({ page }) => {
    await mockApi(page, {});
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
    await expect(page.getByText(/rate.?limit|too many/i).first()).toBeVisible();
  });
});
