import { encode } from 'next-auth/jwt';
import { test, expect } from '@playwright/test';
import { visit, GATE } from './helpers';

test.beforeEach(async ({ context }) => { await context.clearCookies(); });

test('visitors can browse the overview, directories and docs without balances', async ({ page }) => {
  for (const path of ['/', '/players', '/firms', '/accounts', '/docs']) {
    await visit(page, path);
    await expect(page.locator(GATE)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Log in', exact: true })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: /^Balance\b/ })).toHaveCount(0);
  }
  await visit(page, '/accounts?sort=-balance&f=balance:gt:10000');
  await expect(page.getByRole('columnheader', { name: /^Balance\b/ })).toHaveCount(0);
  await page.locator('table tbody a').first().click();
  await expect(page.getByRole('heading', { name: 'There’s more to explore' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Log in or create an account' })).toBeVisible();
  await page.getByRole('link', { name: 'Read the docs' }).click();
  await expect(page).toHaveURL(/\/docs$/);
});

test('protected pages offer login while API and export data remain blocked', async ({ page, request }) => {
  for (const path of ['/players/Alice', '/firms/Acme', '/transactions', '/me', '/market', '/gov', '/admin']) {
    await visit(page, path);
    await expect(page.getByRole('heading', { name: 'There’s more to explore' })).toBeVisible();
    await expect(page.locator('table')).toHaveCount(0);
  }
  expect((await request.get('/api/export/accounts')).status()).toBe(401);
  expect((await request.get('/api/preview/account/1')).status()).toBe(401);
});


test('logging out removes directory and personal balances', async ({ page, context, baseURL }) => {
  test.skip(!process.env.E2E_AUTH_SECRET, 'requires a disposable signed test session');
  const name = baseURL!.startsWith('https:') ? '__Secure-authjs.session-token' : 'authjs.session-token';
  const token = await encode({ secret: process.env.E2E_AUTH_SECRET!, salt: name, token: {
    sub: 'e2e-access-alice', minecraftUuid: '00000000-0000-0000-0000-00000000a1ce', minecraftName: 'Alice',
  } });
  await context.addCookies([{ name, value: token, url: baseURL!, httpOnly: true, sameSite: 'Lax' }]);
  // The app clears its real session; only the external IdP's logout return is stubbed.
  await page.route('https://identity.example.test/**/logout?**', route =>
    route.fulfill({ status: 302, headers: { location: baseURL! } }));
  await visit(page, '/accounts');
  await expect(page.getByRole('columnheader', { name: /^Balance\b/ })).toBeVisible();
  await expect(page.locator('.shell-account-balance')).toBeVisible();
  await page.getByRole('button', { name: /personal balance/ }).click();
  await page.getByRole('menuitem', { name: 'Log out', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Log in', exact: true })).toBeVisible();
  await visit(page, '/accounts');
  await expect(page.getByRole('columnheader', { name: /^Balance\b/ })).toHaveCount(0);
  await expect(page.locator('.shell-account-balance')).toHaveCount(0);
});
