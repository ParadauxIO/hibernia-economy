import { test, expect } from '@playwright/test';
import { visit } from './helpers';

test('hashed assets stay cacheable while dashboard HTML stays private', async ({ page, request }) => {
  await visit(page, '/market');
  const script = await page.locator('script[src*="/_next/static/"]').first().getAttribute('src');
  expect(script).toBeTruthy();
  const asset = await request.get(script!);
  expect(asset.ok()).toBe(true);
  expect(asset.headers()['cache-control']).toContain('immutable');
  expect(asset.headers()['cache-control']).toContain('max-age=31536000');
  expect(asset.headers()['cache-control']).not.toContain('no-store');

  const html = await request.get('/market');
  expect(html.headers()['cache-control']).toContain('no-store');
});

test('a staff-warmed item page never exposes sales figures to anonymous visitors', async ({ page, context, baseURL }) => {
  test.skip(!process.env.E2E_AUTH_SHIM, 'requires disposable test identities');
  await context.addCookies([{ name: 'e2e_viewer', value: 'admin', url: baseURL! }]);
  await visit(page, '/chestshop/items/DIAMOND');
  const sales = page.locator('section#recent-sales');
  await expect(sales).toBeVisible();
  await expect(sales.getByRole('columnheader', { name: 'Total' })).toBeVisible();
  await context.clearCookies();
  await visit(page, '/chestshop/items/DIAMOND');
  await expect(page.locator('section#recent-sales')).toHaveCount(0);
  const html = await (await page.request.get('/chestshop/items/DIAMOND')).text();
  expect(html).not.toContain('sale_count');
  expect(html).not.toContain('customer_uuid');
  expect(html).not.toContain('Recent sales');
});
