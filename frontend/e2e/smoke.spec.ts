import { expect, test } from "@playwright/test";
import { ROUTES } from "./_helpers/pages";
import { captureConsole } from "./_helpers/console";

test.describe("smoke @smoke", () => {
  for (const route of ROUTES) {
    test(`renders ${route.path}`, async ({ page }) => {
      const errors = captureConsole(page);
      await page.goto(route.path);
      await expect(page.getByRole("heading", { name: route.heading })).toBeVisible();
      expect(errors, errors.join("\n")).toEqual([]);
    });
  }

  test("404 page shows on unknown route", async ({ page }) => {
    await page.goto("/this-does-not-exist");
    await expect(page.getByRole("heading", { name: "404" })).toBeVisible();
    await expect(page.getByRole("link", { name: /back to overview/i })).toBeVisible();
  });

  test("brand mark and product label show on desktop", async ({ page, viewport }) => {
    test.skip((viewport?.width ?? 0) < 768, "desktop-only branding");
    await page.goto("/");
    await expect(page.getByText("reach-taskq")).toBeVisible();
    await expect(page.getByText(/control plane/i)).toBeVisible();
  });
});
