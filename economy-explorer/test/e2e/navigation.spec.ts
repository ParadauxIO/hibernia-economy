import { test, expect, type BrowserContext, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { visit } from './helpers';

// The chrome (PAR-536): top bar, section nav, account menu, command palette and
// phone menu, against the real app and seeded DB. Role specs need the auth shim.
const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100';
const shim = !!process.env.E2E_AUTH_SHIM;

async function loginAs(context: BrowserContext, role: string) {
  await context.addCookies([{ name: 'e2e_viewer', value: role, url: baseURL }]);
}

async function axeBlocking(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']).analyze();
  return results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
}

test.describe('desktop chrome', () => {
  test.skip(({ isMobile }) => isMobile, 'desktop layout');

  test('renders the viewer on the server: no /api/viewer request per document', async ({ page }) => {
    const viewerCalls: string[] = [];
    page.on('request', (r) => { if (r.url().includes('/api/viewer')) viewerCalls.push(r.url()); });
    await visit(page, '/');
    await page.waitForLoadState('networkidle');
    expect(viewerCalls).toEqual([]);
    await expect(page.getByRole('button', { name: /Visitor/ })).toBeVisible();
  });

  test('marks the section and page, and the section nav follows the path', async ({ page }) => {
    await visit(page, '/economy/wealth');
    const primary = page.getByRole('navigation', { name: 'Primary' });
    await expect(primary.getByRole('link', { name: 'Economy' })).toHaveAttribute('aria-current', 'true');
    const economy = page.getByRole('navigation', { name: 'Economy' });
    await expect(economy.getByRole('link', { name: 'Wealth' })).toHaveAttribute('aria-current', 'page');
    await economy.getByRole('link', { name: 'Taxation' }).click();
    await expect(page).toHaveURL(/\/economy\/taxation$/);
    await expect(economy.getByRole('link', { name: 'Taxation' })).toHaveAttribute('aria-current', 'page');

    await primary.getByRole('link', { name: 'Market' }).click();
    await expect(page.getByRole('navigation', { name: 'Market' }).getByRole('link', { name: 'Market firms' })).toHaveAttribute('href', '/chestshop/firms');
  });

  test('pages no longer render their own section tabs', async ({ page }) => {
    await visit(page, '/market');
    await expect(page.getByRole('main').locator('.section-tabs')).toHaveCount(0);
  });

  test('the palette finds an account by keyboard and opens it', async ({ page }) => {
    await visit(page, '/');
    await page.keyboard.press('Control+k');
    const input = page.getByRole('combobox', { name: /Search players/ });
    await expect(input).toBeFocused();
    await input.fill('Acme');
    const first = page.getByRole('group', { name: 'Results' }).getByRole('option').first();
    await expect(first).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: /match/ })).toHaveCount(1);
    await input.press('Enter');
    await expect(page).toHaveURL(/\/(accounts|firms)\//);
    // The pick is remembered.
    await page.keyboard.press('Control+k');
    await expect(page.getByRole('group', { name: 'Recent' }).getByRole('option')).toHaveCount(1);
  });

  test('the open palette passes axe in both themes', async ({ page, context }) => {
    for (const theme of ['blue', 'red']) {
      await context.addCookies([{ name: 'theme', value: theme, url: baseURL }, { name: 'mode', value: 'light', url: baseURL }]);
      await visit(page, '/');
      await page.keyboard.press('Control+k');
      await page.getByRole('combobox').fill('al');
      await expect(page.getByRole('group', { name: 'Results' })).toBeVisible();
      expect(await axeBlocking(page)).toEqual([]);
    }
  });
});

test.describe('account menu', () => {
  test.skip(!shim, 'needs the auth shim');
  test.skip(({ isMobile }) => isMobile, 'desktop layout');

  test('shows the personal balance only, and the player pages', async ({ page, context }) => {
    await loginAs(context, 'player'); // Alice: personal #1 ($5,000.00) and business #3 ($25,000.00)
    await visit(page, '/');
    const chip = page.getByRole('button', { name: /Alice personal balance/ });
    await expect(chip).toContainText('$5,000.00');
    await expect(chip).not.toContainText('30,000');
    await chip.click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem').first()).toBeFocused();
    await expect(menu.getByRole('menuitem', { name: 'Statements' })).toHaveAttribute('href', '/me/statements');
    await expect(menu.getByRole('menuitem', { name: 'Admin' })).toHaveCount(0);
    expect(await axeBlocking(page)).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
    await expect(chip).toBeFocused();
  });

  test('admins get Admin in the menu, not in the primary nav', async ({ page, context }) => {
    await loginAs(context, 'admin');
    await visit(page, '/');
    await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Admin' })).toHaveCount(0);
    await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Investigate' })).toBeVisible();
    await page.getByRole('button', { name: /Alice/ }).click();
    await expect(page.getByRole('menu').getByRole('menuitem', { name: 'Admin' })).toHaveAttribute('href', '/admin');
  });

  test('log out clears the session and goes to the identity provider’s end-session endpoint', async ({ page, context }) => {
    await loginAs(context, 'player');
    await visit(page, '/');
    await page.getByRole('button', { name: /Alice/ }).click();
    const [request] = await Promise.all([
      page.waitForRequest((r) => r.url().includes('/protocol/openid-connect/logout'), { timeout: 15_000 }),
      page.getByRole('menuitem', { name: 'Log out' }).click(),
    ]);
    const url = new URL(request.url());
    expect(url.searchParams.get('client_id')).toBeTruthy();
    expect(url.searchParams.get('post_logout_redirect_uri')).toBe(new URL(baseURL).origin);
  });
});

test.describe('phone chrome', () => {
  test.skip(({ isMobile }) => !isMobile, 'phone layout');

  test('the menu lists every section and closes with Escape', async ({ page }) => {
    await visit(page, '/accounts');
    await expect(page.getByRole('navigation', { name: 'Primary' })).toBeHidden();
    const button = page.getByRole('button', { name: 'Menu' });
    await button.click();
    const sheet = page.getByRole('dialog', { name: 'Menu' });
    await expect(sheet.getByRole('link', { name: 'Accounts' })).toHaveAttribute('aria-current', 'page');
    await expect(sheet.getByRole('link', { name: 'Market firms' })).toBeVisible();
    expect(await axeBlocking(page)).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();
    await expect(button).toBeFocused();
  });

  test('the section nav stays reachable under the bar', async ({ page }) => {
    await visit(page, '/market/activity');
    await expect(page.getByRole('navigation', { name: 'Market' }).getByRole('link', { name: 'Activity' })).toHaveAttribute('aria-current', 'page');
  });
});
