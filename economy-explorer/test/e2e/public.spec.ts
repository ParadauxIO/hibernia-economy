import { test, expect, type Page } from '@playwright/test';
import { dataTable, visit, GATE } from './helpers';

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
    const summary = page.getByRole('group', { name: 'Economy summary' });
    await expect(summary).toContainText('Personal money supply');
    await expect(summary).toContainText('Daily active players');
    await expect(summary).toContainText('vs previous 30 days');
    await expect(page.getByText(/^Updated \d{2}:\d{2} UTC$/).first()).toBeVisible();
    await page.getByRole('link', { name: 'Wealth', exact: true }).first().click();
    await expect(page.getByRole('group', { name: 'Wealth concentration' }).locator('.metric', { hasText: 'Gini coefficient' }).locator('.metric-value')).toHaveText(/^0\.\d{3}$/);
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
    const firstLabel = (await page.locator('.dt-rowlink span').first().textContent())?.trim() ?? '';
    expect(firstLabel).not.toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-/i);
  });

  test('the whole-economy transactions firehose is gated', async ({ page }) => {
    await visit(page, '/transactions');
    await expect(page.locator(GATE)).toBeVisible();
    await expect(dataTable(page)).toHaveCount(0);
  });

  test('market browsing links to activity and searches the full catalogue', async ({ page }) => {
    await visit(page, '/market');
    const tabs = page.getByRole('navigation', { name: 'Market' }).getByRole('link');
    await expect(tabs.filter({ hasText: 'Items' })).toHaveAttribute('aria-current', 'page');
    await expect(tabs.filter({ hasText: 'Market firms' })).toHaveAttribute('href', '/chestshop/firms');
    await expect(tabs.filter({ hasText: 'Sales' })).toHaveCount(0);
    await page.getByLabel('Find an item', { exact: true }).fill('Diamond');
    await page.getByRole('button', { name: 'Search items' }).click();
    await expect(page).toHaveURL(/chestshop\?q=Diamond/);
    await expect(page.getByRole('heading', { name: 'Items', level: 1 })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Items' }).getByRole('link', { name: /Diamond/ })).toBeVisible();
    await page.getByRole('link', { name: 'Activity', exact: true }).click();
    await expect(page.getByText('Average trade', { exact: true })).toBeVisible();
  });

  test('per-firm financials are private to anonymous viewers', async ({ page }) => {
    await visit(page, '/chestshop/firms');
    // seed.sql gives Acme Corp (firm 1) ChestShop sales, so it is always listed.
    const firmLink = page.locator('a[href="/chestshop/firms/1"]').first();
    await firmLink.click();
    await expect(page.locator(GATE)).toBeVisible();
  });

  test('government dashboard requires a role and relationship', async ({ page }) => {
    await visit(page, '/gov');
    await expect(page.locator(GATE)).toBeVisible();
    await expect(dataTable(page)).toHaveCount(0);
  });

  test('money flow shows type-to-type flows and net change from the rollups', async ({ page }) => {
    await visit(page, '/money-flow');
    await expect(page.getByRole('heading', { name: 'Money flow', level: 1 })).toBeVisible();
    const matrix = page.getByRole('table', { name: 'Money paid between account types' });
    await expect(matrix.getByRole('row', { name: /^Personal/ })).toContainText(/\d/);
    await expect(page.getByRole('group', { name: 'Net change by account type' })).toContainText('Business');
    await page.getByRole('button', { name: '7d' }).click();
    await expect(page).toHaveURL(/range=7d/);
    await expect(page.getByRole('button', { name: '7d' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('/me requires login', async ({ page }) => {
    await visit(page, '/me');
    await expect(page.locator(GATE)).toBeVisible();
  });

  test('a missing route shows the styled 404', async ({ page }) => {
    await visit(page, '/this-route-does-not-exist');
    await expect(page.locator('.explorer-error-code')).toHaveText('404');
  });

  test('footer carries the attribution', async ({ page }) => {
    await visit(page, '/');
    await expect(page.getByRole('contentinfo')).toContainText('Minecraft Cities Network');
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
  // React can retain a hidden streamed copy outside main while swapping the
  // live dashboard into place. Assert on the displayed page, not that source.
  await expect(page.getByRole('main').getByRole('group', { name: 'Economy summary' })).toContainText('vs previous 7 days');
});

test('shop discovery quotes per item, cheapest first, and switches to selling', async ({ page }) => {
  await visit(page, '/chestshop/shops?f=item:in:DIAMOND');
  const rows = page.getByRole('table', { name: 'Shops' }).locator('tbody tr');
  // 64.00 for a batch of 64 is 1.00 per item, cheaper than 2.00 for one.
  await expect(rows.first()).toContainText('$1.00');
  await visit(page, '/chestshop/shops?f=item:in:DIAMOND&f=direction:in:sell');
  await expect(page).toHaveURL(/sort=-price/);
  await expect(rows.first()).toContainText('$1.50');
});

test('item pages show candles, offers both ways and map links', async ({ page }) => {
  await visit(page, '/chestshop/items/DIAMOND');
  await expect(page.getByRole('heading', { name: 'Price', level: 2, exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Where to buy' })).toBeVisible();
  await expect(page.getByRole('table', { name: 'Shops selling Diamond' }).locator('tbody tr')).toHaveCount(2);
  await expect(page.getByRole('table', { name: 'Shops buying Diamond' }).locator('tbody tr')).toHaveCount(2);
});

test('mobile search opens the palette, focuses it and closes with Escape', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, '/');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const input = page.getByRole('combobox', { name: /Search players/ });
  await expect(input).toBeFocused();
  await input.fill('Alice');
  await expect(page.getByRole('group', { name: 'Results' }).getByRole('option').first()).toContainText('Alice');
  await input.press('Escape');
  await expect(page.getByRole('button', { name: 'Search', exact: true })).toBeFocused();
  await expect(input).toBeHidden();
});

test('mobile account rows keep balances visible and expand secondary details', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, '/accounts?sort=balance&dir=DESC');
  const first = page.locator('#accounts-table tbody tr').first();
  await expect(first.locator('td[data-col="balance"]')).toBeVisible();
  const box = await first.locator('td[data-col="balance"]').boundingBox();
  expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  await expect(first.locator('td[data-col="created"]')).toBeHidden();
  await first.locator('summary').click();
  await expect(first.locator('details')).toHaveAttribute('open', '');
  await expect(first.locator('details [data-col="created"] time')).toBeVisible();
  await page.getByRole('columnheader', { name: /^Account/ }).getByRole('link').click();
  await expect(page).toHaveURL(/sort=name/);
});


test('wealth shows the ledger as it is, with signed balances visible on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, '/economy/wealth');
  const byType = page.getByRole('table', { name: 'Money by account type' });
  // The SYSTEM mint holds a negative balance and is listed, not hidden.
  await expect(byType.getByRole('row', { name: /^System/ })).toContainText('−$3,000.00');
  await expect(byType.getByRole('button', { name: 'About negative government balances' })).toBeVisible();
  for (const table of [byType, page.getByRole('table', { name: 'Largest accounts' })]) {
    const balance = table.locator('tbody tr').first().locator('td.amount:visible').last();
    await expect(balance).toBeVisible();
    const box = await balance.boundingBox();
    expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
});

test('taxation aggregates are public, bounded and responsive', async ({ page }) => {
  const errors = trackErrors(page);
  await visit(page, '/economy/taxation?range=invalid');
  await expect(page.getByRole('heading', { name: 'Taxation', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '30d' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('group', { name: 'Tax revenue summary' })).toContainText('Net revenue');
  await expect(page.getByRole('heading', { name: 'Revenue by type' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Where it lands' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Rates in effect' })).toBeVisible();
  await expect(page.locator(GATE)).toHaveCount(0);
  await expect(page.locator('a[href^="/transactions/"], a[href^="/players/"]')).toHaveCount(0);
  await page.getByRole('button', { name: '7d' }).click();
  await expect(page).toHaveURL(/range=7d/);
  await expect(page.getByRole('button', { name: '7d' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('navigation', { name: 'Economy' }).getByRole('link', { name: 'Taxation' })).toHaveAttribute('aria-current', 'page');
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
  expect(errors.filter(e => /#\d+/.test(e))).toEqual([]);
});
