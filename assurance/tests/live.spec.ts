import { expect, test, type Browser } from "@playwright/test";
import { loadLiveConfig, type LiveTarget } from "../lib/config";
import { LineItemWatchPage } from "../lib/line-item-watch-page";

async function runReadOnlyJourney(browser: Browser, target: LiveTarget) {
  const config = loadLiveConfig(target);
  const context = await browser.newContext({ storageState: config.storageState });
  const page = await context.newPage();
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  const failedRequests: string[] = [];
  const ownedError = (value: string) =>
    value.includes(config.apiOrigin) || /line[ -]item[ -]watch/i.test(value);
  page.on("console", (message) => {
    if (message.type() === "error" && ownedError(message.text())) {
      consoleErrors.push(message.text());
    }
  });
  page.on("pageerror", (error) => {
    if (ownedError(`${error.message} ${error.stack ?? ""}`)) pageErrors.push(error.message);
  });
  page.on("requestfailed", (request) => {
    try {
      const failedUrl = new URL(request.url());
      if (failedUrl.origin === config.apiOrigin) {
        failedRequests.push(failedUrl.pathname);
      }
    } catch {
      // Non-HTTP browser-internal requests are outside the owned API surface.
    }
  });
  try {
    await page.goto(config.recordUrl, { waitUntil: "domcontentloaded" });
    const card = new LineItemWatchPage(page);
    await card.assertAuthenticated();
    await card.assertLoaded(config.fixture);
    await card.searchAndShowHistory(config.fixture);
    await card.refreshAndPaginateIfOffered();
    expect(pageErrors, "PRODUCT_FAILURE: browser application error").toEqual([]);
    expect(failedRequests, "PRODUCT_FAILURE: browser request failed").toEqual([]);
    expect(
      consoleErrors,
      "PRODUCT_FAILURE: browser console error",
    ).toEqual([]);
  } finally {
    await context.close();
  }
}

test("@staging real HubSpot customer journey", async ({ browser }) => {
  await runReadOnlyJourney(browser, "staging");
});

test("@production read-only HubSpot synthetic journey", async ({ browser }) => {
  await runReadOnlyJourney(browser, "production");
});
