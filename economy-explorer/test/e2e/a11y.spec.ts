import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { visit } from './helpers';

// Accessibility gate: axe-core scans every top-level route, logged out and as the
// shim roles that unlock the gated pages, and fails on serious/critical
// violations. Moderate/minor findings are reported in the attachment but don't
// fail the run. Runs on both projects, so the phone layout (collapsed nav, mobile
// search) is scanned too.
//
// Known, tracked exclusions go in KNOWN with a reason; keep this list short.

type Viewer = 'anon' | 'player' | 'moderator' | 'government' | 'admin';

const ROUTES: { path: string; viewers: Viewer[] }[] = [
  { path: '/', viewers: ['anon', 'admin'] },
  { path: '/accounts', viewers: ['anon'] },
  { path: '/accounts/1', viewers: ['anon', 'player'] },
  { path: '/firms', viewers: ['anon'] },
  { path: '/firms/Acme%20Corp', viewers: ['anon', 'player'] },
  { path: '/market', viewers: ['anon', 'admin'] },
  { path: '/market/activity', viewers: ['anon'] },
  { path: '/chestshop', viewers: ['anon'] },
  { path: '/chestshop/shops?item=DIAMOND', viewers: ['anon'] },
  { path: '/chestshop/items/DIAMOND?advanced=true', viewers: ['anon'] },
  { path: '/chestshop/firms', viewers: ['anon'] },
  { path: '/chestshop/firms/1', viewers: ['anon', 'admin'] },
  { path: '/chestshop/sales', viewers: ['admin'] },
  { path: '/economy/wealth', viewers: ['anon'] },
  { path: '/economy/taxation', viewers: ['anon'] },
  { path: '/money-flow', viewers: ['anon'] },
  { path: '/government', viewers: ['anon', 'government'] },
  { path: '/government?view=fines', viewers: ['admin'] },
  { path: '/transactions', viewers: ['anon', 'moderator'] },
  { path: '/transactions/1', viewers: ['admin'] },
  { path: '/search?q=Alice', viewers: ['anon'] },
  { path: '/me', viewers: ['anon', 'player'] },
  { path: '/me/market', viewers: ['player'] },
  { path: '/me/webhooks', viewers: ['player'] },
  { path: '/link', viewers: ['player'] },
  { path: '/docs', viewers: ['anon'] },
  { path: '/docs/guides/tax', viewers: ['anon'] },
  { path: '/admin', viewers: ['admin'] },
  { path: '/admin/accounts', viewers: ['admin'] },
  { path: '/admin/api-keys', viewers: ['admin'] },
  { path: '/admin/firms', viewers: ['admin'] },
  { path: '/admin/groups', viewers: ['admin'] },
  { path: '/admin/groups/1', viewers: ['admin'] },
  { path: '/admin/players', viewers: ['admin'] },
  { path: '/admin/roles', viewers: ['admin'] },
  { path: '/admin/roles/player', viewers: ['admin'] },
  { path: '/admin/webhooks', viewers: ['admin'] },
  { path: '/this-route-does-not-exist', viewers: ['anon'] },
];

/** rule id → why it is excluded and where it is tracked. */
const KNOWN: Record<string, string> = {};

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100';

for (const route of ROUTES) {
  for (const viewer of route.viewers) {
    test(`a11y ${route.path} as ${viewer}`, async ({ page, context }, testInfo) => {
      test.skip(viewer !== 'anon' && !process.env.E2E_AUTH_SHIM, 'role scans need the auth shim');
      if (viewer !== 'anon') await context.addCookies([{ name: 'e2e_viewer', value: viewer, url: baseURL }]);
      await visit(page, route.path);
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'])
        .disableRules(Object.keys(KNOWN))
        .analyze();
      await testInfo.attach('axe-violations', { body: JSON.stringify(results.violations, null, 2), contentType: 'application/json' });
      const blocking = results.violations
        .filter((v) => v.impact === 'serious' || v.impact === 'critical')
        .map((v) => `${v.impact} ${v.id}: ${v.help} — ${v.nodes.map((n) => n.target.join(' ')).slice(0, 5).join(' | ')}`);
      expect(blocking, `serious/critical axe violations on ${route.path} as ${viewer}`).toEqual([]);
    });
  }
}
