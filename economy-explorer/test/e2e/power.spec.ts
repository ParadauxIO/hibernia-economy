import { test, expect, type BrowserContext, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { visit } from './helpers';

// Search, the transaction explorer, the sales feed and full exports (PAR-540).
// Seed (test/integration/seed.sql): txns 1–3 and 10–15, all plugin "treasury";
// #1 moves 500.00 from Alice (#1) to Acme Corporate Account (#3); #3 splits Bob's
// 300.00 to Alice and Acme. Sales: Bob and Carol at Alice's and Acme's shops.

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100';
const as = (context: BrowserContext, viewer: string) => context.addCookies([{ name: 'e2e_viewer', value: viewer, url: baseURL }]);
const rows = (page: Page, id: string) => page.locator(`#${id} tbody tr`);
// Compare exact timestamps instead of labels that change as the clock advances.
const transactionRows = (page: Page) => rows(page, 'transactions-table').evaluateAll(elements =>
  elements.map(row => Array.from(row.querySelectorAll('td'), cell => {
    const timestamp = cell.querySelector('time');
    return timestamp ? timestamp.dateTime : cell.innerText;
  })));

test.describe('transaction explorer', () => {
  test.skip(!process.env.E2E_AUTH_SHIM, 'needs the auth shim');

  test('lists transactions newest first with chips, pages by cursor, and opens the drawer', async ({ page, context }) => {
    const initialTime = new Date();
    await page.clock.setFixedTime(initialTime);
    await as(context, 'admin');
    await visit(page, '/transactions?f=plugin:in:treasury&limit=4');
    await expect(page.getByRole('heading', { level: 1, name: 'Transactions' })).toBeVisible();
    await expect(page.getByRole('list', { name: 'Active filters' }).getByRole('listitem')).toHaveCount(1);
    await expect(rows(page, 'transactions-table')).toHaveCount(4);
    await expect(page.getByRole('status')).toContainText(/\d+ transactions/);
    const firstPage = await transactionRows(page);
    // Force the minute boundary that caused CI to compare different relative labels.
    await page.clock.setFixedTime(new Date(initialTime.getTime() + 60_000));

    await page.getByRole('link', { name: 'Older' }).click();
    await expect(page).toHaveURL(/cursor=/);
    await expect(rows(page, 'transactions-table')).toHaveCount(4);
    expect(await transactionRows(page)).not.toEqual(firstPage);
    await page.getByRole('link', { name: 'Newer' }).click();
    await expect(page).toHaveURL(/back=1/);
    await expect.poll(() => transactionRows(page)).toEqual(firstPage);

    await visit(page, '/transactions?q=%231');
    await page.getByRole('link', { name: 'pay acme #1' }).click();
    const drawer = page.getByRole('dialog', { name: 'Transaction #1' });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('table', { name: 'Postings of transaction #1' }).getByRole('link', { name: 'Acme Corporate Account #3' })).toBeVisible();
    await expect(drawer.getByRole('heading', { name: 'Follow the money' })).toBeVisible();
    // Acme also received from #3 (the split) within 24 hours.
    await expect(drawer.getByRole('table', { name: /Transactions on Acme Corporate Account/ })).toContainText('split');
    await expect(drawer.getByRole('link', { name: 'Open full page' })).toHaveAttribute('href', '/transactions/1');
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await expect(page).not.toHaveURL(/txn=/);
  });

  test('filters amount exactly, by account and by player, and says when a window was applied', async ({ page, context }) => {
    await as(context, 'admin');
    await visit(page, '/transactions?f=amount:eq:500');
    await expect(rows(page, 'transactions-table')).toHaveCount(1);
    await expect(rows(page, 'transactions-table').first()).toContainText('pay acme');
    await expect(page.getByText(/filters search 31 days at most/)).toBeVisible();

    await visit(page, '/transactions?f=account:in:9');
    await expect(rows(page, 'transactions-table')).toHaveCount(6);
    await expect(page.getByRole('button', { name: /Account: Penny Wallet #9\. Edit filter/ })).toBeVisible();

    await visit(page, '/transactions?f=player:in:Bob');
    await expect(rows(page, 'transactions-table')).toHaveCount(1);
    await expect(rows(page, 'transactions-table').first()).toContainText('split');

    await visit(page, '/transactions?f=category:in:transfers&q=pay');
    await expect(page.getByRole('button', { name: /Category: Transfers\. Edit filter/ })).toBeVisible();
    await expect(rows(page, 'transactions-table').first()).toContainText('pay acme');

    await visit(page, '/transactions?f=toType:in:system');
    await expect(page.locator('#transactions-table .dt-empty')).toContainText('No transactions match To type: System');
  });

  test('the full page keeps its access rules and follows the money for investigators', async ({ page, context }) => {
    await as(context, 'admin');
    await visit(page, '/transactions/3');
    await expect(page.getByRole('heading', { level: 1, name: 'Transaction #3' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Postings of transaction #3' }).getByRole('row')).toHaveCount(4);
    await expect(page.getByRole('heading', { name: 'Follow the money' })).toBeVisible();
    await page.getByRole('link', { name: '1h' }).click();
    await expect(page).toHaveURL(/follow=1/);

    await context.clearCookies();
    await as(context, 'player');
    await visit(page, '/transactions');
    await expect(page.locator('.privacy-gate')).toBeVisible();
  });
});

test.describe('sales feed', () => {
  test.skip(!process.env.E2E_AUTH_SHIM, 'needs the auth shim');

  test('filters by customer (including old links), item and direction', async ({ page, context }) => {
    await as(context, 'admin');
    await visit(page, '/chestshop/sales?customer=00000000-0000-0000-0000-000000000b0b');
    await expect(page.getByRole('button', { name: /Customer: Bob\. Edit filter/ })).toBeVisible();
    await expect(rows(page, 'sales-table')).toHaveCount(2);

    await visit(page, '/chestshop/sales?f=item:in:diamond&f=direction:in:sell');
    await expect(rows(page, 'sales-table')).toHaveCount(2);
    await expect(page.getByRole('button', { name: /Item: Diamond\. Edit filter/ })).toBeVisible();
    await rows(page, 'sales-table').filter({ hasText: 'Carol' }).getByRole('link', { name: 'Carol' }).click();
    await expect(page).toHaveURL(/customer:in:00000000-0000-0000-0000-00000000ca01/);
    await expect(rows(page, 'sales-table')).toHaveCount(1);
  });

  test('is staff only', async ({ page, context }) => {
    await as(context, 'player');
    await visit(page, '/chestshop/sales');
    await expect(page.locator('.privacy-gate')).toBeVisible();
  });
});

test.describe('search', () => {
  test('groups contains matches with counts and per-type tabs', async ({ page }) => {
    await visit(page, '/search?q=acme');
    await expect(page.getByRole('navigation', { name: 'Result type' }).getByRole('link', { name: 'All 2' })).toHaveAttribute('aria-current', 'true');
    await expect(page.getByRole('heading', { name: 'Accounts' })).toBeVisible();
    await expect(page.getByRole('table', { name: /Accounts matching/ })).toContainText('Acme Corporate Account');
    await page.getByRole('link', { name: 'Firms 1' }).click();
    await expect(page).toHaveURL(/type=firms/);
    await expect(page.getByRole('table', { name: /Firms matching/ }).getByRole('row')).toHaveCount(2);
    await page.getByRole('table', { name: /Firms matching/ }).getByRole('link', { name: /Acme Corp/ }).click();
    await expect(page).toHaveURL(/\/firms\/Acme%20Corp/);
  });

  test('matches players by prefix only, and account numbers exactly', async ({ page }) => {
    await visit(page, '/search?q=lice');
    await expect(page.getByText('Nothing matches “lice”')).toBeVisible();
    await visit(page, '/search?q=ali');
    await expect(page.getByRole('table', { name: /Players matching/ })).toContainText('Alice');
    await visit(page, '/search?q=%235');
    await expect(page.getByRole('table', { name: /Accounts matching/ })).toContainText('City Hall');
  });
});

test.describe('full exports', () => {
  test.skip(!process.env.E2E_AUTH_SHIM, 'needs the auth shim');

  test('stream the filtered rows as CSV or JSON with the page’s permissions', async ({ context, page }) => {
    await context.clearCookies();
    expect((await context.request.get('/api/export/accounts?format=csv')).status()).toBe(401);
    await as(context, 'player');
    expect((await context.request.get('/api/export/transactions?format=csv')).status()).toBe(403);
    expect((await context.request.get('/api/export/nope')).status()).toBe(404);

    const csv = await context.request.get('/api/export/accounts?format=csv&f=type:in:business,government');
    expect(csv.status()).toBe(200);
    expect(csv.headers()['content-type']).toContain('text/csv');
    expect(csv.headers()['content-disposition']).toMatch(/attachment; filename="accounts-\d{8}-\d{4}\.csv"/);
    const lines = (await csv.text()).replace(/^﻿/, '').trimEnd().split('\n');
    expect(lines[0]).toBe('Account ID,Type,Name,Owner,Owner UUID,Balance,Archived,Created');
    // 201–205 and supplier 213 are the government workspace fixtures (gov-fixtures.ts).
    expect(lines.slice(1).map((l) => l.split(',')[0]).sort()).toEqual(['201', '202', '203', '204', '205', '213', '3', '5']);

    await context.clearCookies();
    await as(context, 'admin');
    const json = await (await context.request.get('/api/export/transactions?format=json&f=account:in:9')).json();
    expect(json).toMatchObject({ dataset: 'transactions', truncated: false });
    expect(json.rows.map((r: { txn_id: number }) => r.txn_id)).toEqual([15, 14, 13, 12, 11, 10]);
    expect(json.rows[0]).toMatchObject({ amount: '0.20', from: 'Penny Wallet #9', to: 'Bob #2', plugin: 'treasury' });

    // Every export is audited with action `export`, and the audit log exports too.
    const audit = await (await context.request.get('/api/export/audit?format=json&f=action:in:export&f=targetId:eq:transactions')).json();
    expect(audit.rows.length).toBeGreaterThan(0);
    expect(audit.rows[0]).toMatchObject({ action: 'export', target_type: 'global', target_id: 'transactions', outcome: 200 });
    expect(audit.rows[0].path).toContain('/api/export/transactions?');

    // The accounts directory's Export menu points at the same route with its filters.
    await visit(page, '/accounts?f=type:in:business');
    await page.getByRole('button', { name: 'Export' }).click();
    await expect(page.getByRole('menuitem', { name: /CSV/ }).last()).toHaveAttribute('href', '/api/export/accounts?format=csv&f=type:in:business');
  });
});

for (const theme of ['blue', 'red']) {
  for (const mode of ['dark', 'light']) {
    for (const path of ['/transactions?txn=1', '/chestshop/sales', '/search?q=acme']) {
      test(`${theme} ${mode}: ${path} is readable, accessible and fits`, async ({ page, context }) => {
        test.skip(!process.env.E2E_AUTH_SHIM, 'needs the auth shim');
        await context.addCookies([
          { name: 'theme', value: theme, url: baseURL },
          { name: 'mode', value: mode, url: baseURL },
          { name: 'e2e_viewer', value: 'admin', url: baseURL },
        ]);
        await visit(page, path);
        if (path.includes('txn=')) await expect(page.getByRole('dialog')).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
        const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']).analyze();
        const blocking = audit.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
        expect(blocking).toEqual([]);
      });
    }
  }
}
