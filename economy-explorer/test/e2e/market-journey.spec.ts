import { loginMarketPlayer } from './helpers';
import { test, expect } from '@playwright/test';
import { visit } from './helpers';

test('search, open an item, compare it and see where to trade', async ({ page }) => {
  await loginMarketPlayer(page);
  await visit(page, '/chestshop?q=Diamond');
  await page.getByRole('table', { name: 'Items' }).getByRole('link', { name: /Diamond/ }).click();
  await expect(page.getByRole('heading', { name: 'Diamond', level: 1 })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Diamond');
  // The seed's second Diamond sign pays 1.50 for one item.
  await expect(page.getByRole('table', { name: 'Shops buying Diamond' })).toContainText('$1.50');
  // The location column folds into row details on phones, so check the link itself.
  await expect(page.getByRole('table', { name: 'Shops selling Diamond' }).locator('a[href*="#world:101:64:101:"]').first()).toBeAttached();
  await page.getByRole('link', { name: '+ Iron Ingot' }).click();
  await expect(page).toHaveURL(/compare=IRON_INGOT/);
  await expect(page.getByRole('link', { name: 'Remove Iron Ingot' })).toBeVisible();
  await page.getByRole('link', { name: 'Remove Iron Ingot' }).click();
  await expect(page).not.toHaveURL(/compare=/);
});

test('an empty search says what filtered it and offers a way back', async ({ page }) => {
  await visit(page, '/chestshop?q=no-such-item-joy-check');
  await expect(page.getByText(/No items match/)).toBeVisible();
  await page.getByRole('link', { name: /^Remove/ }).click();
  await expect(page.getByRole('searchbox')).toHaveValue('');
  await expect(page.getByRole('table', { name: 'Items' }).locator('tbody tr')).not.toHaveCount(0);
});

test('old shop links become filter chips, and selling compares buy-back prices', async ({ page }) => {
  await loginMarketPlayer(page);
  await visit(page, '/chestshop/shops?item=DIAMOND&direction=sell');
  await expect(page.getByText('Shops that buy items from you', { exact: false })).toBeVisible();
  const table = page.getByRole('table', { name: 'Shops' });
  await expect(table.locator('tbody tr')).toHaveCount(2);
  await expect(table.locator('tbody tr').first()).toContainText('$1.50');
  await expect(table.getByRole('columnheader', { name: 'Space', includeHidden: true })).toHaveCount(1);
});

test('chart data is readable without interpreting the visual chart', async ({ page }) => {
  await visit(page, '/');
  // The volume chart comes first; the weekday × hour heatmap has its own table.
  await page.getByText('View chart data', { exact: true }).first().click();
  const table = page.getByRole('table', { name: 'Transactions and volume by day' });
  await expect(table).toBeVisible();
  await expect(table.getByRole('columnheader', { name: 'Transactions' })).toBeVisible();
  await expect(table.locator('tbody tr')).not.toHaveCount(0);
  await page.getByRole('button', { name: '7d' }).click();
  await expect(page).toHaveURL(/range=7d/);
  await expect(page.getByRole('main').getByRole('group', { name: 'Economy summary' })).toContainText('vs previous 7 days');
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
    for (const [path, selector] of [['/', '.overview-metrics'], ['/market', '.metric-row'], ['/chestshop', '#items-table tbody tr']] as const) {
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
  await row.getByText('Details', { exact: true }).click();
  await expect(row.locator('details')).toContainText('Items');
  await expect(row.locator('details')).toContainText('Average price');
  await row.getByRole('link').first().click();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: 'Market' }).click();
  await expect(page.getByRole('table', { name: 'Most traded items' })).toBeVisible();
});
