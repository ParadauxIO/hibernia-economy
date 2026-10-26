import { test, expect } from '@playwright/test';
import { visit } from './helpers';

// Regression for the shared Popover base (PAR-536): it used to focus its first
// control while the panel was still `visibility: hidden`, which real browsers
// ignore (jsdom doesn't). Focus stayed on the trigger, so Escape inside the
// panel had nothing to act on. These run in a real browser on purpose.

test('a dialog popover takes focus when it opens, and Escape closes it back to the trigger', async ({ page, isMobile }) => {
  test.skip(isMobile, 'opens as a bottom sheet on phones; same focus path');
  await visit(page, '/design-system');
  const trigger = page.getByRole('button', { name: /^Type: / });
  await trigger.click();
  const panel = page.getByRole('dialog', { name: 'Filter by account type' });
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('checkbox').first()).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(panel).toBeHidden();
  await expect(trigger).toBeFocused();
});

test('a filter popover focuses its first control and closes on Escape', async ({ page }) => {
  await visit(page, '/accounts');
  const trigger = page.getByRole('button', { name: 'Filter', exact: true });
  await trigger.click();
  const panel = page.getByRole('dialog', { name: 'Add a filter' });
  await expect(panel).toBeVisible();
  await expect(panel.locator(':focus')).toHaveCount(1);
  await page.keyboard.press('Escape');
  await expect(panel).toBeHidden();
  await expect(trigger).toBeFocused();
});

test('a menu popover focuses its first item and closes on Escape', async ({ page, context, baseURL, isMobile }) => {
  test.skip(!process.env.E2E_AUTH_SHIM, 'needs the auth shim');
  test.skip(isMobile, 'desktop layout');
  await context.addCookies([{ name: 'e2e_viewer', value: 'player', url: baseURL! }]);
  await visit(page, '/');
  const trigger = page.getByRole('button', { name: /Alice/ });
  await trigger.click();
  const menu = page.getByRole('menu');
  await expect(menu.getByRole('menuitem').first()).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(menu.getByRole('menuitem').nth(1)).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(menu).toBeHidden();
  await expect(trigger).toBeFocused();
});
