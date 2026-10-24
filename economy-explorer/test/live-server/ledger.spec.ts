import { test, expect, type BrowserContext } from '@playwright/test';
import { encode } from 'next-auth/jwt';
import { readFileSync } from 'node:fs';
import mysql, { type ResultSetHeader, type RowDataPacket } from 'mysql2/promise';

type Fixture = {
  senderUuid: string; senderName: string; senderAccount: number;
  recipientUuid: string; recipientName: string; recipientAccount: number;
  outsiderUuid: string; outsiderName: string; outsiderFirm: number; outsiderFirmName: string; firmAccount: number; itemKey: string; transactionId: number; memo: string;
};
const fixture: Fixture = JSON.parse(readFileSync(process.env.IT_EXPLORER_FIXTURE!, 'utf8'));

async function signIn(context: BrowserContext, uuid: string, name: string) {
  const cookie = 'authjs.session-token';
  const token = await encode({ secret: process.env.E2E_AUTH_SECRET!, salt: cookie, maxAge: 3600,
    token: { sub: `live-${uuid}`, minecraftUuid: uuid, minecraftName: name } });
  await context.clearCookies();
  await context.addCookies([{ name: cookie, value: token, url: process.env.E2E_BASE_URL!,
    httpOnly: true, sameSite: 'Lax' }]);
}

test('real in-game payment is public as a balance but private as history', async ({ page, context }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => {
    if (response.url().startsWith(process.env.E2E_BASE_URL!) && response.status() >= 500) {
      errors.push(`HTTP ${response.status()} ${new URL(response.url()).pathname}`);
    }
  });
  await page.goto(`/accounts/${fixture.senderAccount}`);
  await expect(page.getByText('Current balance', { exact: true })).toBeVisible();
  await expect(page.locator('.kpi-value').filter({ hasText: '9,976.55' })).toBeVisible();
  await expect(page.getByText('Transaction history is private', { exact: true })).toBeVisible();
  await expect(page.getByText(fixture.memo, { exact: false })).toHaveCount(0);

  await page.goto(`/transactions/${fixture.transactionId}`);
  await expect(page.getByText('Transaction detail is private', { exact: true })).toBeVisible();
  await expect(page.getByText(fixture.memo, { exact: false })).toHaveCount(0);

  await signIn(context, fixture.senderUuid, fixture.senderName);
  await page.goto(`/accounts/${fixture.senderAccount}`);
  await expect(page.getByRole('region', { name: 'Recent activity' })).toContainText(fixture.memo);
  await expect(page.getByText('Transaction history is private', { exact: true })).toHaveCount(0);
  await page.goto(`/transactions/${fixture.transactionId}`);
  await expect(page.getByText(fixture.memo, { exact: false }).first()).toBeVisible();

  await signIn(context, fixture.outsiderUuid, fixture.outsiderName);
  await page.goto(`/accounts/${fixture.senderAccount}`);
  await expect(page.getByText('Transaction history is private', { exact: true })).toBeVisible();
  await expect(page.getByText(fixture.memo, { exact: false })).toHaveCount(0);

  await page.goto(`/transactions/${fixture.transactionId}`);
  await expect(page.getByText('Transaction access required', { exact: true })).toBeVisible();
  await expect(page.getByText(fixture.memo, { exact: false })).toHaveCount(0);

  await signIn(context, fixture.recipientUuid, fixture.recipientName);
  await page.goto(`/accounts/${fixture.recipientAccount}`);
  await expect(page.locator('.kpi-value').filter({ hasText: '10,023.45' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Recent activity' })).toContainText(fixture.memo);
  await page.reload();
  await expect(page.getByRole('region', { name: 'Recent activity' })).toContainText(fixture.memo);
  expect(errors).toEqual([]);
});


test('scheduled Business grants revoke in the same browser session and expired leases fail closed', async ({ page, context }) => {
  const connection = await mysql.createConnection({
    host: process.env.DB_HOST!, port: Number(process.env.DB_PORT), database: process.env.DB_NAME!,
    user: process.env.DB_USERNAME!, password: process.env.DB_PASSWORD!,
  });
  const role = `live_${fixture.outsiderName}`;
  const uuid = Buffer.from(fixture.outsiderUuid.replaceAll('-', ''), 'hex');
  let group: number | undefined;
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const allowed = async () => {
    const response = await context.request.get('/api/viewer');
    expect(response.ok()).toBeTruthy();
    return (await response.json()).permissions.includes('account.read.all');
  };
  const visible = async (access: boolean) => {
    await page.goto(`/accounts/${fixture.senderAccount}`);
    if (access) {
      await expect(page.getByRole('region', { name: 'Recent activity' })).toContainText(fixture.memo);
    } else {
      await expect(page.getByText('Transaction history is private', { exact: true })).toBeVisible();
      await expect(page.getByText(fixture.memo, { exact: false })).toHaveCount(0);
    }
  };
  try {
    await connection.execute('INSERT INTO explorer_role_definition(role_key,name) VALUES (?,?)', [role, role]);
    await connection.execute('INSERT INTO explorer_role_permission(role_key,permission) VALUES (?,?)', [role, 'account.read.all']);
    const [insert] = await connection.execute<ResultSetHeader>('INSERT INTO explorer_group(name) VALUES (?)', [role]);
    group = insert.insertId;
    await connection.execute('INSERT INTO explorer_group_capability(group_id,capability) VALUES (?,?)', [group, role]);
    await signIn(context, fixture.outsiderUuid, fixture.outsiderName);
    expect(await allowed()).toBe(false);
    await visible(false);
    await connection.execute("INSERT INTO explorer_group_sync(group_id,source,selector) VALUES (?,'business',?)", [group, String(fixture.outsiderFirm)]);
    // No membership fixture here: the production scheduler must publish the real proprietor.
    await expect.poll(allowed).toBe(true);
    await visible(true);

    // Retargeting invalidates the previous publication even when the provider fails.
    await connection.execute("UPDATE explorer_group_sync SET selector='invalid-firm-id' WHERE group_id=? AND source='business'", [group]);
    expect(await allowed()).toBe(false);
    await visible(false);
    const [retained] = await connection.execute<RowDataPacket[]>("SELECT sync_selector FROM explorer_group_member WHERE group_id=? AND source='business'", [group]);
    expect(retained.map(row => row.sync_selector)).toEqual([String(fixture.outsiderFirm)]);

    // Model an elapsed lease without a one-hour wall-clock wait. The invalid provider
    // cannot refresh it; this checks the real browser read-side expiry boundary.
    await connection.execute("UPDATE explorer_group_member SET sync_selector='invalid-firm-id', synced_at=NOW() WHERE group_id=? AND source='business'", [group]);
    expect(await allowed()).toBe(true);
    await connection.execute("UPDATE explorer_group_member SET synced_at=NOW()-INTERVAL 61 MINUTE WHERE group_id=? AND source='business'", [group]);
    expect(await allowed()).toBe(false);
    await visible(false);

    // Manual access is independent of an expired derived row, and revokes immediately.
    await connection.execute("INSERT INTO explorer_group_member(group_id,player_uuid_bin,source) VALUES (?,?,'manual')", [group, uuid]);
    expect(await allowed()).toBe(true);
    await visible(true);
    await connection.execute("DELETE FROM explorer_group_member WHERE group_id=? AND source='manual'", [group]);
    expect(await allowed()).toBe(false);

    await connection.execute("UPDATE explorer_group_sync SET selector=? WHERE group_id=? AND source='business'", [String(fixture.outsiderFirm), group]);
    await expect.poll(allowed).toBe(true);
    await visible(true);
    await connection.execute("DELETE FROM explorer_group_sync WHERE group_id=? AND source='business'", [group]);
    expect(await allowed()).toBe(false);
    await visible(false);
    expect(errors).toEqual([]);
  } finally {
    if (group !== undefined) await connection.execute('DELETE FROM explorer_group WHERE group_id=?', [group]);
    await connection.execute('DELETE FROM explorer_role_definition WHERE role_key=?', [role]);
    await connection.end();
  }
});


test('real business trade and fine appear in firm, market and public taxation pages', async ({ page, context }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => {
    if (response.url().startsWith(process.env.E2E_BASE_URL!) && response.status() >= 500)
      errors.push(`HTTP ${response.status()} ${new URL(response.url()).pathname}`);
  });
  await page.goto(`/firms/${fixture.outsiderFirmName}`);
  await expect(page.getByRole('heading', { name: fixture.outsiderFirmName, exact: true })).toBeVisible();
  await expect(page.getByText('Firm internals are private', { exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Accounts', exact: true })).toHaveCount(0);
  await signIn(context, fixture.outsiderUuid, fixture.outsiderName);
  await page.reload();
  const accounts = page.getByRole('region', { name: 'Accounts', exact: true });
  await expect(accounts).toContainText('140.45');
  await expect(accounts.locator(`a[href="/accounts/${fixture.firmAccount}"]`)).toBeVisible();
  const activity = page.getByRole('region', { name: 'ChestShop activity', exact: true });
  await expect(activity.locator('tbody tr')).toHaveCount(1);
  await expect(activity.locator('tbody tr td').nth(2)).toHaveText('1');
  await expect(activity.locator('tbody tr td').nth(3)).toHaveText('2');
  await expect(activity.locator('tbody tr td').nth(4)).toContainText('17.00');
  await context.clearCookies();
  await page.reload();
  await expect(page.getByText('Firm internals are private', { exact: true })).toBeVisible();

  await page.goto('/market');
  const item = page.getByRole('table', { name: 'Most traded items' }).locator('tbody tr');
  await expect(item).toHaveCount(1);
  await expect(item.locator('.item-price')).toContainText('8.50');
  await expect(item.locator('.item-trades')).toHaveText('1');
  await expect(item.locator('.item-volume')).toContainText('17.00');
  await item.getByRole('link').click();
  await expect(page).toHaveURL(new RegExp(`/chestshop/items/${encodeURIComponent(fixture.itemKey)}`));

  await page.goto('/economy/taxation');
  await expect(page.getByRole('heading', { name: 'Taxation', exact: true })).toBeVisible();
  const kpi = (label: string) => page.locator('.kpi').filter({ has: page.getByText(label, { exact: true }) }).locator('.kpi-value');
  await expect(kpi('Collected')).toContainText('11.25');
  await expect(kpi('Fine refunds')).toContainText('0.00');
  await expect(kpi('Net revenue')).toContainText('11.25');
  await expect(kpi('Receiving accounts')).toHaveText('1');
  await expect(page.getByRole('region', { name: 'Where it lands' })).toContainText('GovernmentFines');
  await expect(page.getByText('browser-private-reason', { exact: false })).toHaveCount(0);
  expect(errors).toEqual([]);
});
