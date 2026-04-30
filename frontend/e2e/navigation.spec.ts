import { expect, test } from "@playwright/test";
import { ROUTES } from "./_helpers/pages";

test.describe("navigation @nav", () => {
  test("clicking each nav link routes to the right page", async ({ page }) => {
    await page.goto("/");
    for (const route of ROUTES.slice(1)) {
      await page.getByRole("link", { name: route.navLabel }).first().click();
      await expect(page).toHaveURL(route.path);
      await expect(page.getByRole("heading", { name: route.heading })).toBeVisible();
    }
  });

  test("back / forward browser navigation works", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: "Jobs" }).first().click();
    await expect(page).toHaveURL("/jobs");
    await page.goBack();
    await expect(page).toHaveURL("/");
    await page.goForward();
    await expect(page).toHaveURL("/jobs");
  });

  test("active nav item is visually highlighted", async ({ page }) => {
    await page.goto("/dlq");
    const dlqLink = page.getByRole("link", { name: "DLQ" }).first();
    await expect(dlqLink).toHaveClass(/bg-accent/);
  });

  test("404 page link returns home", async ({ page }) => {
    await page.goto("/nope");
    await page.getByRole("link", { name: /back to overview/i }).click();
    await expect(page).toHaveURL("/");
    await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
  });
});
