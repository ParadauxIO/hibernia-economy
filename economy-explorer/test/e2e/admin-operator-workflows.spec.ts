import { test, expect } from '@playwright/test';
import mysql from 'mysql2/promise';
import { randomUUID } from 'node:crypto';
import { mkdir } from 'node:fs/promises';
import { testDbOptions } from '../integration/db';
import { visit } from './helpers';

// Requires the real REST app using this same disposable database and a SERVICE key.
// The dispatcher must be off: these tests never send webhook messages.
test.skip(!process.env.ADMIN_STACK_REST_E2E || !process.env.E2E_AUTH_SHIM, 'requires local REST and disposable test identity');
const ALICE = '00000000-0000-0000-0000-00000000a1ce';
const BOB = '00000000-0000-0000-0000-000000000b0b';

test('operators complete recovery, handover and metadata workflows through the real API', async ({ page, context }, testInfo) => {
  test.setTimeout(120_000);
  if (!/test/i.test(process.env.DB_NAME ?? '')) throw new Error('Disposable database required');
  const db = await mysql.createConnection(testDbOptions());
  const suffix = randomUUID().slice(0, 8);
  const firmName = `Operator ${suffix}`;
  const groupName = `Operators ${suffix}`;
  const memo = `operator-retry-${suffix}`;
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', dialog => dialog.accept());
  const base = process.env.E2E_BASE_URL!;
  const screenshot = async (name: string) => {
    if (!process.env.ADMIN_STACK_SCREENSHOTS) return;
    await mkdir(process.env.ADMIN_STACK_SCREENSHOTS, { recursive: true });
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(200);
    await page.screenshot({ path: `${process.env.ADMIN_STACK_SCREENSHOTS}/${name}-${testInfo.project.name}.png`, fullPage: true });
  };
  try {
    await db.query("INSERT IGNORE INTO explorer_role (player_uuid_bin,role) VALUES (uuid_to_bin(?),'admin')", [ALICE]);
    const [firm] = await db.execute<mysql.ResultSetHeader>('INSERT INTO firm (display_name,proprietor_uuid_bin) VALUES (?,uuid_to_bin(?))', [firmName, ALICE]);
    const [account] = await db.execute<mysql.ResultSetHeader>("INSERT INTO accounts (account_type,owner_uuid_bin,display_name) VALUES ('BUSINESS',uuid_to_bin(?),?)", [ALICE, firmName]);
    await db.query('INSERT INTO firm_accounts (firm_id,account_id) VALUES (?,?)', [firm.insertId, account.insertId]);
    await db.query('INSERT INTO account_balances_mat (account_id,balance) VALUES (?,50.00) ON DUPLICATE KEY UPDATE balance=50.00', [account.insertId]);
    const [group] = await db.execute<mysql.ResultSetHeader>('INSERT INTO explorer_group (name,description) VALUES (?,?)', [groupName, 'Original']);
    await db.query("INSERT INTO explorer_group_capability (group_id,capability) VALUES (?,'player')", [group.insertId]);
    await db.query("INSERT INTO explorer_group_member (group_id,player_uuid_bin,source) VALUES (?,uuid_to_bin(?),'manual')", [group.insertId, BOB]);
    const [hook] = await db.execute<mysql.ResultSetHeader>("INSERT INTO webhook_subscription (owner_uuid_bin,key_type,account_id,target_url,secret,active) VALUES (uuid_to_bin(?),'PERSONAL',2,'https://example.com/old',?,0)", [BOB, 'a'.repeat(64)]);
    await db.query("INSERT INTO webhook_delivery (subscription_id,txn_id,account_id,status,attempts,http_status,last_error) VALUES (?,1,2,'FAILED',5,503,'Receiver unavailable')", [hook.insertId]);
    await context.addCookies([{ name: 'e2e_viewer', value: 'admin', url: base }]);

    // Drop only the response: Treasury commits once; a reload and identical retry recover it.
    await visit(page, '/admin/accounts');
    const fillTransfer = async () => {
      await page.getByPlaceholder('From account #').fill(String(account.insertId));
      await page.getByPlaceholder('To account #').fill('2');
      await page.getByPlaceholder('Amount', { exact: true }).fill('1.00');
      await page.getByPlaceholder('Memo', { exact: true }).fill(memo);
    };
    await fillTransfer();
    await page.route('**/admin/accounts', async route => {
      if (route.request().method() !== 'POST') return route.continue();
      await route.fetch();
      await route.abort('failed');
    }, { times: 1 });
    await page.getByRole('button', { name: 'Transfer', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'result is unknown' })).toBeVisible();
    await page.reload();
    await fillTransfer();
    await page.getByRole('button', { name: 'Transfer', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Transferred' })).toBeVisible();
    const [txns] = await db.query<mysql.RowDataPacket[]>('SELECT txn_id FROM ledger_txns WHERE message=?', [memo]);
    expect(txns).toHaveLength(1);
    await screenshot('transfers');

    // A fleet operator can diagnose and repair another player's webhook.
    await visit(page, `/admin/webhooks/${hook.insertId}`);
    await expect(page.getByRole('cell', { name: 'Receiver unavailable' })).toBeVisible();
    await page.getByLabel('Delivery URL').fill('https://example.com/repaired');
    await page.getByRole('button', { name: 'Save URL' }).click();
    await expect(page.getByRole('status').filter({ hasText: 'URL saved' })).toBeVisible();
    await page.getByRole('button', { name: 'Rotate secret' }).click();
    await expect(page.getByText('Copy this secret now. It is shown only once.')).toBeVisible();
    await page.getByRole('button', { name: 'Dismiss secret' }).click();
    await page.reload();
    await expect(page.getByLabel('Delivery URL')).toHaveValue('https://example.com/repaired');
    const [hooks] = await db.query<mysql.RowDataPacket[]>('SELECT secret FROM webhook_subscription WHERE subscription_id=?', [hook.insertId]);
    expect(hooks[0]?.secret).not.toBe('a'.repeat(64));
    const [audit] = await db.query<mysql.RowDataPacket[]>("SELECT after_json FROM explorer_audit WHERE action='webhook.secret.rotate' AND target_id=?", [String(hook.insertId)]);
    expect(JSON.stringify(audit)).not.toContain(hooks[0]?.secret);
    await screenshot('webhook');

    // In-place metadata editing retains memberships and grants.
    await visit(page, `/admin/groups/${group.insertId}`);
    await page.getByLabel('Group name', { exact: true }).fill(`${groupName} renamed`);
    await page.getByRole('textbox', { name: 'Description', exact: true }).fill('Corrected operator group');
    await page.getByRole('button', { name: 'Save group details' }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Group details saved' })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`${groupName} renamed`);
    const [members] = await db.query<mysql.RowDataPacket[]>('SELECT * FROM explorer_group_member WHERE group_id=?', [group.insertId]);
    expect(members).toHaveLength(1);
    await screenshot('group');

    await visit(page, `/admin/firms/${firm.insertId}`);
    await page.getByRole('button', { name: 'Exempt from balance tax' }).click();
    await expect(page.getByRole('button', { name: 'Remove tax exemption' })).toBeVisible();
    await page.getByLabel('New proprietor (name or UUID)').fill('Bob');
    await page.getByLabel(`Retype ${firmName} to confirm`).fill(firmName);
    await page.getByRole('button', { name: 'Change proprietor', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Reconciled 1 account' })).toBeVisible();
    await page.reload();
    const [owners] = await db.query<mysql.RowDataPacket[]>('SELECT bin_to_uuid(owner_uuid_bin) AS owner FROM accounts WHERE account_id=?', [account.insertId]);
    expect(String(owners[0]?.owner).toLowerCase()).toBe(BOB);
    await page.getByRole('button', { name: 'Reconcile account access' }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Reconciled 1 account' })).toBeVisible();
    await screenshot('firm-controls');

    await visit(page, `/admin/firms?q=${encodeURIComponent(firmName)}`);
    await page.getByRole('button', { name: `Manage ${firmName}` }).click();
    await page.getByLabel('Type firm name to confirm').fill(firmName);
    await page.getByRole('button', { name: 'Disband', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: firmName })).toContainText('Disbanded');
    await visit(page, `/admin/firms/${firm.insertId}`);
    await expect(page.getByRole('table', { name: 'Firm accounts' })).toContainText(firmName);
    await expect(page.getByRole('table', { name: 'Firm accounts' })).not.toContainText('Current');
    await page.getByLabel(`Retype ${firmName} to confirm`).fill(firmName);
    await page.getByRole('button', { name: 'Release reserved name' }).click();
    await expect(page.getByText(/Reserved name: Released/)).toBeVisible();
    await page.reload();
    await expect(page.getByText(/Reserved name: Released/)).toBeVisible();
    await screenshot('firm-history');

    await context.addCookies([{ name: 'e2e_viewer', value: 'player', url: base }]);
    await visit(page, `/admin/webhooks/${hook.insertId}`);
    await expect(page.getByRole('button', { name: 'Rotate secret' })).toHaveCount(0);
    await expect(page.locator('.access-gate')).toBeVisible();
    expect(errors).toEqual([]);
  } finally { await db.end(); }
});
