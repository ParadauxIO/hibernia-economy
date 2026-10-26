import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import mysql, { type RowDataPacket } from 'mysql2/promise';

// Credentials are private runtime input, never committed or written to a trace.
const enabled = process.env.IT_KEYCLOAK_PROFILE === 'true';
test.skip(!enabled, 'Requires the explicitly selected Minecraft realm acceptance profile.');

test('real Keycloak sessions follow an in-game proprietorship transfer', async ({ browser }) => {
  test.setTimeout(180_000);
  const fixture = JSON.parse(readFileSync(process.env.IT_EXPLORER_FIXTURE!, 'utf8'));
  const identities = JSON.parse(readFileSync(process.env.IT_KEYCLOAK_CREDENTIALS!, 'utf8'));
  const oldContext = await browser.newContext();
  const newContext = await browser.newContext();
  const db = await mysql.createConnection({ host: process.env.DB_HOST!, port: Number(process.env.DB_PORT),
    database: process.env.DB_NAME!, user: process.env.DB_USERNAME!, password: process.env.DB_PASSWORD! });
  const login = async (page: Page, identity: { username: string; password: string; id: string }, uuid: string) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Log in', exact: true }).first().click();
    await expect(page).toHaveURL(/^https:\/\/auth\.paradaux\.io\/realms\/minecraft\//);
    await page.locator('#username').fill(identity.username);
    await page.locator('#password').fill(identity.password);
    await page.locator('#kc-login').click();
    await page.waitForURL(url => url.origin === process.env.E2E_BASE_URL);
    const session = await (await page.request.get('/api/auth/session')).json();
    expect(session.keycloakSub).toBe(identity.id);
    expect(session.minecraftUuid).toBe(uuid);
    expect(session).not.toHaveProperty('access_token');
    expect(session).not.toHaveProperty('refresh_token');
    const viewer = await (await page.request.get('/api/viewer')).json();
    expect(viewer.loggedIn).toBe(true);
    expect(viewer.isAdmin).toBe(false);
    const [rows] = await db.execute<RowDataPacket[]>(
      'SELECT BIN_TO_UUID(player_uuid_bin) AS uuid, linked_by FROM explorer_identity WHERE keycloak_sub = ?', [identity.id]);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.uuid.toLowerCase()).toBe(uuid);
    expect(rows[0]!.linked_by).toBe('token-claim');
  };
  const visibility = async (page: Page, permitted: boolean) => {
    await page.goto(`/accounts/${fixture.firmAccount}`);
    await expect(page.locator('.kpi-value').filter({ hasText: '140.45' })).toBeVisible();
    if (permitted) {
      await expect(page.getByRole('region', { name: 'Recent activity' })).toBeVisible();
      await expect(page.getByText('Transaction history is private', { exact: true })).toHaveCount(0);
    } else {
      await expect(page.getByText('Transaction history is private', { exact: true })).toBeVisible();
      await expect(page.getByRole('region', { name: 'Recent activity' })).toHaveCount(0);
    }
  };
  try {
    const oldPage = await oldContext.newPage();
    const newPage = await newContext.newPage();
    await login(oldPage, identities.old, fixture.outsiderUuid);
    await login(newPage, identities.next, fixture.recipientUuid);
    await visibility(oldPage, true);
    await visibility(newPage, false);
    // Existing signed sessions remain open throughout the real command flow.
    await promisify(execFile)('sh', ['./gradlew', '-p', 'integration-tests', '--console=plain',
      ':tests:explorerTransfer', `-Dmockclient.port=${process.env.DEVSERVER_PORT}`],
    { cwd: process.env.IT_REPO_ROOT!, timeout: 90_000, maxBuffer: 4 * 1024 * 1024 });
    await visibility(oldPage, false);
    await visibility(newPage, true);
    expect((await (await oldPage.request.get('/api/auth/session')).json()).keycloakSub).toBe(identities.old.id);
    expect((await (await newPage.request.get('/api/auth/session')).json()).keycloakSub).toBe(identities.next.id);
  } finally {
    await db.end();
    await oldContext.close();
    await newContext.close();
  }
});
