import { expect, type Page } from "@playwright/test";
import type { LiveFixture } from "./config";

export class LineItemWatchPage {
  public constructor(private readonly page: Page) {}

  async assertAuthenticated(): Promise<void> {
    const login = this.page.getByText(/sign in|log in/i).first();
    const cardHeading = this.page.getByRole("heading", {
      name: "Line Item Watch",
      exact: true,
    });
    await Promise.race([
      cardHeading.waitFor({ state: "visible", timeout: 10_000 }),
      login.waitFor({ state: "visible", timeout: 10_000 }),
      this.page.waitForURL(/\/login(?:[/?#]|$)/i, { timeout: 10_000 }),
    ]).catch(() => undefined);
    if (
      /\/login(?:[/?#]|$)/i.test(this.page.url()) ||
      (await login.isVisible().catch(() => false))
    ) {
      throw new Error("SYNTHETIC_AUTH_FAILURE: HubSpot session is not valid");
    }
  }

  async assertLoaded(fixture: LiveFixture): Promise<void> {
    await expect(
      this.page.getByRole("heading", { name: "Line Item Watch", exact: true }),
      "PRODUCT_FAILURE: card heading did not load",
    ).toBeVisible({ timeout: 30_000 });
    await expect(
      this.page.getByText(fixture.primaryLineItem, { exact: true }).first(),
      "PRODUCT_FAILURE: expected synthetic fixture was not rendered",
    ).toBeVisible();
  }

  async searchAndShowHistory(fixture: LiveFixture): Promise<void> {
    const search = this.page.getByLabel("Line Item name", { exact: true });
    await search.fill(fixture.searchTerm);
    await this.page.getByRole("button", { name: "Apply", exact: true }).click();
    await expect(
      this.page.getByText(fixture.primaryLineItem, { exact: true }).first(),
    ).toBeVisible();
    await this.page
      .getByRole("button", { name: "Show history", exact: true })
      .first()
      .click();
    await expect(this.page.getByText(fixture.expectedEventText).first()).toBeVisible();
  }

  async refreshAndPaginateIfOffered(): Promise<void> {
    const loadMore = this.page.getByRole("button", { name: /Load more/ }).first();
    if (await loadMore.isVisible().catch(() => false)) {
      await loadMore.click();
    }
    await this.page.getByRole("button", { name: "Refresh", exact: true }).click();
    await expect(this.page.getByText(/Last refreshed/).first()).toBeVisible();
  }
}
