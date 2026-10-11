import { expect, type Locator, type Page } from '@playwright/test';

/**
 * Navigate and wait for streamed content to settle. Pages stream behind Suspense
 * boundaries: late content arrives in a hidden `<div id="S:n">` and is then swapped
 * in over its fallback, so for a moment the DOM holds two copies of a section
 * (skeleton + real, or real + its not-yet-removed hidden source) and a bare
 * locator matches both.
 */
export async function visit(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.locator('[aria-busy="true"], div[hidden][id^="S:"]')).toHaveCount(0);
}

/**
 * The page's data table. Sections that stream behind their own Suspense boundary
 * keep a skeleton table in the DOM until the real one replaces it, and the real
 * one always comes later in document order.
 */
export function dataTable(page: Page): Locator {
  return page.locator('table.data-table').last();
}
