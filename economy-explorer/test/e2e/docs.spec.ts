import { test, expect, type Page } from '@playwright/test';
import { visit } from './helpers';

function trackErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('requestfailed', request => {
    const failure = request.failure()?.errorText;
    if (failure && !failure.includes('ERR_ABORTED')) errors.push(`${request.url()}: ${failure}`);
  });
  return errors;
}

test('public docs expose recovery and TreasuryAPI guidance', async ({ page }) => {
  const errors = trackErrors(page);
  await visit(page, '/docs');
  await page.locator('article').getByRole('link', { name: 'TreasuryAPI', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'TreasuryAPI' })).toBeVisible();
  await page.locator('article').getByRole('link', { name: 'Password recovery', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Recover your website password' })).toBeVisible();
  await expect(page.locator('article')).toContainText('/treasuryapi ui resetpassword confirm');
  expect(errors).toEqual([]);
});

for (const role of ['anon', 'player', 'moderator', 'admin']) {
  test(`operator runbook access for ${role}`, async ({ page, context, baseURL }) => {
    test.skip(role !== 'anon' && !process.env.E2E_AUTH_SHIM, 'requires test auth shim');
    const errors = trackErrors(page);
    await context.clearCookies();
    if (role !== 'anon') await context.addCookies([{ name: 'e2e_viewer', value: role, url: baseURL! }]);
    await visit(page, '/docs/admin/operator-runbook');
    if (role !== 'admin') {
      await expect(page.locator('.privacy-gate')).toBeVisible();
      await expect(page.locator('article')).toHaveCount(0);
      await expect(page.locator('.docs-sidebar').getByRole('link', { name: 'Admin-operator runbook' })).toHaveCount(0);
    } else {
      await expect(page.getByRole('heading', { level: 1, name: 'Admin-operator runbook' })).toBeVisible();
      const sidebar = page.locator('.docs-sidebar');
      // Open the collapsed documentation menu on the phone viewport.
      const toggle = sidebar.getByRole('button', { name: 'Documentation menu' });
      if (await toggle.isVisible()) await toggle.click();
      await sidebar.getByRole('link', { name: 'Access and credentials', exact: true }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Access and credentials' })).toBeVisible();
      await expect(page.locator('article')).toContainText('one affected row');
      await page.reload();
      await expect(page.getByRole('heading', { level: 1, name: 'Access and credentials' })).toBeVisible();
    }
    expect(errors).toEqual([]);
  });
}

test('documentation stays public without share-caching the personalized explorer shell', async ({ context }) => {
  await context.clearCookies();
  const response = await context.request.get('/docs');
  expect(response.status()).toBe(200);
  expect(response.headers()['cache-control']).toContain('no-store');
  expect(response.headers()['cache-control']).not.toContain('public');
});
