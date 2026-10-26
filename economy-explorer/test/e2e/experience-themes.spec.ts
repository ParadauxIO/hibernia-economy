import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { visit } from './helpers';

// Shared tokens must work for both servers and both surface modes. Exercise
// representative full screens because an isolated swatch misses real contrast.
for (const theme of ['blue', 'red']) {
  for (const mode of ['dark', 'light']) {
    for (const path of ['/', '/economy/wealth', '/money-flow', '/economy/taxation', '/market', '/chestshop', '/chestshop/shops?item=DIAMOND', '/chestshop/items/DIAMOND', '/accounts/1', '/accounts/3', '/players/Alice', '/firms/Acme%20Corp', '/design-system', '/design-system/mock-account', '/design-system/mock-me']) {
      test(`${theme} ${mode}: ${path} remains readable and fits the viewport`, async ({ page, context, baseURL }) => {
        await context.addCookies([
          { name: 'theme', value: theme, url: baseURL! },
          { name: 'mode', value: mode, url: baseURL! },
        ]);
        await visit(page, path);
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        await expect(page.locator('html')).toHaveAttribute('data-mode', mode);
        expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
        const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']).analyze();
        const blocking = audit.violations.filter(v => v.impact === 'serious' || v.impact === 'critical').map(v => `${v.id}: ${v.nodes.map(n => n.target.join(' ')).join(', ')}`);
        expect(blocking).toEqual([]);
      });
    }
  }
}
