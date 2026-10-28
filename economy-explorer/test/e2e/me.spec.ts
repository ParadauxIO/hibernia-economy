import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { visit } from './helpers';

// The /me workspace (spec §8) as the shim's player, Alice: personal #1 (5,000.00)
// and Acme Corp's #3 (25,000.00), whose proprietor she is. The managed e2e
// server runs without the rollup worker, so rollup-backed sections show their
// "Preparing history" state; the money rules are covered on fixtures in
// test/integration/me.test.ts.

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100';
const month = new Date().toISOString().slice(0, 7);

const ROUTES: { path: string; heading: string }[] = [
  { path: '/me', heading: 'Overview' },
  { path: '/me/transactions', heading: 'Transactions' },
  { path: '/me/transactions?f=category:in:taxes&f=amount:gt:10', heading: 'Transactions' },
  { path: '/me/statements', heading: 'Statements' },
  { path: `/me/statements/${month}`, heading: `Statement · ${new Date(`${month}-01T00:00:00Z`).toLocaleDateString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' })}` },
  { path: '/me/taxes', heading: 'Taxes and fines' },
  { path: '/me/market', heading: 'Market' },
  { path: '/me/firms', heading: 'Firms' },
  { path: '/me/developer', heading: 'Webhooks and API' },
  { path: '/me/developer/webhooks', heading: 'Webhooks' },
];

test.describe('/me as a linked player', () => {
  test.skip(!process.env.E2E_AUTH_SHIM, 'needs the auth shim');
  test.beforeEach(async ({ context }) => {
    await context.addCookies([{ name: 'e2e_viewer', value: 'player', url: baseURL }]);
  });

  for (const route of ROUTES) {
    test(`${route.path} renders in the workspace`, async ({ page }) => {
      const errors: string[] = [];
      page.on('pageerror', (e) => errors.push(e.message));
      await visit(page, route.path);
      await expect(page.getByRole('heading', { level: 1, name: route.heading, exact: true })).toBeVisible();
      await expect(page.locator('.sidebar-layout')).toBeVisible();
      expect(errors).toEqual([]);
    });
  }

  test('My balance counts only her personal account; the firm’s money is listed as the firm’s', async ({ page }) => {
    await visit(page, '/me');
    const summary = page.getByRole('group', { name: 'Personal summary' }).or(page.locator('.metric-row').first());
    await expect(summary).toContainText('My balance');
    await expect(summary).toContainText('$5,000.00');
    await expect(summary).not.toContainText('$30,000.00');
    const acme = page.getByRole('region', { name: /^Acme Corp ?· your role/ });
    await expect(acme).toContainText('Acme Corp’s money');
    await expect(acme).toContainText('$25,000.00');
    await expect(acme).toContainText('Registered holder');
    await expect(acme).toContainText('Firm finance · Owner');
  });

  test('the sidebar marks the section on its sub-pages', async ({ page, isMobile }) => {
    test.skip(isMobile, 'the phone layout uses a select');
    await visit(page, `/me/statements/${month}`);
    await expect(page.getByRole('navigation', { name: 'My finances' }).getByRole('link', { name: 'Statements' })).toHaveAttribute('aria-current', 'page');
  });

  test('rows open the transaction, and paging goes older', async ({ page }) => {
    await visit(page, '/me/transactions?limit=1');
    await expect(page.locator('table.dt tbody tr')).toHaveCount(1);
    await page.getByRole('link', { name: 'Older' }).click();
    await expect(page).toHaveURL(/cursor=/);
    await page.locator('table.dt tbody tr a.dt-rowlink').first().click();
    await expect(page).toHaveURL(/\/transactions\/\d+$/);
  });

  test('exports every matching row as CSV', async ({ request }) => {
    const res = await request.get(`${baseURL}/api/export/me-transactions?format=csv`, { headers: { cookie: 'e2e_viewer=player' } });
    expect(res.status()).toBe(200);
    expect(res.headers()['content-type']).toContain('text/csv');
    const lines = (await res.text()).trim().split('\n');
    expect(lines[0]).toContain('Transaction,When (UTC),Account');
    expect(lines.length).toBeGreaterThan(2);
  });

  test('old webhook links redirect to the Developer section', async ({ page }) => {
    await page.goto('/me/webhooks');
    await expect(page).toHaveURL(/\/me\/developer\/webhooks$/);
  });
});

test('/me asks an anonymous visitor to log in, without the workspace', async ({ page, context }) => {
  await context.clearCookies();
  await visit(page, '/me/statements');
  await expect(page).toHaveURL(/\/api\/auth\/signin/);
  await expect(page.getByRole('navigation', { name: 'My finances' })).toHaveCount(0);
});

// Axe in both themes and both modes, every /me route.
for (const theme of ['blue', 'red']) {
  for (const mode of ['dark', 'light']) {
    for (const route of ROUTES) {
      test(`a11y ${theme} ${mode}: ${route.path}`, async ({ page, context, isMobile }) => {
        test.skip(!process.env.E2E_AUTH_SHIM, 'needs the auth shim');
        test.skip(isMobile && (theme !== 'blue' || mode !== 'dark'), 'phones: one theme is enough for layout');
        await context.addCookies([
          { name: 'e2e_viewer', value: 'player', url: baseURL },
          { name: 'theme', value: theme, url: baseURL },
          { name: 'mode', value: mode, url: baseURL },
        ]);
        await visit(page, route.path);
        expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
        const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']).analyze();
        const blocking = audit.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
          .map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 5).join(', ')}`);
        expect(blocking).toEqual([]);
      });
    }
  }
}
