import { test, expect } from '@playwright/test';
import { encode } from 'next-auth/jwt';
import { randomUUID } from 'node:crypto';
import mysql from 'mysql2/promise';
import { testDbOptions } from '../integration/db';

test('provenance gates live read access and filters active and expired group grants', async ({ page, context }, testInfo) => {
  if (!/test/i.test(process.env.DB_NAME ?? '')) throw new Error('Disposable test database required');
  const conn = await mysql.createConnection(testDbOptions());
  const user = randomUUID(), key = `prov_${randomUUID().slice(0, 8)}`;
  const bin = Buffer.from(user.replaceAll('-', ''), 'hex');
  let groupId: number | undefined;
  try {
    await context.clearCookies();
    expect((await context.request.get('/admin/role-grants', { maxRedirects: 0 })).status()).toBe(307);
    await conn.query('INSERT INTO explorer_role_definition (role_key,name) VALUES (?,?)', [key,key]);
    await conn.query('INSERT INTO explorer_role_permission VALUES (?,?)', [key,'roles.read']);
    await conn.query('INSERT INTO explorer_role (player_uuid_bin,role) VALUES (?,?)', [bin,key]);
    const name = 'authjs.session-token';
    const token = await encode({ secret: process.env.E2E_AUTH_SECRET!, salt: name, token: { sub: `provenance-${user}`, minecraftUuid: user } });
    await context.addCookies([{ name, value: token, url: process.env.E2E_BASE_URL!, httpOnly: true, sameSite: 'Lax' }]);
    await page.goto('/admin/role-grants');
    await expect(page.getByText('Role and group read access required')).toBeVisible();
    await conn.query('INSERT INTO explorer_role_permission VALUES (?,?)', [key,'groups.read']);
    const [g] = await conn.query<mysql.ResultSetHeader>('INSERT INTO explorer_group (name,luckperms_node) VALUES (?,?)', [key,'parent']);
    groupId = g.insertId;
    await conn.query('INSERT INTO explorer_group_capability VALUES (?,?)', [groupId,key]);
    await conn.query('INSERT INTO explorer_group_member (group_id,player_uuid_bin,source,synced_at) VALUES (?,?,?,CURRENT_TIMESTAMP)', [groupId,bin,'luckperms']);
    await page.goto('/admin/roles');
    await page.getByRole('link', { name: 'Browse user role grants and group provenance' }).click();
    await page.getByLabel('Search user, UUID, group or source node').fill(user);
    await page.getByLabel('Source', { exact: true }).selectOption('luckperms');
    await page.getByRole('button', { name: 'Filter', exact: true }).click();
    const table = page.getByRole('table');
    await expect(table.getByRole('row')).toHaveCount(2);
    await expect(table.getByRole('cell', { name: 'parent', exact: true })).toBeVisible();
    await expect(table.getByRole('cell', { name: 'Active', exact: true })).toBeVisible();
    await page.screenshot({ path: `../.context/luckperms/provenance-${testInfo.project.name}.png`, fullPage: true });
    await page.getByLabel('Status', { exact: true }).selectOption('inactive');
    await page.getByRole('button', { name: 'Filter', exact: true }).click();
    await expect(page.getByText('No role grants match these filters.')).toBeVisible();
    await conn.query('UPDATE explorer_group_member SET synced_at=CURRENT_TIMESTAMP-INTERVAL 2 HOUR WHERE group_id=?', [groupId]);
    await page.reload();
    await expect(table.getByRole('cell', { name: /Inactive/ })).toBeVisible();
    await page.getByLabel('Search user, UUID, group or source node').fill('no-such-provenance-user');
    await page.getByRole('button', { name: 'Filter', exact: true }).click();
    await expect(page.getByText('No role grants match these filters.')).toBeVisible();
    await conn.query('DELETE FROM explorer_role_permission WHERE role_key=? AND permission=?', [key,'roles.read']);
    await page.reload();
    await expect(page.getByText('Role and group read access required')).toBeVisible();
  } finally {
    if (groupId) await conn.query('DELETE FROM explorer_group WHERE group_id=?', [groupId]);
    await conn.query('DELETE FROM explorer_role_definition WHERE role_key=?', [key]);
    await conn.end();
  }
});
