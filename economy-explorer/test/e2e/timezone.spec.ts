import { test, expect } from '@playwright/test';
import { visit } from './helpers';

for (const timezoneId of ['America/Los_Angeles', 'Pacific/Auckland']) {
  test.describe(timezoneId, () => {
    test.use({ timezoneId });

    test('localizes server-rendered account timestamps without hydration errors', async ({ page }) => {
      const errors: string[] = [];
      page.on('pageerror', error => errors.push(error.message));
      page.on('console', message => {
        if (message.type() === 'error') errors.push(message.text());
      });
      await visit(page, '/accounts?type=PERSONAL');
      const timestamp = page.locator('time[datetime]').first();
      await expect(timestamp).toHaveAttribute('title', new RegExp(timezoneId));
      const instant = await timestamp.getAttribute('datetime');
      const expected = new Date(instant!).toLocaleString('en-US', {
        month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: timezoneId,
      });
      await expect(timestamp).toContainText(expected);
      expect(errors.filter(error => /hydration|#418|didn't match/i.test(error))).toEqual([]);
    });
  });
}
