import { test, expect, type BrowserContext } from '@playwright/test';
import mysql from 'mysql2/promise';
import { dataTable, visit, submitReload } from './helpers';
import { testDbOptions } from '../integration/db';

// These require the app running with E2E_TEST_AUTH=1 against the seeded test DB
// (the CI e2e job sets E2E_AUTH_SHIM=1 to enable them). Skipped otherwise so a
// run against a real deployment only exercises the public specs.
test.skip(!process.env.E2E_AUTH_SHIM, 'auth shim not enabled (set E2E_AUTH_SHIM=1 with a seeded test app)');

const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:3000';

async function loginAs(context: BrowserContext, role: string) {
  await context.addCookies([{ name: 'e2e_viewer', value: role, url: baseURL }]);
}

test.describe('admin', () => {
  test.beforeEach(({ context }) => loginAs(context, 'admin'));

  test('can open the whole-economy transactions list', async ({ page }) => {
    await visit(page, '/transactions');
    await expect(page.locator('.privacy-gate')).toHaveCount(0);
    await expect(dataTable(page)).toBeVisible();
  });

  test('can open admin API keys', async ({ page }) => {
    await visit(page, '/admin/api-keys');
    await expect(page.locator('.privacy-gate')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'API keys' })).toBeVisible();
  });

  test('sees the staff-only Sales tab in the market nav', async ({ page }) => {
    await visit(page, '/market');
    await expect(page.locator('.section-tabs a', { hasText: 'Sales' })).toBeVisible();
  });

  test('privileged reads are written to the audit log', async ({ page }) => {
    await visit(page, '/transactions'); // a privileged read of everyone's transactions
    // There is no audit page in the explorer; assert on the row itself. The insert
    // is awaited by the page render, so it is committed once the page has settled.
    const conn = await mysql.createConnection(testDbOptions());
    try {
      const [rows] = await conn.query<mysql.RowDataPacket[]>(
        "SELECT actor_role FROM explorer_audit WHERE actor_sub = 'e2e-admin' AND path = '/transactions'");
      expect(rows.length).toBeGreaterThan(0);
      expect(rows[0]?.actor_role).toBe('admin');
    } finally {
      await conn.end();
    }
  });
});

test.describe('player own-data', () => {
  test.beforeEach(({ context }) => loginAs(context, 'player')); // shim = Alice, owns account #1

  test('sees their own account history', async ({ page }) => {
    await visit(page, '/accounts/1');
    await expect(page.locator('.privacy-gate')).toHaveCount(0);
    // account-detail renders several tables (txns, counterparties, …) when
    // history is visible — assert at least one is present.
    await expect(dataTable(page).first()).toBeVisible();
  });

  test('cannot see another account’s history', async ({ page }) => {
    await visit(page, '/accounts/2'); // Bob's account
    await expect(page.locator('.privacy-gate')).toBeVisible();
  });
});


test.describe('moderator investigations', () => {
  test.beforeEach(({ context }) => loginAs(context, 'moderator'));
  test('can investigate but cannot open administrative controls', async ({ page }) => {
    await visit(page, '/transactions');
    await expect(page.locator('.privacy-gate')).toHaveCount(0);
    await visit(page, '/admin/groups');
    await expect(page.locator('.privacy-gate')).toBeVisible();
  });
});
test.describe('government relationships', () => {
  test.beforeEach(({ context }) => loginAs(context, 'government'));
  test('cannot inspect unrelated players or the global sales feed', async ({ page }) => {
    await visit(page, '/accounts/2');
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await visit(page, '/chestshop/sales');
    await expect(page.locator('.privacy-gate')).toBeVisible();
  });
});


test('configures custom roles and enforces changes in the admin tools', async ({ page, context }, testInfo) => {
  const key = `test_reader_${testInfo.project.name}`;
  const conn = await mysql.createConnection(testDbOptions());
  try {
    // The shim authenticates Alice; policy mutations also verify her live DB grants.
    await conn.query("INSERT IGNORE INTO explorer_role (player_uuid_bin, role) VALUES (UNHEX('0000000000000000000000000000A1CE'), 'admin')");
    await conn.query('DELETE FROM explorer_role_definition WHERE role_key = ?', [key]);
    await loginAs(context, 'admin');
    await visit(page, '/admin/roles');
    await page.getByLabel('Role key', { exact: true }).fill(key);
    await page.getByLabel('Display name', { exact: true }).fill('Test key reader');
    await page.getByRole('checkbox', { name: /^api_keys\.read\b/ }).check();
    await submitReload(page, page.getByRole('button', { name: 'Create role', exact: true }));
    await expect(page).toHaveURL(new RegExp(`/admin/roles/${key}$`));
    await page.getByLabel('Player name or UUID').fill('Bob');
    await submitReload(page, page.getByRole('button', { name: 'Assign role', exact: true }));
    await expect(page.getByRole('cell', { name: 'Bob', exact: true })).toBeVisible();

    await loginAs(context, key);
    await visit(page, '/admin/api-keys');
    await expect(page.getByRole('heading', { name: 'API keys', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /Revoke|Rotate|Limit/ })).toHaveCount(0);
    await visit(page, '/admin/groups');
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await visit(page, '/admin/roles');
    await expect(page.locator('.privacy-gate')).toBeVisible();

    await loginAs(context, 'admin');
    await visit(page, `/admin/roles/${key}`);
    await page.getByRole('checkbox', { name: /^api_keys\.read\b/ }).uncheck();
    await page.getByRole('checkbox', { name: /^market\.sales\.read\b/ }).check();
    await submitReload(page, page.getByRole('button', { name: 'Save permissions', exact: true }));
    await expect.poll(async () => {
      const [rows] = await conn.query<mysql.RowDataPacket[]>('SELECT revision FROM explorer_role_definition WHERE role_key = ?', [key]);
      return rows[0]?.revision;
    }).toBe(2);
    await loginAs(context, key);
    await visit(page, '/admin/api-keys');
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await visit(page, '/chestshop/sales');
    await expect(page.locator('.privacy-gate')).toHaveCount(0);

    await loginAs(context, 'admin');
    await visit(page, `/admin/roles/${key}`);
    page.once('dialog', dialog => dialog.accept());
    await submitReload(page, page.getByRole('button', { name: 'Delete role', exact: true }));
    await expect(page).toHaveURL(/\/admin\/roles$/);
    const [grants] = await conn.query<mysql.RowDataPacket[]>('SELECT role FROM explorer_role WHERE role = ?', [key]);
    expect(grants).toHaveLength(0);
    await loginAs(context, key);
    await visit(page, '/chestshop/sales');
    await expect(page.locator('.privacy-gate')).toBeVisible();
  } finally {
    await conn.query('DELETE FROM explorer_role_definition WHERE role_key = ?', [key]);
    await conn.end();
  }
});

test('government accounts and fines have separate views with preserved filters', async ({ page, context }) => {
  await loginAs(context, 'admin');
  await visit(page, '/government');
  // Scope to the live page: streamed navigation can temporarily leave a
  // second copy of the controls in React's hidden containers outside main.
  const main = page.getByRole('main');
  const status = main.getByRole('combobox', { name: 'Fine status' });
  await expect(main.locator('.card-title', { hasText: 'Government accounts' })).toBeVisible();
  await Promise.all([
    page.waitForURL(/view=fines/),
    main.getByRole('navigation', { name: 'Government views' }).getByRole('link', { name: 'Fines', exact: true }).click(),
  ]);
  await expect(status).toBeEnabled();
  await status.selectOption('revoked');
  const week = main.getByRole('link', { name: '7d', exact: true });
  await expect(week).toHaveAttribute('href', /status=revoked/);
  await Promise.all([page.waitForURL(/days=7/), week.click()]);
  await expect(page).toHaveURL(/view=fines/);
  await expect(page).toHaveURL(/status=revoked/);
  await expect(status).toHaveValue('revoked');
});
