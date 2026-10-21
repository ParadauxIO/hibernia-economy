import { test, expect, type BrowserContext } from '@playwright/test';
import { encode } from 'next-auth/jwt';
import { readFileSync } from 'node:fs';

type Fixture = {
  senderUuid: string; senderName: string; senderAccount: number;
  recipientUuid: string; recipientName: string; recipientAccount: number;
  outsiderUuid: string; outsiderName: string; transactionId: number; memo: string;
};
const fixture: Fixture = JSON.parse(readFileSync(process.env.IT_EXPLORER_FIXTURE!, 'utf8'));

async function signIn(context: BrowserContext, uuid: string, name: string) {
  const cookie = 'authjs.session-token';
  const token = await encode({ secret: process.env.E2E_AUTH_SECRET!, salt: cookie, maxAge: 3600,
    token: { sub: `live-${uuid}`, minecraftUuid: uuid, minecraftName: name } });
  await context.clearCookies();
  await context.addCookies([{ name: cookie, value: token, url: process.env.E2E_BASE_URL!,
    httpOnly: true, sameSite: 'Lax' }]);
}

test('real in-game payment is public as a balance but private as history', async ({ page, context }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => {
    if (response.url().startsWith(process.env.E2E_BASE_URL!) && response.status() >= 500) {
      errors.push(`HTTP ${response.status()} ${new URL(response.url()).pathname}`);
    }
  });
  await page.goto(`/accounts/${fixture.senderAccount}`);
  await expect(page.getByText('Current balance', { exact: true })).toBeVisible();
  await expect(page.locator('.kpi-value').filter({ hasText: '9,976.55' })).toBeVisible();
  await expect(page.getByText('Transaction history is private', { exact: true })).toBeVisible();
  await expect(page.getByText(fixture.memo, { exact: false })).toHaveCount(0);

  await page.goto(`/transactions/${fixture.transactionId}`);
  await expect(page.getByText('Transaction detail is private', { exact: true })).toBeVisible();
  await expect(page.getByText(fixture.memo, { exact: false })).toHaveCount(0);

  await signIn(context, fixture.senderUuid, fixture.senderName);
  await page.goto(`/accounts/${fixture.senderAccount}`);
  await expect(page.getByRole('region', { name: 'Recent activity' })).toContainText(fixture.memo);
  await expect(page.getByText('Transaction history is private', { exact: true })).toHaveCount(0);
  await page.goto(`/transactions/${fixture.transactionId}`);
  await expect(page.getByText(fixture.memo, { exact: false }).first()).toBeVisible();

  await signIn(context, fixture.outsiderUuid, fixture.outsiderName);
  await page.goto(`/accounts/${fixture.senderAccount}`);
  await expect(page.getByText('Transaction history is private', { exact: true })).toBeVisible();
  await expect(page.getByText(fixture.memo, { exact: false })).toHaveCount(0);

  await page.goto(`/transactions/${fixture.transactionId}`);
  await expect(page.getByText('Transaction access required', { exact: true })).toBeVisible();
  await expect(page.getByText(fixture.memo, { exact: false })).toHaveCount(0);

  await signIn(context, fixture.recipientUuid, fixture.recipientName);
  await page.goto(`/accounts/${fixture.recipientAccount}`);
  await expect(page.locator('.kpi-value').filter({ hasText: '10,023.45' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Recent activity' })).toContainText(fixture.memo);
  await page.reload();
  await expect(page.getByRole('region', { name: 'Recent activity' })).toContainText(fixture.memo);
  expect(errors).toEqual([]);
});
