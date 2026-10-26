import { test, expect, type BrowserContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import mysql from 'mysql2/promise';
import { submitReload, visit } from './helpers';
import { testDbOptions } from '../integration/db';

// The audit log, group sources and the admin workspace shell (PAR-542). Uses the
// role shim (E2E_TEST_AUTH=1): the shim viewer is Alice, and policy mutations
// re-check her live grants, so she also gets a real admin role row.
test.skip(!process.env.E2E_AUTH_SHIM, 'auth shim not enabled (set E2E_AUTH_SHIM=1 with a seeded test app)');

const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:3000';
const ALICE = '00000000-0000-0000-0000-00000000a1ce';

async function loginAs(context: BrowserContext, role: string) {
  await context.addCookies([{ name: 'e2e_viewer', value: role, url: baseURL }]);
}

test('an admin change shows up in the audit log with its before and after values', async ({ page, context }, testInfo) => {
  if (!/test/i.test(process.env.DB_NAME ?? '')) throw new Error('Disposable test database required');
  const conn = await mysql.createConnection(testDbOptions());
  const name = `Audit ${randomUUID().slice(0, 8)}`;
  try {
    await conn.query("INSERT IGNORE INTO explorer_role (player_uuid_bin, role) VALUES (UNHEX(REPLACE(?, '-', '')), 'admin')", [ALICE]);
    await loginAs(context, 'admin');

    await visit(page, '/admin/groups');
    await page.getByPlaceholder('Group name (e.g. DOC)').fill(name);
    await submitReload(page, page.getByRole('button', { name: 'Create group' }));
    await page.getByRole('link', { name, exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();

    // Link a firm source; the page explains the group → role chain and shows live counts.
    await expect(page.getByRole('heading', { name: 'Roles this group grants' })).toBeVisible();
    await page.getByPlaceholder(/^Firm id/).fill('1');
    await submitReload(page, page.getByRole('button', { name: 'Save firm source' }));
    await expect(page.getByRole('heading', { name: 'Firm · 0 live' })).toBeVisible();
    await expect(page.getByPlaceholder(/^Firm id/)).toHaveValue('1');
    await page.getByPlaceholder(/^world-uuid/).fill('not-a-region');
    await page.getByRole('button', { name: 'Save region source' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'world-uuid/region-id' })).toBeVisible();

    // The sidebar marks the tool that owns this sub-route (phones get a select instead).
    if (testInfo.project.name !== 'mobile') await expect(page.getByRole('navigation', { name: 'Admin' }).getByRole('link', { name: 'Groups' })).toHaveAttribute('aria-current', 'page');

    await visit(page, `/admin/audit?f=action:in:group-source-update&f=actor:in:${ALICE}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Audit log' })).toBeVisible();
    const row = page.getByRole('row').filter({ hasText: 'Group: change firm or region source' }).first();
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: /^Details for entry/ }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('cell', { name: 'selector', exact: true })).toBeVisible();
    await expect(dialog.getByRole('cell', { name: '1', exact: true })).toBeVisible();
    await expect(dialog.getByText('Changed', { exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: 'Close' }).click();
    await expect(dialog).toBeHidden();

    // The creation is there too, as its own action.
    await visit(page, `/admin/audit?f=action:in:group-create&q=${encodeURIComponent('/admin/groups')}`);
    await expect(page.getByRole('row').filter({ hasText: 'Group: create' }).first()).toBeVisible();
    // An unknown chip is dropped, never an error.
    await visit(page, '/admin/audit?f=action:in:drop-tables&f=nope:eq:1');
    await expect(page.getByRole('heading', { level: 1, name: 'Audit log' })).toBeVisible();
  } finally {
    await conn.query('DELETE FROM explorer_group WHERE name = ?', [name]);
    await conn.end();
  }
});

test('the audit log needs audit.read, even for other admin tools', async ({ page, context }) => {
  await loginAs(context, 'moderator');
  await visit(page, '/admin/audit');
  await expect(page.getByText('Audit log access required')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Admin' }).getByRole('link', { name: 'Audit log' })).toHaveCount(0);

  await context.clearCookies();
  await loginAs(context, 'player');
  await visit(page, '/admin/audit');
  await expect(page.locator('.access-gate')).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
});

test('the admin home is a quiet index of the tools the viewer holds', async ({ page, context }) => {
  await loginAs(context, 'admin');
  await visit(page, '/admin');
  for (const tool of ['Accounts', 'Role grants', 'Audit log']) {
    await expect(page.getByRole('main').getByRole('link', { name: tool, exact: true }).first()).toBeVisible();
  }
  await page.getByRole('main').getByRole('link', { name: 'Role grants', exact: true }).first().click();
  await expect(page).toHaveURL(/\/admin\/role-grants$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Role grants' })).toBeVisible();
});
