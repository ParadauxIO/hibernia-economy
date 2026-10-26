import { test, expect } from '@playwright/test';
import { visit } from './helpers';

// A fresh deploy: the schema exists but the rollup worker has not written a
// row yet. Runs in its own project before rollups.setup.ts fills the rollups,
// so every page here sees empty coverage. The economy pages must still render
// (never a server error, never a live ledger scan) and say they are preparing.

for (const path of ['/', '/economy/wealth', '/money-flow', '/economy/taxation']) {
  test(`${path} renders the preparing state with no rollup coverage`, async ({ page }) => {
    test.skip(!process.env.E2E_AUTH_SHIM, 'needs the managed e2e database, seeded without rollups');
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    const response = await page.goto(path);
    expect(response?.status()).toBe(200);
    await visit(page, path);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    // Section errors render ErrorState (role=alert) inside main; Next's route announcer is outside it.
    await expect(page.getByRole('main').getByRole('alert')).toHaveCount(0);
    if (path !== '/economy/wealth') {
      await expect(page.getByRole('status').filter({ hasText: 'Preparing history' }).first()).toBeVisible();
    } else {
      // Current balances never depend on the rollups; only the history waits.
      await expect(page.getByRole('group', { name: 'Wealth concentration' })).toContainText('Gini coefficient');
      await expect(page.getByRole('status').filter({ hasText: 'Preparing history' })).toBeVisible();
    }
    expect(errors.filter((e) => /#\d+|digest/i.test(e))).toEqual([]);
  });
}
