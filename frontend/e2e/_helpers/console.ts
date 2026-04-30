import type { Page } from "@playwright/test";

const IGNORED = [/Failed to load resource.*favicon/i, /\[vite\]/i, /Download the React DevTools/i];

export function captureConsole(page: Page) {
  const errors: string[] = [];
  page.on("console", (msg) => {
    if (msg.type() !== "error") return;
    const text = msg.text();
    if (IGNORED.some((re) => re.test(text))) return;
    errors.push(text);
  });
  page.on("pageerror", (err) => errors.push(err.message));
  return errors;
}
