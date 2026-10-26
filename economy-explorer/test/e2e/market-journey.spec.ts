import { test, expect } from '@playwright/test';
import { visit } from './helpers';

test('search, compare and return keeps the item results and page size', async ({ page }) => {
  await visit(page, '/chestshop?q=Diamond&limit=25');
  await page.locator('.catalogue-table tbody .rowlink').first().click();
  await expect(page.getByRole('heading', { name: 'Where to trade' })).toBeVisible();
  await page.getByRole('link', { name: 'I want to sell', exact: true }).click();
  await expect(page.locator('.offer-price').first()).toContainText('$1.50');
  await page.getByRole('link', { name: 'Show advanced candlesticks' }).click();
  await expect(page.getByRole('link', { name: 'Hide advanced candlesticks' })).toBeVisible();
  await page.locator('.offer-identity h2 a').first().click();
  await expect(page.getByRole('link', { name: 'I want to sell', exact: true })).toHaveAttribute('aria-current', 'true');
  await page.getByRole('link', { name: 'Back to item results' }).click();
  await expect(page.getByRole('searchbox', { name: 'Search items, materials, keys…' })).toHaveValue('Diamond');
  const url = new URL(page.url());
  expect(url.searchParams.get('limit')).toBe('25');
  expect(url.searchParams.get('page')).toBe('1');
  await expect(page.locator('.catalogue-table tbody .rowlink')).not.toHaveCount(0);
});

test('empty search offers a working fresh start', async ({ page }) => {
  await visit(page, '/chestshop?q=no-such-item-joy-check');
  await expect(page.getByRole('heading', { name: 'No items found' })).toBeVisible();
  await page.getByRole('link', { name: 'Browse all items' }).click();
  await expect(page.getByRole('searchbox', { name: 'Search items, materials, keys…' })).toHaveValue('');
  await expect(page.locator('.catalogue-table tbody .rowlink')).not.toHaveCount(0);
});

test('clearing shop filters preserves trade direction and comparison units', async ({ page }) => {
  await visit(page, '/chestshop/shops?item=DIAMOND&direction=sell&units=64');
  await page.getByRole('button', { name: 'Clear filters' }).click();
  await expect(page.getByLabel('Trade direction', { exact: true })).toHaveValue('sell');
  await expect(page.getByLabel('Price units', { exact: true })).toHaveValue('64');
  await expect(page.getByRole('button', { name: 'Remove Item: DIAMOND filter' })).toHaveCount(0);
  expect(new URL(page.url()).searchParams.has('item')).toBe(false);
  await expect(page.locator('.offer-price').first()).toContainText('/ 64 items');
});

test('chart data is readable without interpreting the visual chart', async ({ page }) => {
  await visit(page, '/');
  // The volume chart comes first; the weekday × hour heatmap has its own table.
  await page.getByText('View chart data', { exact: true }).first().click();
  const table = page.getByRole('table', { name: 'Transactions and volume by day' });
  await expect(table).toBeVisible();
  await expect(table.getByRole('columnheader', { name: 'Transactions' })).toBeVisible();
  await expect(table.locator('tbody tr')).not.toHaveCount(0);
  await page.getByRole('link', { name: '7 days' }).click();
  await expect(page.getByRole('main').getByText('Active players / 7d', { exact: true })).toBeVisible();
});

test('keyboard users can skip the global navigation', async ({ page }) => {
  await visit(page, '/market');
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to content' });
  await expect(skip).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
});


test('keyboard help survives focus scrolling and stays inside a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await visit(page, '/design-system');
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
  const trigger = page.getByRole('button', { name: 'More information' }).first();
  await trigger.focus();
  const tooltip = page.getByRole('tooltip');
  await expect(tooltip).toBeVisible();
  const box = await tooltip.boundingBox();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(320);
  await trigger.press('Escape');
  await expect(tooltip).toBeHidden();
  await expect(trigger).toBeFocused();
});

// A public dashboard must expose data without scrolling past introductions.
for (const width of [390, 1280]) {
  test(`dashboard data is visible on arrival at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 });
    for (const [path, selector] of [['/', '.overview-metrics'], ['/market', '.market-table tbody tr'], ['/chestshop', '.catalogue-table tbody tr']] as const) {
      await visit(page, path);
      await expect(page.locator(selector).first()).toBeInViewport({ ratio: 1 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
    }
  });
}

test('phone market rows retain secondary trading data', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  await visit(page, '/market');
  const row = page.getByRole('table', { name: 'Most traded items' }).locator('tbody tr').first();
  await row.getByText('Trading details', { exact: true }).click();
  await expect(row.locator('details')).toContainText('trades');
  await expect(row.locator('details').getByText(/Volume:/)).toBeVisible();
  await row.getByRole('link').click();
  await page.getByRole('link', { name: 'Back to Market' }).click();
  await expect(page.getByRole('table', { name: 'Most traded items' })).toBeVisible();
});
