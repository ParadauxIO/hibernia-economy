import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { visit } from './helpers';

// The accounts directory on the table + filter-chip system (PAR-534). Seed:
// #3 Acme Corporate Account (BUSINESS, 25,000, firm 1), #5 City Hall
// (GOVERNMENT, 8,000), personal #1 Alice 5,000 / #2 Bob 100,000. The e2e
// run also loads the government workspace fixtures (gov-fixtures.ts), all
// under $1M except the mint, which is far below zero.
const GOV_FIXTURE_ACCOUNTS = ['Commerce Department', 'Commerce Reserve', 'Commerce Annex', 'Interior Ministry', 'Gov Mint'];

const searchBox = (page: Page) => page.getByRole('searchbox', { name: /Search accounts/ });
const chips = (page: Page) => page.getByRole('list', { name: 'Active filters' }).getByRole('listitem');
const rowNames = (page: Page) => page.locator('#accounts-table tbody .dt-rowlink > span:first-child').allInnerTexts();

test('typed tokens become chips, filter the rows, and live in the URL', async ({ page }) => {
  await visit(page, '/accounts');
  await searchBox(page).click();
  await searchBox(page).pressSequentially('type:business ');
  await expect(page).toHaveURL(/f=type:in:business/);
  await expect(chips(page)).toHaveCount(1);
  await expect(chips(page).first()).toContainText('Business');
  await expect(searchBox(page)).toHaveValue('');
  await expect.poll(() => rowNames(page)).toEqual(['Government supplier', 'Acme Corporate Account']);
  await expect(page.getByRole('status')).toHaveText('2 accounts');

  // Backspace in the empty box removes the last chip.
  await searchBox(page).press('Backspace');
  await expect(page).not.toHaveURL(/f=/);
  await expect(chips(page)).toHaveCount(0);
});

test('a chip reopens its popover to edit, and Escape returns focus to it', async ({ page }) => {
  await visit(page, '/accounts?f=type:in:business');
  const chip = page.getByRole('button', { name: 'Type: Business. Edit filter' });
  await chip.click();
  const dialog = page.getByRole('dialog', { name: 'Filter by Type' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('checkbox', { name: /Business/ })).toBeChecked();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(chip).toBeFocused();

  await chip.click();
  await dialog.getByRole('checkbox', { name: /Government/ }).check();
  await dialog.getByRole('button', { name: 'Apply' }).click();
  await expect(page).toHaveURL(/f=type:in:business,government/);
  await expect.poll(async () => (await rowNames(page)).sort()).toEqual(['Acme Corporate Account', 'Government supplier', 'City Hall', ...GOV_FIXTURE_ACCOUNTS].sort());
});

test('the + Filter menu adds a balance filter; the empty state names the filters and removes one', async ({ page }) => {
  await visit(page, '/accounts?f=type:in:government');
  await page.getByRole('button', { name: 'Filter', exact: true }).click();
  await page.getByRole('dialog', { name: 'Add a filter' }).getByRole('button', { name: /^Balance/ }).click();
  const dialog = page.getByRole('dialog', { name: 'Filter by Balance' });
  await dialog.getByRole('textbox', { name: 'Value' }).fill('1m');
  await dialog.getByRole('button', { name: 'Apply' }).click();
  await expect(page).toHaveURL(/f=balance:gt:1000000/);
  const empty = page.locator('#accounts-table .dt-empty');
  await expect(empty).toContainText('No accounts match Type: Government and Balance > $1M.');
  await empty.getByRole('link', { name: 'Remove Balance > $1M' }).click();
  await expect(page).not.toHaveURL(/balance/);
  await expect.poll(async () => (await rowNames(page)).sort()).toEqual(['City Hall', ...GOV_FIXTURE_ACCOUNTS].sort());
});

test('sort headers are links with aria-sort, and a second click reverses', async ({ page }) => {
  await visit(page, '/accounts');
  const balance = page.getByRole('columnheader', { name: /Balance/ });
  await expect(balance).toHaveAttribute('aria-sort', 'descending');
  await balance.getByRole('link').click();
  await expect(page).toHaveURL(/sort=balance(&|$)/);
  await expect(page.getByRole('columnheader', { name: /Balance/ })).toHaveAttribute('aria-sort', 'ascending');
  await expect(page.getByText('Sorted by balance, low to high')).toBeVisible();
});

test('old ?type= links still filter, as a chip', async ({ page }) => {
  await visit(page, '/accounts?type=BUSINESS');
  await expect(chips(page)).toHaveCount(1);
  await expect.poll(() => rowNames(page)).toEqual(['Government supplier', 'Acme Corporate Account']);
});

test('chips scroll sideways and popovers open as bottom sheets on phones', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await visit(page, '/accounts?f=type:in:personal,business,government&f=balance:between:1000,200000&f=created:gte:-1y');
  const list = page.getByRole('list', { name: 'Active filters' });
  expect(await list.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
  await chips(page).first().getByRole('button', { name: /Edit filter/ }).click();
  const sheet = page.getByRole('dialog', { name: 'Filter by Type' });
  await expect(sheet).toHaveClass(/is-sheet/);
  const box = await sheet.boundingBox();
  expect(Math.round(box!.y + box!.height)).toBe(844);
});

test('no serious axe violations with chips applied and a filter popover open', async ({ page }) => {
  await visit(page, '/accounts?f=type:in:business&f=balance:gt:1000');
  await page.getByRole('button', { name: 'Type: Business. Edit filter' }).click();
  await expect(page.getByRole('dialog', { name: 'Filter by Type' })).toBeVisible();
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']).analyze();
  const blocking = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
  expect(blocking).toEqual([]);
});
