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
    await page.waitForTimeout(1500);
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
    const firmLink = page.locator('a[href^="/chestshop/firms/"]').first();
    if ((await firmLink.count()) === 0) test.skip(true, 'no market firms in this dataset');
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
});

test('healthz returns 200 without the DB', async ({ request }) => {
  const res = await request.get('/healthz');
  expect(res.status()).toBe(200);
});

test('Health bookmarks preserve their window on the consolidated overview', async ({ page }) => {
  await visit(page, '/economy/health?days=7');
  await expect(page).toHaveURL(/\/?days=7$/);
  await expect(page.getByText('Active players / 7d', { exact: true })).toBeVisible();
});
