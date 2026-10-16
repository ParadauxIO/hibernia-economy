import { test, expect, type Page } from '@playwright/test';
import { dataTable, visit } from './helpers';

// Collect page (hydration / runtime) errors so any React #418 etc. fails the test.
function trackErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  return errors;
}

test.describe('anonymous browsing', () => {
  test('home renders the economy dashboard with working KPIs', async ({ page }) => {
    const errors = trackErrors(page);
    await visit(page, '/');
    await expect(page.getByRole('heading', { name: 'Economy', level: 1 })).toBeVisible();
    await expect(page.getByText('Personal supply')).toBeVisible();
    await expect(page.getByText('Active players / 30d', { exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Wealth', exact: true }).click();
    const gini = page.locator('.insight', { hasText: 'Gini' }).locator('.value');
    await expect(gini).toHaveText(/^0\.\d+$/);
    // Charts only mount client-side (useHydrated), so a rendered chart means the
    // wealth tree has committed — any hydration/render error has been reported.
    await expect(page.locator('.recharts-responsive-container').first()).toBeVisible();
    expect(errors.filter((e) => /#\d+/.test(e))).toEqual([]);
  });

  test('no horizontal overflow (mobile regression)', async ({ page }) => {
    await visit(page, '/');
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(1);
  });

  test('accounts list shows names (not raw UUIDs) and no status column', async ({ page }) => {
    await visit(page, '/accounts?type=PERSONAL');
    await expect(dataTable(page)).toBeVisible();
    await expect(page.locator('th', { hasText: 'Status' })).toHaveCount(0);
    const firstLabel = (await page.locator('.rowlink span').first().textContent())?.trim() ?? '';
    expect(firstLabel).not.toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-/i);
  });

  test('the whole-economy transactions firehose is gated', async ({ page }) => {
    await visit(page, '/transactions');
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await expect(dataTable(page)).toHaveCount(0);
  });

  test('market browsing links to activity and searches the full catalogue', async ({ page }) => {
    await visit(page, '/market');
    const tabs = page.locator('.section-tabs a');
    await expect(tabs.filter({ hasText: 'Items' })).toBeVisible();
    await expect(tabs.filter({ hasText: 'Firms' })).toHaveCount(0);
    await expect(tabs.filter({ hasText: 'Sales' })).toHaveCount(0);
    await page.getByLabel('Find an item', { exact: true }).fill('Diamond');
    await page.getByRole('button', { name: 'Search all items' }).click();
    await expect(page).toHaveURL(/chestshop\?q=Diamond/);
    await expect(page.getByRole('heading', { name: 'Browse items' })).toBeVisible();
    await page.getByRole('link', { name: 'Activity', exact: true }).click();
    await expect(page.getByText('Avg sale', { exact: true })).toBeVisible();
  });

  test('per-firm financials are private to anonymous viewers', async ({ page }) => {
    await visit(page, '/chestshop/firms');
    // seed.sql gives Acme Corp (firm 1) ChestShop sales, so it is always listed.
    const firmLink = page.locator('a[href="/chestshop/firms/1"]').first();
    await firmLink.click();
    await expect(page.locator('.privacy-gate')).toBeVisible();
  });

  test('government dashboard requires a role and relationship', async ({ page }) => {
    await visit(page, '/government');
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await expect(dataTable(page)).toHaveCount(0);
  });

  test('money-flow explains cross-type and offers window tabs', async ({ page }) => {
    await visit(page, '/money-flow');
    await expect(page.getByText(/between different account types/i)).toBeVisible();
    await expect(page.locator('.window-tabs a')).toHaveCount(2);
  });

  test('/me requires login', async ({ page }) => {
    await visit(page, '/me');
    await expect(page.locator('.privacy-gate')).toBeVisible();
  });

  test('a missing route shows the styled 404', async ({ page }) => {
    await visit(page, '/this-route-does-not-exist');
    await expect(page.locator('.explorer-error-code')).toHaveText('404');
  });

  test('footer carries the attribution', async ({ page }) => {
    await visit(page, '/');
    await expect(page.locator('.footer-meta')).toContainText('Minecraft Cities Network');
  });

  test('the static tax guide shows the rates this server published', async ({ page }) => {
    const errors = trackErrors(page);
    await visit(page, '/docs/guides/tax');
    const balance = page.locator('.doc-live[data-live="balance-tax"]');
    await expect(balance.locator('tbody tr')).toHaveCount(2);
    await expect(balance.locator('tbody tr').last()).toContainText('$100,000.00 and above');
    await expect(balance.locator('tbody tr').last()).toContainText('1%');
    const income = page.locator('.doc-live[data-live="income-tax"]');
    await expect(income.locator('tbody')).toContainText('Jobs');
    await expect(income.locator('tbody')).toContainText('5%');
    expect(errors).toEqual([]);
  });
});

test('healthz returns 200 without the DB', async ({ request }) => {
  const res = await request.get('/healthz');
  expect(res.status()).toBe(200);
});

test('Health bookmarks preserve their window on the consolidated overview', async ({ page }) => {
  await visit(page, '/economy/health?days=7');
  await expect(page).toHaveURL(/\?days=7$/);
  await expect(page.getByText('Active players / 7d', { exact: true })).toBeVisible();
});

test('shop discovery normalises batches and switches to selling', async ({ page }) => {
  await visit(page, '/chestshop/shops?item=DIAMOND');
  await expect(page.locator('.offer-price').first()).toContainText('$1.00');
  await page.getByLabel('Trade direction', { exact: true }).selectOption('sell');
  await expect(page).toHaveURL(/direction=sell/);
  await expect(page.locator('.offer-price').first()).toContainText('$1.50');
  await page.getByLabel('Price units').selectOption('64');
  await expect(page.locator('.offer-price').first()).toContainText('$96.00');
});

test('item pages put observed offers before optional advanced history', async ({ page }) => {
  await visit(page, '/chestshop/items/DIAMOND');
  await expect(page.getByRole('heading', { name: 'Where to trade' })).toBeVisible();
  await expect(page.locator('.offer-card')).toHaveCount(2);
  await expect(page.getByText('Candles', { exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: 'Show advanced candlesticks' }).click();
  await expect(page.getByRole('link', { name: 'Hide advanced candlesticks' })).toBeVisible();
});

test('mobile global search opens, focuses and closes with Escape', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, '/');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const input = page.getByRole('searchbox', { name: 'Search', exact: true });
  await expect(input).toBeFocused();
  await input.fill('Alice');
  await expect(page.getByRole('group', { name: 'Search suggestions' })).toBeVisible();
  await input.press('Escape');
  await expect(page.getByRole('button', { name: 'Search', exact: true })).toBeFocused();
  await expect(input).toBeHidden();
});

test('mobile account rows keep balances visible and expand secondary details', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, '/accounts?sort=balance&dir=DESC');
  const first = page.locator('.directory-table tbody tr').first();
  await expect(first.locator('td.amount')).toBeVisible();
  const box = await first.locator('td.amount').boundingBox();
  expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  await first.locator('summary').click();
  await expect(first.locator('details')).toHaveAttribute('open', '');
  await page.getByRole('link', { name: 'Newest', exact: true }).click();
  await expect(page).toHaveURL(/sort=created/);
});


test('wealth keeps signed balances visible on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, '/economy/wealth');
  for (const table of await page.locator('.directory-table').all()) {
    const balance = table.locator('tbody tr').first().locator('td.amount:visible').first();
    await expect(balance).toBeVisible();
    const box = await balance.boundingBox();
    expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  }
});

test('taxation aggregates are public, bounded and responsive', async ({ page }) => {
  const errors = trackErrors(page);
  await visit(page, '/economy/taxation?days=invalid');
  await expect(page.getByRole('heading', { name: 'Taxation', exact: true })).toBeVisible();
  await expect(page.locator('.window-tabs a.active')).toHaveText('30d');
  await expect(page.getByRole('heading', { name: 'Revenue by type' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Where it lands' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Rates in effect' })).toBeVisible();
  await expect(page.locator('.privacy-gate')).toHaveCount(0);
  await expect(page.locator('a[href^="/transactions/"], a[href^="/players/"]')).toHaveCount(0);
  await page.getByRole('link', { name: '7d', exact: true }).click();
  await expect(page.locator('.window-tabs a.active')).toHaveText('7d');
  await expect(page.locator('.section-tabs a.active')).toHaveText('Taxation');
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
  expect(errors.filter(e => /#\d+/.test(e))).toEqual([]);
});
