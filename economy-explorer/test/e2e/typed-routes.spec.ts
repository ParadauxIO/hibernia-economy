import { test, expect } from '@playwright/test';
import { visit } from './helpers';

test('range navigation retains the current route and unrelated query parameters', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  // The fixture page uses the real RangePicker and Next router without a DB.
  await visit(page, '/design-system/mock-me?page=4&unit=commerce');
  await page.getByRole('button', { name: '30d', exact: true }).click();
  await expect(page).toHaveURL(/\/design-system\/mock-me\?unit=commerce&range=30d$/);
  await expect(page.getByRole('button', { name: '30d', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.reload();
  await expect(page.getByRole('button', { name: '30d', exact: true })).toHaveAttribute('aria-pressed', 'true');
  expect(errors).toEqual([]);
});
