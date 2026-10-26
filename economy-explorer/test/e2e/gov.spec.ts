import { test, expect, type BrowserContext, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { encode } from 'next-auth/jwt';
import { visit } from './helpers';
import { GOV_OFFICIAL, GOV_OFFICIAL_NAME, GOV_OUT_OF_SCOPE_NAMES } from './gov-fixtures';

// The government workspace (PAR-539) for its two kinds of viewer, in both
// brands and both modes:
//   * OFFICIAL: a signed Auth.js session for a player with the `government`
//     role and VIEWER access to two Commerce accounts (gov-fixtures.ts). The
//     real proxy, identity and role resolution run.
//   * moderator / admin / compliance: the e2e auth shim (government.read.all).
// Every page must pass axe and fit the viewport, and nothing out of OFFICIAL's
// scope may ever reach their pages or exports.

const secret = process.env.E2E_AUTH_SECRET;
const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100';

async function signInOfficial(context: BrowserContext) {
  const name = baseURL.startsWith('https:') ? '__Secure-authjs.session-token' : 'authjs.session-token';
  const token = await encode({ secret: secret!, salt: name, token: { sub: `gov-e2e-${GOV_OFFICIAL}`, minecraftUuid: GOV_OFFICIAL, minecraftName: GOV_OFFICIAL_NAME } });
  await context.addCookies([{ name, value: token, url: baseURL, httpOnly: true, sameSite: 'Lax' }]);
}
async function shim(context: BrowserContext, role: string) {
  await context.addCookies([{ name: 'e2e_viewer', value: role, url: baseURL }]);
}
async function look(context: BrowserContext, theme: string, mode: string) {
  await context.addCookies([{ name: 'theme', value: theme, url: baseURL }, { name: 'mode', value: mode, url: baseURL }]);
}

async function axe(page: Page): Promise<string[]> {
  const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']).analyze();
  return audit.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 5).join(' | ')}`);
}

const PAGES = [
  '/gov', '/gov?unit=commerce&range=90d', '/gov/accounts', '/gov/revenue', '/gov/spending', '/gov/payroll?runs=all',
  '/gov/fines', '/gov/fines?f=status:in:active', '/gov/reports', '/gov/audit', '/gov/audit/views',
];

test.describe('government workspace pages', () => {
  test.skip(!secret || !process.env.E2E_AUTH_SHIM, 'needs the managed e2e app (signed sessions and the auth shim)');

  for (const [theme, mode] of [['blue', 'dark'], ['red', 'light']] as const) {
    for (const path of PAGES) {
      test(`official, ${theme} ${mode}: ${path} is accessible, fits and stays in scope`, async ({ page, context }) => {
        await signInOfficial(context);
        await look(context, theme, mode);
        await visit(page, path);
        await expect(page.locator('.privacy-gate')).toHaveCount(0);
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        const text = await page.locator('main').innerText();
        for (const name of GOV_OUT_OF_SCOPE_NAMES) expect(text, `${name} is out of scope`).not.toContain(name);
        expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
        expect(await axe(page)).toEqual([]);
      });
    }
  }

  for (const [theme, mode] of [['blue', 'light'], ['red', 'dark']] as const) {
    for (const path of PAGES) {
      test(`government.read.all, ${theme} ${mode}: ${path} is accessible and fits`, async ({ page, context }) => {
        await shim(context, 'admin');
        await look(context, theme, mode);
        await visit(page, path);
        await expect(page.locator('.privacy-gate')).toHaveCount(0);
        expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
        expect(await axe(page)).toEqual([]);
      });
    }
  }

  for (const [path, role] of [['/gov/compliance', 'compliance'], ['/gov/compliance?firm=Ac', 'compliance'], ['/gov/compliance/firms/1', 'compliance'], ['/admin/gov-units', 'admin']] as const) {
    for (const [theme, mode] of [['blue', 'dark'], ['red', 'light']] as const) {
      test(`${role}, ${theme} ${mode}: ${path} is accessible and fits`, async ({ page, context }) => {
        await shim(context, role);
        await look(context, theme, mode);
        await visit(page, path);
        await expect(page.locator('.privacy-gate')).toHaveCount(0);
        expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
        expect(await axe(page)).toEqual([]);
      });
    }
  }
});

test.describe('government workspace scope and behaviour', () => {
  test.skip(!secret || !process.env.E2E_AUTH_SHIM, 'needs the managed e2e app (signed sessions and the auth shim)');

  test('the workspace needs government access', async ({ page, context }) => {
    await visit(page, '/gov');
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await shim(context, 'player');
    await visit(page, '/gov/fines');
    await expect(page.locator('.privacy-gate')).toBeVisible();
    expect((await context.request.get(`${baseURL}/api/export/fines?format=csv`)).status()).toBe(403);
  });

  test('an official sees their accounts, a partial unit, and only in-scope fines', async ({ page, context }) => {
    await signInOfficial(context);
    await visit(page, '/gov?unit=commerce');
    await expect(page.getByText(/Partial view/)).toBeVisible();
    await expect(page.getByText('2 of 3 accounts', { exact: false })).toBeVisible();
    await visit(page, '/gov/accounts');
    const rows = page.locator('#gov-accounts-table tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(page.getByRole('link', { name: /Commerce Department/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /Commerce Reserve/ })).toBeVisible();
    await visit(page, '/gov/fines');
    await expect(page.locator('#gov-fines-table tbody tr')).toHaveCount(3);
    // The page's export link carries its unit as an account chip and its range as a date chip.
    await page.getByRole('button', { name: 'Export' }).click();
    const exportLink = await page.getByRole('menuitem', { name: /CSV/ }).last().getAttribute('href');
    expect(exportLink).toMatch(/^\/api\/export\/fines\?.*f=account:in:201,202.*f=issued:between:/);
    // Exports carry the same scope, even when asked for everything or for another account.
    const csv = await (await context.request.get(`${baseURL}/api/export/fines?format=csv`)).text();
    expect(csv).toContain('Price fixing');
    expect(csv).not.toContain('Trespass');
    const elsewhere = await (await context.request.get(`${baseURL}/api/export/fines?format=csv&f=account:in:204`)).text();
    expect(elsewhere.trimEnd().split(/\r?\n/).filter(Boolean)).toHaveLength(1); // the header only
    const ledger = await (await context.request.get(`${baseURL}/api/export/gov-transactions?format=csv`)).text();
    expect(ledger.trimEnd().split(/\r?\n/).filter(Boolean).length).toBeGreaterThan(1);
    for (const name of GOV_OUT_OF_SCOPE_NAMES) expect(ledger).not.toContain(name);
    // Choosing a unit outside the scope falls back to the official's own accounts.
    await visit(page, '/gov/accounts?unit=interior');
    await expect(page.locator('#gov-accounts-table tbody tr')).toHaveCount(2);
  });

  test('an official without the compliance role can’t open the compliance tools', async ({ page, context }) => {
    await signInOfficial(context);
    await visit(page, '/gov/compliance');
    await expect(page.locator('.privacy-gate')).toBeVisible();
  });

  test('a government.read.all viewer sees every account, the mint included', async ({ page, context }) => {
    await shim(context, 'moderator');
    await visit(page, '/gov/accounts');
    await expect(page.getByRole('link', { name: /Gov Mint/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /Interior Ministry/ })).toBeVisible();
  });

  test('fine chips and quick filters live in the URL and keep the unit', async ({ page, context }) => {
    await shim(context, 'moderator');
    await visit(page, '/gov/fines?unit=commerce&range=90d');
    await expect(page.locator('#gov-fines-table tbody tr')).toHaveCount(3);
    await page.getByRole('searchbox', { name: /Search fine reasons/ }).fill('licence');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/q=licence/);
    await expect(page).toHaveURL(/unit=commerce/);
    await expect(page.locator('#gov-fines-table tbody tr')).toHaveCount(1);
  });

  test('payroll finds the runs and the reports page prints and downloads', async ({ page, context }) => {
    await shim(context, 'moderator');
    await visit(page, '/gov/payroll?range=30d&runs=all');
    await expect(page.locator('#gov-payroll-runs tbody tr').first()).toBeVisible();
    await visit(page, '/gov/reports');
    await expect(page.getByRole('button', { name: 'Print or save as PDF' })).toBeVisible();
    const href = await page.getByRole('link', { name: 'Download CSV' }).getAttribute('href');
    const csv = await (await context.request.get(`${baseURL}${href}`)).text();
    expect(csv.split('\r\n')[0]).toBe('Section,Item,Amount');
  });

  test('/government redirects to the workspace with its filters', async ({ context }) => {
    const r = await context.request.get(`${baseURL}/government?view=fines&days=7&status=active&q=stall`, { maxRedirects: 0 });
    expect(r.status()).toBe(308);
    expect(r.headers().location).toMatch(/\/gov\/fines\?range=7d&q=stall&f=status%3Ain%3Aactive$/);
  });

  test('compliance officers watch accounts from a firm investigation', async ({ page, context }) => {
    await shim(context, 'compliance');
    await visit(page, '/gov/compliance/firms/1');
    await expect(page.getByRole('heading', { level: 1, name: 'Acme Corp' })).toBeVisible();
    const watch = page.getByRole('button', { name: /^(Watch|Stop watching) Acme Corporate Account/ });
    if ((await watch.getAttribute('aria-pressed')) === 'true') {
      await watch.click();
      await expect(watch).toHaveAttribute('aria-pressed', 'false');
    }
    await watch.click();
    await expect(watch).toHaveAttribute('aria-pressed', 'true');
    await visit(page, '/gov/compliance');
    await expect(page.locator('#compliance-watchlist tbody tr', { hasText: 'Acme' })).toHaveCount(1);
    await page.getByRole('button', { name: /Stop watching Acme Corporate Account/ }).click();
    await expect(page.locator('#compliance-watchlist tbody tr', { hasText: 'Acme' })).toHaveCount(0);
  });

  test('admins create, assign and delete a unit', async ({ page, context }, testInfo) => {
    await shim(context, 'admin');
    const name = `E2E unit ${testInfo.project.name}`;
    await visit(page, '/admin/gov-units');
    await page.getByRole('button', { name: 'New unit' }).click();
    const dialog = page.getByRole('dialog', { name: 'New unit' });
    await dialog.getByLabel('Name').fill(name);
    await expect(dialog.getByLabel('Slug')).toHaveValue(`e2e-unit-${testInfo.project.name}`);
    await dialog.getByRole('button', { name: 'Create unit' }).click();
    await expect(page.locator('#gov-units-table tbody td:first-child', { hasText: name })).toHaveCount(1);
    await page.getByRole('button', { name: `Delete ${name}` }).click();
    await page.getByRole('dialog', { name: `Delete ${name}?` }).getByRole('button', { name: 'Delete unit' }).click();
    await expect(page.locator('#gov-units-table tbody td:first-child', { hasText: name })).toHaveCount(0);
  });
});
