import { expect, test } from "@playwright/test";
import { ROUTES } from "./_helpers/pages";

test.describe("responsive @responsive", () => {
  test("desktop shows the brand block in the sidebar", async ({ page, viewport }) => {
    test.skip((viewport?.width ?? 0) < 768, "desktop-only assertion");
    await page.goto("/");
    await expect(page.getByText(/control plane/i)).toBeVisible();
  });

  test("mobile hides the brand block (icons-only nav)", async ({ page, viewport }) => {
    test.skip((viewport?.width ?? 0) >= 768, "mobile-only assertion");
    await page.goto("/");
    await expect(page.getByText(/control plane/i)).toBeHidden();
  });

  test("every page renders without horizontal overflow", async ({ page }) => {
    for (const route of ROUTES) {
      await page.goto(route.path);
      const overflow = await page.evaluate(() => {
        const doc = document.documentElement;
        return doc.scrollWidth > doc.clientWidth;
      });
      expect(overflow, `${route.path} overflows horizontally`).toBeFalsy();
    }
  });

  test("nav remains reachable at all viewports", async ({ page }) => {
    await page.goto("/");
    const links = await page.getByRole("link").all();
    expect(links.length).toBeGreaterThan(5);
    for (const route of ROUTES) {
      await expect(page.getByRole("link", { name: route.navLabel }).first()).toBeVisible();
    }
  });
});
