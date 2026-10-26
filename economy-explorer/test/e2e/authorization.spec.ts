import { test as base, expect, type BrowserContext } from '@playwright/test';
import { encode } from 'next-auth/jwt';
import { randomUUID } from 'node:crypto';
import mysql, { type Connection, type RowDataPacket, type ResultSetHeader } from 'mysql2/promise';
import { visit, submitReload } from './helpers';
import { testDbOptions } from '../integration/db';

// No e2e_viewer cookie: these tests use signed Auth.js sessions, the real request
// proxy (proxy.ts), live identity/role resolution and the real database. Only the
// external identity provider and LP server are represented by session/sync fixtures.
const secret = process.env.E2E_AUTH_SECRET;
const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100';
const bin = (uuid: string) => Buffer.from(uuid.replaceAll('-', ''), 'hex');
async function signIn(context: BrowserContext, uuid: string, maxAge = 3600) {
  const name = baseURL.startsWith('https:') ? '__Secure-authjs.session-token' : 'authjs.session-token';
  const token = await encode({ secret: secret!, salt: name, maxAge, token: { sub: `auth-test-${uuid}`, minecraftUuid: uuid, minecraftName: 'TestPlayer' } });
  await context.clearCookies();
  await context.addCookies([{ name, value: token, url: baseURL, httpOnly: true, sameSite: 'Lax', secure: baseURL.startsWith('https:') }]);
}

type Fixture = { conn: Connection; admin: string; player: string; key: string; groupName: string };
const test = base.extend<{ policy: Fixture }>({
  policy: async ({}, use) => {
    if (!/test/i.test(process.env.DB_NAME ?? '')) throw new Error('Authorization E2E requires a disposable DB_NAME containing test.');
    const conn = await mysql.createConnection(testDbOptions());
    const admin = randomUUID(), player = randomUUID(), key = `test_${randomUUID().slice(0, 8)}`;
    const groupName = `Group ${key}`;
    await conn.query('INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, ?)', [bin(admin), 'admin']);
    try { await use({ conn, admin, player, key, groupName }); }
    finally {
      const [groups] = await conn.query<RowDataPacket[]>('SELECT group_id FROM explorer_group WHERE name = ?', [groupName]);
      for (const group of groups) {
        await conn.query('DELETE FROM explorer_group_member WHERE group_id = ?', [group.group_id]);
        await conn.query('DELETE FROM explorer_group_capability WHERE group_id = ?', [group.group_id]);
      }
      await conn.query('DELETE FROM explorer_group WHERE name = ?', [groupName]);
      await conn.query('DELETE FROM explorer_role_definition WHERE role_key = ?', [key]);
      for (const uuid of [admin, player]) {
        await conn.query('DELETE FROM account_access WHERE subject_uuid_bin = ?', [bin(uuid)]);
        await conn.query('DELETE FROM explorer_lp_group_member WHERE player_uuid_bin = ?', [bin(uuid)]);
        await conn.query('DELETE FROM firm_employee WHERE player_uuid_bin = ?', [bin(uuid)]);
        await conn.query('DELETE FROM explorer_role WHERE player_uuid_bin = ?', [bin(uuid)]);
        await conn.query('DELETE FROM explorer_identity WHERE player_uuid_bin = ?', [bin(uuid)]);
      }
      await conn.query('DELETE FROM account_group_access WHERE lp_group = ?', [key]);
      await conn.end();
    }
  },
});
test.skip(!secret, 'Set E2E_AUTH_SECRET to the local test app AUTH_SECRET; managed Playwright runs configure it automatically.');

async function createRole(conn: Connection, key: string, permissions: string[]) {
  await conn.query('INSERT INTO explorer_role_definition (role_key, name) VALUES (?, ?)', [key, key]);
  for (const permission of permissions) await conn.query('INSERT INTO explorer_role_permission VALUES (?, ?)', [key, permission]);
}
async function permission(context: BrowserContext, name: string) {
  const response = await context.request.get(`${baseURL}/api/viewer`);
  expect(response.ok()).toBeTruthy();
  return (await response.json()).permissions.includes(name);
}

test('edge rejects forged and expired sessions; valid sessions still need live permissions', async ({ context, page, policy }) => {
  for (const forged of ['', 'forged-session']) {
    await context.clearCookies();
    if (forged) await context.addCookies([{ name: 'authjs.session-token', value: forged, url: baseURL }]);
    const response = await context.request.get(`${baseURL}/admin/roles`, { maxRedirects: 0 });
    expect(response.status()).toBe(307);
    expect(response.headers().location).toContain('/api/auth/signin');
  }
  await signIn(context, policy.player, -60);
  expect((await context.request.get(`${baseURL}/admin/roles`, { maxRedirects: 0 })).status()).toBe(307);
  await signIn(context, policy.player);
  await visit(page, '/admin/roles');
  await expect(page.locator('.access-gate')).toBeVisible();
  await policy.conn.query('INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, ?)', [bin(policy.player), 'moderator']);
  await visit(page, '/accounts/2');
  await expect(page.locator('.privacy-gate')).toHaveCount(0);
  await visit(page, '/admin/groups');
  await expect(page.locator('.access-gate')).toBeVisible();
  await policy.conn.query('DELETE FROM explorer_role WHERE player_uuid_bin = ?', [bin(policy.player)]);
  await visit(page, '/accounts/2');
  await expect(page.locator('.privacy-gate')).toBeVisible();
});

test('role editor changes a real assignee’s access without replacing their session', async ({ context, page, browser, policy }) => {
  const recipient = await browser.newContext();
  try {
    await signIn(recipient, policy.player);
    await signIn(context, policy.admin);
    await visit(page, '/admin/roles');
    await page.getByLabel('Role key', { exact: true }).fill(policy.key);
    await page.getByLabel('Display name', { exact: true }).fill(policy.key);
    await page.getByRole('checkbox', { name: /^api_keys\.read\b/ }).check();
    await submitReload(page, page.getByRole('button', { name: 'Create role', exact: true }));
    await expect(page).toHaveURL(new RegExp(`/admin/roles/${policy.key}$`));
    await page.getByLabel('Player name or UUID').fill(policy.player);
    await submitReload(page, page.getByRole('button', { name: 'Assign role', exact: true }));
    await expect.poll(() => permission(recipient, 'api_keys.read')).toBe(true);
    const target = await recipient.newPage();
    await visit(target, `${baseURL}/admin/api-keys`);
    await expect(target.getByRole('heading', { name: 'API keys', exact: true })).toBeVisible();
    await expect(target.getByRole('button', { name: /^(Revoke key|Rotate key|Limit$)/ })).toHaveCount(0);
    await page.getByRole('checkbox', { name: /^api_keys\.read\b/ }).uncheck();
    await page.getByRole('checkbox', { name: /^market\.sales\.read\b/ }).check();
    await submitReload(page, page.getByRole('button', { name: 'Save permissions', exact: true }));
    await expect.poll(() => permission(recipient, 'api_keys.read')).toBe(false);
    await expect.poll(() => permission(recipient, 'market.sales.read')).toBe(true);
    await visit(target, `${baseURL}/admin/api-keys`);
    await expect(target.locator('.access-gate')).toBeVisible();
    await visit(page, `/admin/roles/${policy.key}`);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
    await submitReload(page, page.getByRole('button', { name: 'Remove direct assignment' }));
    await expect.poll(() => permission(recipient, 'market.sales.read')).toBe(false);
    page.once('dialog', dialog => dialog.accept());
    await submitReload(page, page.getByRole('button', { name: 'Delete role', exact: true }));
    await expect(page).toHaveURL(/\/admin\/roles$/);
    await expect(page.getByRole('link', { name: policy.key, exact: true })).toHaveCount(0);
  } finally { await recipient.close(); }
});

test('group editor grants custom roles, preserves independent sources, and clears LP grants on source edits', async ({ context, page, browser, policy }) => {
  await createRole(policy.conn, policy.key, ['transactions.read']);
  await signIn(context, policy.admin);
  const recipient = await browser.newContext();
  try {
    await signIn(recipient, policy.player);
    await visit(page, '/admin/groups');
    await page.getByPlaceholder('Group name (e.g. DOC)').fill(policy.groupName);
    await submitReload(page, page.getByRole('button', { name: 'Create group' }));
    await page.getByRole('link', { name: policy.groupName, exact: true }).click();
    await page.getByRole('checkbox', { name: new RegExp(policy.key) }).check();
    await submitReload(page, page.getByRole('button', { name: 'Save roles' }));
    await expect(page.getByText('In sync.', { exact: true })).toBeVisible();
    await page.getByPlaceholder('Player name or UUID', { exact: true }).fill(policy.player);
    await submitReload(page, page.getByRole('button', { name: 'Add member', exact: true }));
    await expect.poll(() => permission(recipient, 'transactions.read')).toBe(true);
    await expect(page.getByRole('button', { name: 'Remove', exact: true })).toBeEnabled();
    // A direct grant survives removing the independent manual group membership.
    await policy.conn.query('INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, ?)', [bin(policy.player), policy.key]);
    page.once('dialog', dialog => dialog.accept());
    await submitReload(page, page.getByRole('button', { name: 'Remove', exact: true }));
    await expect(page.getByText('No members.', { exact: true })).toBeVisible();
    expect(await permission(recipient, 'transactions.read')).toBe(true);
    await policy.conn.query('DELETE FROM explorer_role WHERE player_uuid_bin = ?', [bin(policy.player)]);
    expect(await permission(recipient, 'transactions.read')).toBe(false);
    const source = page.getByPlaceholder('LuckPerms group name', { exact: false });
    await source.fill('group.staff');
    await submitReload(page, page.getByRole('button', { name: 'Save', exact: true }));
    const groupId = Number(new URL(page.url()).pathname.split('/').pop());
    await expect.poll(async () => {
      const [rows] = await policy.conn.query<RowDataPacket[]>('SELECT luckperms_node FROM explorer_group WHERE group_id = ?', [groupId]);
      return rows[0]?.luckperms_node;
    }).toBe('group.staff');
    // This is the same leased row written by the plugin (verified separately in plugin ITs).
    await policy.conn.query("INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at) VALUES (?, ?, 'luckperms', NOW())", [groupId, bin(policy.player)]);
    expect(await permission(recipient, 'transactions.read')).toBe(true);
    await policy.conn.query('UPDATE explorer_group_member SET synced_at = NOW() - INTERVAL 2 HOUR WHERE group_id = ?', [groupId]);
    expect(await permission(recipient, 'transactions.read')).toBe(false);
    await policy.conn.query('UPDATE explorer_group_member SET synced_at = NOW() WHERE group_id = ?', [groupId]);
    await source.fill('group.other');
    await submitReload(page, page.getByRole('button', { name: 'Save', exact: true }));
    await expect.poll(() => permission(recipient, 'transactions.read')).toBe(false);
    await visit(page, '/admin/groups');
    const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: policy.groupName, exact: true }) });
    page.once('dialog', dialog => dialog.accept());
    await submitReload(page, row.getByRole('button', { name: 'Delete', exact: true }));
    await expect(page.getByRole('link', { name: policy.groupName, exact: true })).toHaveCount(0);
  } finally { await recipient.close(); }
});

test('direct and LP account relationships are scoped and revocable; government requires both grants', async ({ context, page, policy }) => {
  await signIn(context, policy.player);
  await visit(page, '/accounts/2');
  await expect(page.locator('.privacy-gate')).toBeVisible();
  await policy.conn.query("INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin) VALUES (2, ?, 'VIEWER', ?)", [bin(policy.player), bin(policy.admin)]);
  await visit(page, '/accounts/2');
  await expect(page.locator('.privacy-gate')).toHaveCount(0);
  await visit(page, '/accounts/1');
  await expect(page.locator('.privacy-gate')).toBeVisible();
  await policy.conn.query('UPDATE account_access SET removed_at = NOW() WHERE subject_uuid_bin = ?', [bin(policy.player)]);
  await visit(page, '/accounts/2');
  await expect(page.locator('.privacy-gate')).toBeVisible();
  await policy.conn.query("INSERT INTO account_group_access (account_id, lp_group, level, added_by_uuid_bin) VALUES (5, ?, 'VIEWER', ?)", [policy.key, bin(policy.admin)]);
  await policy.conn.query('INSERT INTO explorer_lp_group_member (lp_group, player_uuid_bin, synced_at) VALUES (?, ?, NOW())', [policy.key, bin(policy.player)]);
  await visit(page, '/accounts/5');
  await expect(page.locator('.privacy-gate')).toHaveCount(0);
  await visit(page, '/government');
  await expect(page.locator('.privacy-gate')).toBeVisible();
  await policy.conn.query("INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, 'government')", [bin(policy.player)]);
  await visit(page, '/government');
  await expect(page.locator('.privacy-gate')).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'City Hall #5', exact: true })).toBeVisible();
  await policy.conn.query('UPDATE explorer_lp_group_member SET synced_at = NOW() - INTERVAL 2 HOUR WHERE player_uuid_bin = ?', [bin(policy.player)]);
  await visit(page, '/government');
  await expect(page.getByRole('link', { name: 'City Hall #5', exact: true })).toHaveCount(0);
  await visit(page, '/accounts/5');
  await expect(page.locator('.privacy-gate')).toBeVisible();
});

test('the Government entry needs government.read and a government account in scope (PAR-536)', async ({ context, page, policy }) => {
  await page.setViewportSize({ width: 1280, height: 800 }); // the primary nav is in the Menu on phones
  const primary = page.getByRole('navigation', { name: 'Primary' });
  // government.read with nothing in scope: no entry (the nav decision is cached per player for a minute,
  // so the scoped case below uses a second player rather than changing this one).
  await policy.conn.query("INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, 'government')", [bin(policy.player)]);
  await signIn(context, policy.player);
  await visit(page, '/');
  await expect(primary.getByRole('link', { name: 'Economy' })).toBeVisible();
  await expect(primary.getByRole('link', { name: 'Government' })).toHaveCount(0);

  const scoped = randomUUID();
  try {
    await policy.conn.query("INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, 'government')", [bin(scoped)]);
    await policy.conn.query("INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin) VALUES (5, ?, 'VIEWER', ?)", [bin(scoped), bin(policy.admin)]);
    await signIn(context, scoped);
    await visit(page, '/');
    await expect(primary.getByRole('link', { name: 'Government' })).toHaveAttribute('href', '/government');
    // The account chip counts personal money only; this player owns no account.
    await expect(page.getByRole('button', { name: /TestPlayer/ })).not.toContainText('$');
  } finally {
    await policy.conn.query('DELETE FROM account_access WHERE subject_uuid_bin = ?', [bin(scoped)]);
    await policy.conn.query('DELETE FROM explorer_role WHERE player_uuid_bin = ?', [bin(scoped)]);
    await policy.conn.query('DELETE FROM explorer_identity WHERE player_uuid_bin = ?', [bin(scoped)]);
  }
});

test('government account members can open related transactions from My data; revoked access is denied', async ({ context, page, policy }) => {
  // Each run owns its account so shared history caches cannot retain another run's postings.
  const [account] = await policy.conn.query<ResultSetHeader>("INSERT INTO accounts (account_type, owner_uuid_bin, display_name) VALUES ('GOVERNMENT', ?, ?)", [bin(policy.admin), policy.key]);
  const accountId = account.insertId;
  const accountName = `${policy.key} #${accountId}`;
  const [txn] = await policy.conn.query<ResultSetHeader>("INSERT INTO ledger_txns (message, initiator_uuid_bin, plugin_system) VALUES (?, ?, 'treasury')", [policy.key, bin(policy.admin)]);
  const txnId = txn.insertId;
  try {
    await policy.conn.query('INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (?, ?, -1.00), (?, 2, 1.00)', [txnId, accountId, txnId]);
    await signIn(context, policy.player);
    await visit(page, `/transactions/${txnId}`);
    await expect(page.getByText('Transaction access required', { exact: true })).toBeVisible();
    await policy.conn.query("INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin) VALUES (?, ?, 'MEMBER', ?)", [accountId, bin(policy.player), bin(policy.admin)]);
    await visit(page, '/me');
    await page.getByRole('link', { name: accountName, exact: true }).click();
    await page.getByRole('link', { name: `#${txnId}`, exact: true }).click();
    await expect(page.getByRole('heading', { name: `Txn #${txnId}` })).toBeVisible();
    await expect(page.getByRole('link', { name: accountName, exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Bob #2', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '← My data', exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Bob #2', exact: true }).click();
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await visit(page, '/transactions/2');
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await visit(page, '/transactions');
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await policy.conn.query('UPDATE account_access SET removed_at = NOW() WHERE subject_uuid_bin = ?', [bin(policy.player)]);
    await visit(page, `/transactions/${txnId}`);
    await expect(page.locator('.privacy-gate')).toBeVisible();
    await policy.conn.query("INSERT INTO account_group_access (account_id, lp_group, level, added_by_uuid_bin) VALUES (?, ?, 'VIEWER', ?)", [accountId, policy.key, bin(policy.admin)]);
    await policy.conn.query('INSERT INTO explorer_lp_group_member (lp_group, player_uuid_bin, synced_at) VALUES (?, ?, NOW())', [policy.key, bin(policy.player)]);
    await visit(page, `/transactions/${txnId}`);
    await expect(page.getByRole('heading', { name: `Txn #${txnId}` })).toBeVisible();
    await policy.conn.query('UPDATE explorer_lp_group_member SET synced_at = NOW() - INTERVAL 2 HOUR WHERE player_uuid_bin = ?', [bin(policy.player)]);
    await visit(page, `/transactions/${txnId}`);
    await expect(page.locator('.privacy-gate')).toBeVisible();
  } finally {
    await policy.conn.query('DELETE FROM ledger_postings WHERE txn_id = ?', [txnId]);
    await policy.conn.query('DELETE FROM ledger_txns WHERE txn_id = ?', [txnId]);
    await policy.conn.query('DELETE FROM account_access WHERE account_id = ?', [accountId]);
    await policy.conn.query('DELETE FROM account_group_access WHERE account_id = ?', [accountId]);
    await policy.conn.query('DELETE FROM account_balances_mat WHERE account_id = ?', [accountId]);
    await policy.conn.query('DELETE FROM accounts WHERE account_id = ?', [accountId]);
  }
});

test('firm roster membership does not grant finances until the member has a finance role', async ({ context, page, policy }) => {
  await signIn(context, policy.player);
  await policy.conn.query('INSERT INTO firm_employee (firm_id, player_uuid_bin, role_id, joined_at) VALUES (1, ?, 3, NOW())', [bin(policy.player)]);
  await visit(page, '/firms/Acme%20Corp');
  await expect(page.getByText('Worker', { exact: true }).first()).toBeVisible();
  await visit(page, '/chestshop/firms/1');
  await expect(page.locator('.privacy-gate')).toBeVisible();
  await policy.conn.query('UPDATE firm_employee SET role_id = 2 WHERE player_uuid_bin = ?', [bin(policy.player)]);
  await visit(page, '/chestshop/firms/1');
  await expect(page.locator('.privacy-gate')).toHaveCount(0);
  await visit(page, '/accounts/3');
  await expect(page.locator('.privacy-gate')).toHaveCount(0);
  // Business records who removed the member alongside left_at (V1 CHECK constraint).
  await policy.conn.query('UPDATE firm_employee SET left_at = NOW(), removed_by_uuid_bin = ? WHERE player_uuid_bin = ?', [bin(policy.admin), bin(policy.player)]);
  await visit(page, '/accounts/3');
  await expect(page.locator('.privacy-gate')).toBeVisible();
});

test('an open role editor cannot save after the operator’s assignment is revoked', async ({ context, page, policy }) => {
  await createRole(policy.conn, policy.key, []);
  await signIn(context, policy.admin);
  await visit(page, `/admin/roles/${policy.key}`);
  await page.getByLabel('Display name', { exact: true }).fill('Should not be saved');
  await policy.conn.query('DELETE FROM explorer_role WHERE player_uuid_bin = ?', [bin(policy.admin)]);
  await page.getByRole('button', { name: 'Save permissions', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Forbidden' })).toBeVisible();
  const [rows] = await policy.conn.query<RowDataPacket[]>('SELECT name, revision FROM explorer_role_definition WHERE role_key = ?', [policy.key]);
  expect(rows[0]).toMatchObject({ name: policy.key, revision: 1 });
});

test('Business and Realty group memberships show their sources and revoke access independently', async ({ context, page, browser, policy }) => {
  await createRole(policy.conn, policy.key, ['account.read.all']);
  const [insert] = await policy.conn.query<ResultSetHeader>('INSERT INTO explorer_group (name) VALUES (?)', [policy.groupName]);
  const groupId = insert.insertId;
  await policy.conn.query('INSERT INTO explorer_group_capability VALUES (?, ?)', [groupId, policy.key]);
  await policy.conn.query("INSERT INTO explorer_group_sync VALUES (?, 'business', '7'), (?, 'realty', 'world/plot')", [groupId, groupId]);
  await policy.conn.query("INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at, sync_selector) VALUES (?, ?, 'business', NOW(), '7'), (?, ?, 'realty', NOW(), 'world/plot')", [groupId, bin(policy.player), groupId, bin(policy.player)]);
  const recipient = await browser.newContext();
  try {
    await signIn(context, policy.admin);
    await signIn(recipient, policy.player);
    await visit(page, `/admin/groups/${groupId}`);
    await expect(page.getByText('business: synced', { exact: true })).toBeVisible();
    await expect(page.getByText('realty: synced', { exact: true })).toBeVisible();
    expect(await permission(recipient, 'account.read.all')).toBe(true);
    await policy.conn.query("UPDATE explorer_group_sync SET selector = '8' WHERE group_id = ? AND source = 'business'", [groupId]);
    await page.reload();
    await expect(page.getByText('business: sync expired', { exact: true })).toBeVisible();
    expect(await permission(recipient, 'account.read.all')).toBe(true);
    await policy.conn.query("DELETE FROM explorer_group_sync WHERE group_id = ? AND source = 'realty'", [groupId]);
    expect(await permission(recipient, 'account.read.all')).toBe(false);
    await page.reload();
    await expect(page.getByText('realty: sync expired', { exact: true })).toBeVisible();
  } finally { await recipient.close(); }
});
