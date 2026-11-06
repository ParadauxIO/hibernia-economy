import { test, expect } from '@playwright/test';
import { visit } from './helpers';

// PAR-548: exercise the real rendered tables/CSS with deliberately wide cell
// contents. These DOM-only fixtures don't alter the shared ledger or its balances.
for (const width of [1280, 1100, 768, 390]) {
  for (const [path, id] of [['/accounts/1', 'account-txns'], ['/me', 'me-recent']] as const) {
    test(`${id}: complete amounts at ${width}px`, async ({ page, context, baseURL }, testInfo) => {
      test.skip(!process.env.E2E_AUTH_SHIM, 'requires the disposable test auth shim');
      await context.addCookies([{ name: 'e2e_viewer', value: 'player', url: baseURL! }]);
      await page.setViewportSize({ width, height: 900 });
      await visit(page, path);
      const table = page.locator(`#${id}`);
      await expect(table).toBeVisible();
      // Today's seeded transactions switch to relative dates after hydration.
      await expect(table.locator('time').first()).toContainText(/ago|just now/);
      await page.evaluate(() => document.fonts.ready);
      await table.evaluate(el => {
        for (const name of el.querySelectorAll('.entity-name')) {
          name.textContent = 'International Commercial Holdings Corporation Operating Account';
        }
        for (const message of el.querySelectorAll('.txn-message')) {
          message.textContent = 'Annual registration payment for International Commercial Holdings Corporation';
        }
        for (const [i, amount] of [...el.querySelectorAll('td[data-col="amount"] .num')].entries()) {
          amount.textContent = i % 2 ? '−$123,456,789.12' : '+$123,456,789.12';
        }
      });
      const region = table.locator('..');
      const amount = table.locator('td[data-col="amount"] .num').first();
      await expect.poll(() => region.evaluate(el => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1);
      await expect(amount).toHaveText('+$123,456,789.12');
      await expect(table.locator('td[data-col="amount"] .num').nth(1)).toHaveText('−$123,456,789.12');
      const amounts = await table.locator('td[data-col="amount"] .num').evaluateAll(elements => elements.map(el => {
        const range = document.createRange();
        range.selectNodeContents(el);
        const text = range.getBoundingClientRect();
        const wrap = el.closest('.dt-wrap')!;
        const box = wrap.getBoundingClientRect();
        return { left: text.left, right: text.right, start: box.left + wrap.clientLeft, end: box.left + wrap.clientLeft + wrap.clientWidth };
      }));
      for (const bounds of amounts) {
        expect(bounds.left).toBeGreaterThanOrEqual(bounds.start);
        expect(bounds.right).toBeLessThanOrEqual(bounds.end);
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);

      if (await region.evaluate(el => el.clientWidth <= 760)) {
        await expect(table.locator('summary').first()).toBeHidden();
        if (id === 'account-txns') {
          const balance = table.locator('td[data-col="balance"]').first();
          await expect(balance).toBeVisible();
          await expect(balance).toContainText('Balance $5,000.00');
          const balanceBox = await balance.boundingBox();
          const amountBox = await amount.boundingBox();
          expect(balanceBox!.y).toBeGreaterThan(amountBox!.y);
          expect(Math.abs(balanceBox!.x + balanceBox!.width - amountBox!.x - amountBox!.width)).toBeLessThan(1);
        }
      }
      await testInfo.attach('transaction-layout', { body: await region.screenshot(), contentType: 'image/png' });
    });
  }
}
