import { test, expect, type BrowserContext } from '@playwright/test';
import { encode } from 'next-auth/jwt';
import { randomUUID } from 'node:crypto';
import mysql, { type RowDataPacket } from 'mysql2/promise';
import { testDbOptions } from '../integration/db';

const secret = process.env.E2E_AUTH_SECRET;
const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100';
test.skip(!secret, 'Requires a local signed-session test app');

async function signIn(context: BrowserContext, uuid: string) {
  const name = 'authjs.session-token';
  const value = await encode({ secret: secret!, salt: name, token: { sub: `flags-${uuid}`, minecraftUuid: uuid, minecraftName: 'FlagOperator' } });
  await context.clearCookies();
  await context.addCookies([{ name, value, url: baseURL, httpOnly: true, sameSite: 'Lax' }]);
}

test('operator toggles persist, stale saves fail, and read-only access cannot toggle', async ({ page, context, browser }) => {
  if (!/test/i.test(process.env.DB_NAME ?? '')) throw new Error('Requires disposable test DB');
  const conn = await mysql.createConnection(testDbOptions());
  const admin = randomUUID(), reader = randomUUID();
  const key = `test.flag_${randomUUID().replaceAll('-', '')}`;
  const role = `flags_${randomUUID().slice(0, 8)}`;
  const bin = (uuid: string) => Buffer.from(uuid.replaceAll('-', ''), 'hex');
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const second = await browser.newContext();
  try {
    await conn.execute('INSERT INTO feature_flags (name, description) VALUES (?, ?)', [key, 'Browser test feature']);
    await conn.execute('INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, ?)', [bin(admin), 'admin']);
    await conn.execute('INSERT INTO explorer_role_definition (role_key, name) VALUES (?, ?)', [role, role]);
    await conn.execute('INSERT INTO explorer_role_permission (role_key, permission) VALUES (?, ?)', [role, 'feature_flags.read']);
    await conn.execute('INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, ?)', [bin(reader), role]);
    await signIn(context, admin);
    await page.goto('/admin/feature-flags');
    await expect(page.getByRole('heading', { name: 'Feature flags', exact: true })).toBeVisible();
    const stale = await second.newPage();
    await signIn(second, admin);
    await stale.goto(`${baseURL}/admin/feature-flags`);
    await expect(stale.getByRole('button', { name: `Enable ${key}`, exact: true })).toBeVisible();
    await page.getByRole('button', { name: `Enable ${key}`, exact: true }).click();
    await expect(page.getByRole('button', { name: `Disable ${key}`, exact: true })).toBeVisible();
    await stale.getByRole('button', { name: `Enable ${key}`, exact: true }).click();
    await expect(stale.getByRole('alert').filter({ hasText: 'changed since you opened it' })).toContainText('changed since you opened it');
    await page.reload();
    await expect(page.getByRole('button', { name: `Disable ${key}`, exact: true })).toBeVisible();
    const [rows] = await conn.execute<RowDataPacket[]>('SELECT enabled, revision FROM feature_flags WHERE name = ?', [key]);
    expect(rows[0]).toMatchObject({ enabled: 1, revision: 2 });
    const [audits] = await conn.execute<RowDataPacket[]>("SELECT after_json FROM explorer_audit WHERE action = 'feature_flag.update' AND target_id = ?", [key]);
    expect(audits).toHaveLength(1);
    expect(JSON.parse(audits[0]!.after_json)).toMatchObject({ enabled: 1 });
    await page.getByRole('button', { name: `Disable ${key}`, exact: true }).click();
    await expect(page.getByRole('button', { name: `Enable ${key}`, exact: true })).toBeVisible();
    await signIn(context, reader);
    await page.reload();
    await expect(page.getByRole('heading', { name: key, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: new RegExp(`(Enable|Disable) ${key}`) })).toHaveCount(0);
    expect(errors).toEqual([]);
  } finally {
    await second.close();
    await conn.execute('DELETE FROM feature_flags WHERE name = ?', [key]);
    await conn.execute('DELETE FROM explorer_role_definition WHERE role_key = ?', [role]);
    for (const uuid of [admin, reader]) {
      await conn.execute('DELETE FROM explorer_role WHERE player_uuid_bin = ?', [bin(uuid)]);
      await conn.execute('DELETE FROM explorer_identity WHERE player_uuid_bin = ?', [bin(uuid)]);
    }
    await conn.end();
  }
});
