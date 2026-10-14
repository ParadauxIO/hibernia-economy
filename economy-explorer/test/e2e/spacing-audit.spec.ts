import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';

// UI consistency audit. Drives both a desktop and a mobile viewport on Chromium
// (the device presets default to WebKit, which we don't install — and this is a
// CSS-layout audit, not an engine audit). For each page it:
//   1) asserts no horizontal overflow,
//   2) measures the vertical gaps between top-level sections (.explorer-main > *)
//      so uneven section rhythm surfaces as >1 distinct gap value,
//   3) saves a full-page screenshot.
// Run with: npx playwright test spacing-audit --project=chromium

const VIEWPORTS = {
  desktop: { width: 1280, height: 900 },
  mobile: { width: 390, height: 844 },
} as const;

const ROUTES: { path: string; name: string; viewer?: 'admin' }[] = [
  { path: '/', name: 'overview' },
  { path: '/accounts', name: 'accounts' },
  { path: '/accounts/1', name: 'account-detail' },
  { path: '/firms', name: 'firms' },
  { path: '/firms/Acme%20Corp', name: 'firm-detail' },
  { path: '/market', name: 'market' },
  { path: '/chestshop', name: 'chestshop' },
  { path: '/chestshop/items/DIAMOND', name: 'chestshop-item' },
  { path: '/chestshop/shops', name: 'chestshop-shops' },
  { path: '/chestshop/sales', name: 'chestshop-sales', viewer: 'admin' },
  { path: '/chestshop/firms', name: 'chestshop-firms' },
  { path: '/economy/health', name: 'health' },
  { path: '/money-flow', name: 'money-flow' },
  { path: '/government', name: 'government' },
  { path: '/search?q=a', name: 'search' },
  { path: '/transactions', name: 'transactions', viewer: 'admin' },
  { path: '/transactions/1', name: 'txn-detail', viewer: 'admin' },
  { path: '/me', name: 'me', viewer: 'admin' },
  { path: '/me/market', name: 'me-market', viewer: 'admin' },
  { path: '/admin/api-keys', name: 'admin-api-keys', viewer: 'admin' },
  { path: '/admin/roles', name: 'admin-roles', viewer: 'admin' },
  { path: '/admin/roles/player', name: 'admin-role-player', viewer: 'admin' },
  { path: '/admin/groups/1', name: 'admin-group', viewer: 'admin' },
  { path: '/docs', name: 'docs' },
  { path: '/link', name: 'link', viewer: 'admin' },
];

const OUT = process.env.AUDIT_DIR || 'audit';
mkdirSync(OUT, { recursive: true });

for (const route of ROUTES) {
  test(`audit ${route.name}`, async ({ page, context }) => {
    if (route.viewer) {
      await context.addCookies([{ name: 'e2e_viewer', value: route.viewer, url: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100' }]);
    }

    const failures: string[] = [];
    for (const [vp, size] of Object.entries(VIEWPORTS)) {
      await page.setViewportSize(size);
      await page.goto(route.path, { waitUntil: 'domcontentloaded' });
      await page.waitForLoadState('networkidle').catch(() => {});
      await page.waitForTimeout(250);

      // Guard: the page actually rendered content (a blank/errored page would
      // trivially pass the overflow + gap checks, so assert it isn't empty).
      const childCount = await page.locator('.explorer-main > *').count();
      if (childCount === 0) failures.push(`${vp}: .explorer-main rendered empty`);

      const overflow = await page.evaluate(() => ({
        scrollW: document.documentElement.scrollWidth,
        innerW: window.innerWidth,
      }));

      const gaps = await page.evaluate(() => {
        const main = document.querySelector('.explorer-main');
        if (!main) return [] as number[];
        const kids = Array.from(main.children).filter((el) => {
          const r = el.getBoundingClientRect();
          const cs = getComputedStyle(el);
          return r.height > 0 && cs.display !== 'none' && cs.position !== 'absolute';
        });
        const out: number[] = [];
        for (let i = 1; i < kids.length; i++) {
          out.push(Math.round(kids[i].getBoundingClientRect().top - kids[i - 1].getBoundingClientRect().bottom));
        }
        return out;
      });
      const distinctGaps = [...new Set(gaps.filter((g) => g >= 0))].sort((a, b) => a - b);

      await page.screenshot({ path: `${OUT}/${vp}-${route.name}.png`, fullPage: true });
      writeFileSync(`${OUT}/${vp}-${route.name}.json`,
        JSON.stringify({ route: route.path, vp, overflow, gaps, distinctGaps }));

      if (overflow.scrollW > overflow.innerW + 1) {
        failures.push(`${vp}: horizontal overflow ${overflow.scrollW} > ${overflow.innerW}`);
      }
    }
    expect(failures, `${route.path}\n${failures.join('\n')}`).toEqual([]);
  });
}
