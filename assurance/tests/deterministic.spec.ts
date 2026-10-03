import { expect, test } from "@playwright/test";
import { LineItemWatchPage } from "../lib/line-item-watch-page";

test("integrated card journey covers loading, search, history, pagination, and refresh", async ({ page }) => {
  await page.goto("./");
  await expect(page.getByRole("status")).toHaveText("Loading Deal audit history");
  await expect(page.getByRole("heading", { name: "Line Item Watch" })).toBeVisible();
  await expect(page.locator("summary", { hasText: "Enterprise support" })).toBeVisible();

  await page.getByLabel("Line Item name").fill("Enterprise");
  await page.getByRole("button", { name: "Apply", exact: true }).click();
  await expect(page.locator("summary", { hasText: "Enterprise support" })).toBeVisible();
  await expect(page.locator("summary", { hasText: "Implementation services" })).toHaveCount(0);

  await page.getByRole("button", { name: "Show history" }).click();
  await expect(page.getByText(/Line Item 2002/).first()).toBeVisible();
  await page.getByRole("button", { name: "Clear all" }).click();
  await page.getByRole("button", { name: "Load more line items" }).click();
  await expect(page.locator("summary", { hasText: "Training" })).toBeVisible();

  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByText(/Last refreshed/)).toBeVisible();
});

test("reliability warning and public error reference are customer visible", async ({ page }) => {
  await page.goto("./?scenario=warning");
  await expect(page.getByRole("alert").filter({ hasText: "Audit reliability is limited" })).toBeVisible();

  await page.goto("./?scenario=error");
  await expect(page.getByRole("alert")).toContainText("Audit history could not be loaded");
  await expect(page.getByRole("alert")).toContainText("8bd0d958-d3db-4214-b235-99fbcf70a812");
  await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
});

test("Hungarian locale renders the owned card surface", async ({ page }) => {
  await page.goto("./?language=hu");
  await expect(page.getByRole("heading", { name: "Line Item Watch" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Alkalmazás" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Frissítés", exact: true })).toBeVisible();
});

test("captures browser, application, and failed-network errors", async ({ page }) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  const failedRequests: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("requestfailed", (request) => failedRequests.push(new URL(request.url()).pathname));
  await page.goto("./");
  await expect(page.getByRole("heading", { name: "Line Item Watch" })).toBeVisible();
  expect({ consoleErrors, pageErrors, failedRequests }).toEqual({
    consoleErrors: [],
    pageErrors: [],
    failedRequests: [],
  });
});

test("expired HubSpot state is classified as authentication, not product failure", async ({ page }) => {
  await page.setContent("<main><h1>Sign in</h1></main>");
  const card = new LineItemWatchPage(page);
  await expect(card.assertAuthenticated()).rejects.toThrow(
    "SYNTHETIC_AUTH_FAILURE: HubSpot session is not valid",
  );
});
