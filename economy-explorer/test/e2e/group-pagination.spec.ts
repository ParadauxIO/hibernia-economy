import { test, expect } from '@playwright/test';
import { encode } from 'next-auth/jwt';
import { randomUUID } from 'node:crypto';
import mysql from 'mysql2/promise';
import { testDbOptions } from '../integration/db';
import { visit, submitReload } from './helpers';

test('large synced groups page, search and retain manual member controls (PAR-545)', async ({ page, context }) => {
  if (!/test/i.test(process.env.DB_NAME ?? '')) throw new Error('Disposable test database required');
  const conn = await mysql.createConnection(testDbOptions());
  const prefix = randomUUID().replaceAll('-', '').slice(0, 16);
  const admin = randomUUID();
  const adminBin = Buffer.from(admin.replaceAll('-', ''), 'hex');
  let groupId: number | undefined;
  try {
    await conn.query('INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, ?)', [adminBin, 'admin']);
    const [created] = await conn.query<mysql.ResultSetHeader>(
      'INSERT INTO explorer_group (name, luckperms_node) VALUES (?, ?)', [`Paging ${prefix}`, 'default']);
    groupId = created.insertId;
    // Unknown players are common in default-group syncs; 140k rows reproduce the reported scale.
    await conn.query(`INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at)
      SELECT ?, UNHEX(CONCAT(?, LPAD(HEX(seq), 16, '0'))), 'luckperms', NOW() FROM seq_1_to_140000`, [groupId, prefix]);
    const name = 'authjs.session-token';
    const token = await encode({ secret: process.env.E2E_AUTH_SECRET!, salt: name, token: { sub: `paging-${admin}`, minecraftUuid: admin } });
    await context.addCookies([{ name, value: token, url: process.env.E2E_BASE_URL!, httpOnly: true, sameSite: 'Lax' }]);
    await visit(page, `/admin/groups/${groupId}`);
    const table = page.getByRole('table', { name: 'Group members' });
    await expect(table.locator('tbody tr')).toHaveCount(50);
    const first = await table.locator('tbody tr').first().innerText();
    const pager = page.getByRole('navigation', { name: 'Group member pages' });
    await expect(pager).toContainText('Page 1 of 2,800');
    await pager.getByRole('link', { name: 'Next page' }).click();
    await expect(pager).toContainText('Page 2 of 2,800');
    await expect(table.locator('tbody tr')).toHaveCount(50);
    await expect(table.locator('tbody tr').first()).not.toHaveText(first);
    await pager.getByRole('link', { name: 'Last page' }).click();
    await expect(pager).toContainText('Page 2,800 of 2,800');
    const target = await table.locator('tbody tr td').first().innerText();
    const search = page.getByRole('searchbox', { name: 'Search members by name or UUID' });
    await search.fill(target);
    await search.press('Enter');
    await expect(table.locator('tbody tr')).toHaveCount(1);
    await expect(table).toContainText(target);
    await expect(pager).toHaveCount(0);
    await page.getByRole('textbox', { name: 'Add a manual member' }).fill('Alice');
    await submitReload(page, page.getByRole('button', { name: 'Add member', exact: true }));
    await visit(page, `/admin/groups/${groupId}?f=source:in:manual&page=9999`);
    await expect(table.locator('tbody tr')).toHaveCount(1);
    await expect(table).toContainText('Alice');
    page.once('dialog', dialog => dialog.accept());
    await submitReload(page, table.getByRole('button', { name: 'Remove', exact: true }));
    await expect(table).toContainText('No members.');
    const [rows] = await conn.query<mysql.RowDataPacket[]>(
      "SELECT COUNT(*) AS n FROM explorer_group_member WHERE group_id=? AND source='luckperms'", [groupId]);
    expect(Number(rows[0]!.n)).toBe(140000);
  } finally {
    if (groupId) await conn.query('DELETE FROM explorer_group WHERE group_id=?', [groupId]);
    await conn.query('DELETE FROM explorer_role WHERE player_uuid_bin=?', [adminBin]);
    await conn.query('DELETE FROM explorer_identity WHERE keycloak_sub=?', [`paging-${admin}`]);
    await conn.end();
  }
});
