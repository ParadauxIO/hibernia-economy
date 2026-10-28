import { test, expect, type Page } from '@playwright/test';
import { GATE, visit } from './helpers';

// Entity pages (PAR-537): the account page as a visitor from a game link sees
// it, as its owner sees it, player profiles and firm tabs. Seed: Alice owns
// personal #1 and is proprietor of Acme Corp, which holds business #3.

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100';
const UUID = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i;

async function mainText(page: Page): Promise<string> {
  return (await page.getByRole('main').innerText()).replace(/\s+/g, ' ');
}

test.describe('account page, unrelated linked visitor', () => {
  test('a business account names its firm first and keeps the history private', async ({ page }) => {
    await visit(page, '/accounts/3');
    await expect(page.getByRole('heading', { level: 1, name: 'Acme Corporate Account' })).toBeVisible();
    const header = page.locator('.page-header');
    await expect(header).toContainText('Held by Acme Corp.');
    await expect(header).toContainText('Alice is proprietor of Acme Corp.');
    await expect(header.getByRole('link', { name: 'Alice' })).toHaveAttribute('href', '/players/Alice');
    await expect(header).toContainText(/Opened \w{3} \d{1,2}, \d{4}/);
    // Public activity totals for business and government accounts (§14 Q2).
    await expect(page.getByText('Money in, 30 days')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Copy /pay command' })).toHaveAttribute('title', '/firm account pay into 1 3 <amount>');
    const gate = page.locator(GATE);
    await expect(gate).toContainText('This account’s history is private');
    await expect(gate.getByRole('button', { name: 'Log in to see your own account' })).toHaveCount(0);
    // The Related rail stays visible, so the visitor has somewhere to go.
    const related = page.getByRole('complementary', { name: 'Related' });
    await expect(related.getByRole('link', { name: 'Acme Corp' })).toHaveAttribute('href', '/firms/Acme%20Corp');
    await expect(page.getByRole('table')).toHaveCount(0);
    expect(await mainText(page)).not.toMatch(UUID);
  });

  test('a personal account shows its owner and no activity totals', async ({ page }) => {
    await visit(page, '/accounts/1');
    await expect(page.locator('.page-header')).toContainText('Personal account of Alice.');
    await expect(page.getByText('Money in, 30 days')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Copy /pay command' })).toHaveAttribute('title', '/pay Alice <amount>');
    await expect(page.getByRole('complementary', { name: 'Related' }).getByRole('link', { name: 'Acme Corp' })).toBeVisible();
    expect(await mainText(page)).not.toMatch(UUID);
  });

  test('a player missing from the directory is shown by name fallback, never a raw UUID', async ({ page }) => {
    await visit(page, '/accounts/7');
    const text = await mainText(page);
    expect(text).not.toMatch(UUID);
    await expect(page.locator('.page-header').getByRole('link', { name: /^00000000…/ })).toHaveAttribute('href', /\/players\/00000000-0000-0000-0000-00000000da7e$/);
  });

  test('exports need access', async ({ request }) => {
    expect((await request.get('/api/export/account-transactions?f=account:in:1&format=csv')).status()).toBe(403);
  });
});

test.describe('account page, with access', () => {
  test.skip(!process.env.E2E_AUTH_SHIM, 'needs the auth shim');
  test.beforeEach(async ({ context }) => {
    await context.addCookies([{ name: 'e2e_viewer', value: 'player', url: baseURL }]);
  });

  test('the owner sees charts, counterparties and a filterable, cursor-paged history', async ({ page }) => {
    await visit(page, '/accounts/1');
    await expect(page.locator(GATE)).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Balance', level: 2 })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Top counterparties' })).toBeVisible();
    const table = page.getByRole('table', { name: 'Transactions' });
    await expect(table.locator('tbody tr')).toHaveCount(3);
    // Secondary on phones, where it folds into each row's details.
    await expect(table.getByRole('columnheader', { name: 'Balance after', includeHidden: true })).toHaveCount(1);
    // Newest first with a running balance that ends at the current balance.
    await expect(table.locator('tbody tr').first()).toContainText('$5,000.00');

    await visit(page, '/accounts/1?f=direction:in:out&limit=1');
    await expect(table.locator('tbody tr')).toHaveCount(1);
    await expect(table.locator('tbody tr').first()).toContainText('−$100.00');
    await page.getByRole('link', { name: 'Older' }).click();
    await expect(page).toHaveURL(/cursor=/);
    await expect(table.locator('tbody tr').first()).toContainText('−$500.00');
    await expect(page.getByRole('link', { name: 'Newer' })).toBeVisible();

    // The Export menu goes through the export registry; another player's account is refused.
    const csv = await page.request.get('/api/export/account-transactions?f=account:in:1&format=csv');
    expect(csv.status()).toBe(200);
    expect(await csv.text()).toMatch(/^\ufeff?Transaction,Settled \(UTC\),Amount,Balance after,Category/);
    expect((await page.request.get('/api/export/account-transactions?f=account:in:2&format=csv')).status()).toBe(403);
  });
});

test.describe('players', () => {
  test('a profile answers who, not how rich: roles without firm money', async ({ page }) => {
    await visit(page, '/players/alice');
    await expect(page).toHaveURL(/\/players\/Alice$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Alice' })).toBeVisible();
    await expect(page.locator('.page-header')).toContainText('Proprietor of Acme Corp');
    const firms = page.getByRole('table', { name: 'Firm roles' });
    await expect(firms).toContainText('Acme Corp');
    // Acme's balance (#3, $25,000.00) belongs to the firm: never on a person's page.
    expect(await mainText(page)).not.toContain('25,000');
    await expect(page.getByRole('table', { name: 'Personal accounts' })).toContainText('$5,000.00');
  });

  test('the directory searches names and filters by firm membership', async ({ page }) => {
    await visit(page, '/players?q=ali');
    await expect(page.getByRole('table', { name: 'Players' }).locator('tbody tr')).toHaveCount(1);
    await page.getByRole('table', { name: 'Players' }).getByRole('link', { name: /Alice/ }).click();
    await expect(page).toHaveURL(/\/players\/Alice$/);
    await visit(page, '/players?f=hasFirm:eq:true');
    await expect(page.getByRole('table', { name: 'Players' }).locator('tbody tr')).toHaveCount(3);
  });
});

test.describe('firms', () => {
  test('legacy financials links reach the gated financials page', async ({ page }) => {
    await visit(page, '/firms/Acme%20Corp?tab=financials');
    await expect(page).toHaveURL(/\/firms\/Acme%20Corp\/financials$/);
    await expect(page.locator(GATE)).toContainText('Financials are private to the firm');
  });
  test('public overview, gated members and financials', async ({ page }) => {
    await visit(page, '/firms/Acme%20Corp');
    await expect(page.locator('.page-header')).toContainText('Run by Alice, its proprietor.');
    await expect(page.locator('.page-header')).toContainText('Firm balance $25,000.00');
    await expect(page.getByRole('table', { name: 'Firm accounts' })).toContainText('Acme Corporate Account');
    await expect(page.getByRole('table', { name: 'Top items' })).toContainText('Diamond');
    await page.getByRole('link', { name: /^Members/ }).click();
    await expect(page.locator(GATE)).toContainText('Members are private to the firm');
    await expect(page.locator('.page-header')).toContainText('Firm balance $25,000.00');
    await page.getByRole('link', { name: 'Financials' }).click();
    await expect(page.locator(GATE)).toContainText('Financials are private to the firm');
    await expect(page.locator('.page-header')).toContainText('Firm balance $25,000.00');
  });

  test('the directory is a chip table', async ({ page }) => {
    await visit(page, '/firms?f=staff:gt:1');
    await expect(page.getByRole('table', { name: 'Firms' }).locator('tbody tr')).toHaveCount(1);
    await expect(page.locator('.fb')).toContainText(/Staff\s*> 1/);
  });
});

test.describe('previews and unfurls', () => {
  test('hover cards are public JSON', async ({ request }) => {
    const account = await request.get('/api/preview/account/3');
    expect(account.status()).toBe(200);
    expect(await account.json()).toMatchObject({ title: 'Acme Corporate Account', subtitle: 'Business account · #3' });
    const firm = await (await request.get('/api/preview/firm/1')).json();
    expect(firm.rows).toContainEqual({ label: 'Proprietor', value: 'Alice' });
    expect((await request.get('/api/preview/account/999999')).status()).toBe(404);
    expect((await request.get('/api/preview/secret/1')).status()).toBe(404);
  });

  test('entity pages unfurl with a generated card', async ({ page, request }) => {
    for (const path of ['/accounts/3', '/players/Alice', '/firms/Acme%20Corp']) {
      const image = await request.get(`${path}/opengraph-image`);
      expect(image.status()).toBe(200);
      expect(image.headers()['content-type']).toBe('image/png');
    }
    await page.goto('/accounts/3');
    await expect(page.locator('meta[property="og:image"]')).toHaveAttribute('content', /\/accounts\/3\/opengraph-image$/);
  });
});
