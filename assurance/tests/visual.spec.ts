import { expect, test } from "@playwright/test";

test("stable Line Item Watch-owned card surface", async ({ page }) => {
  await page.goto("./?scenario=warning");
  await expect(page.getByRole("heading", { name: "Line Item Watch" })).toBeVisible();
  await expect(page.locator("summary", { hasText: "Enterprise support" })).toBeVisible();
  await expect(page.getByRole("main")).toHaveScreenshot("line-item-watch-card.png", {
    animations: "disabled",
    mask: [page.getByText(/Last refreshed/)],
  });
});
