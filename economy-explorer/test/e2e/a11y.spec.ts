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
  { path: '/', viewers: ['player', 'admin'] },
  { path: '/accounts', viewers: ['player'] },
  { path: '/accounts?f=type:in:business,government&f=balance:gt:1000&f=created:gte:-1y', viewers: ['player'] },
  { path: '/accounts?f=type:in:system&f=balance:gt:1b', viewers: ['player'] },
  { path: '/accounts/1', viewers: ['player'] },
  { path: '/accounts/1?f=direction:in:out&limit=1', viewers: ['player'] },
  { path: '/accounts/3', viewers: ['player', 'admin'] },
  { path: '/accounts/5', viewers: ['player'] },
  { path: '/players', viewers: ['player'] },
  { path: '/players?f=hasFirm:eq:true', viewers: ['player'] },
  { path: '/players/Alice', viewers: ['player'] },
  { path: '/firms', viewers: ['player'] },
  { path: '/firms?f=staff:gt:1', viewers: ['player'] },
  { path: '/firms/Acme%20Corp', viewers: ['player'] },
  { path: '/firms/Acme%20Corp/members', viewers: ['player'] },
  { path: '/firms/Acme%20Corp/financials', viewers: ['player'] },
  { path: '/market', viewers: ['player', 'admin'] },
  { path: '/market/activity', viewers: ['player'] },
  { path: '/chestshop', viewers: ['player'] },
  { path: '/chestshop/shops?item=DIAMOND', viewers: ['player'] },
  { path: '/chestshop/items/DIAMOND?compare=IRON_INGOT', viewers: ['player', 'admin'] },
  { path: '/chestshop/firms', viewers: ['player'] },
  { path: '/chestshop/firms/1', viewers: ['player', 'admin'] },
  { path: '/chestshop/sales', viewers: ['admin'] },
  { path: '/chestshop/sales?f=direction:in:sell&f=item:in:DIAMOND', viewers: ['admin'] },
  { path: '/economy/wealth', viewers: ['player'] },
  { path: '/economy/taxation', viewers: ['player'] },
  { path: '/money-flow', viewers: ['player'] },
  { path: '/gov', viewers: ['player', 'government'] },
  { path: '/gov/fines', viewers: ['admin'] },
  { path: '/admin/gov-units', viewers: ['admin'] },
  { path: '/transactions', viewers: ['player', 'moderator'] },
  { path: '/transactions?f=plugin:in:treasury&f=amount:gt:50', viewers: ['moderator'] },
  { path: '/transactions?txn=3', viewers: ['admin'] },
  { path: '/transactions/1', viewers: ['admin'] },
  { path: '/search?q=Alice', viewers: ['player'] },
  { path: '/search?q=acme&type=firms', viewers: ['player'] },
  { path: '/me', viewers: ['player'] },
  { path: '/me/market', viewers: ['player'] },
  { path: '/me/developer/webhooks', viewers: ['player'] },
  { path: '/link', viewers: ['player'] },
  { path: '/docs', viewers: ['anon'] },
  { path: '/docs/guides/tax', viewers: ['anon'] },
  { path: '/admin', viewers: ['admin'] },
  { path: '/admin/accounts', viewers: ['admin'] },
  { path: '/admin/api-keys', viewers: ['admin'] },
  { path: '/admin/audit', viewers: ['admin', 'moderator'] },
  { path: '/admin/firms', viewers: ['admin'] },
  { path: '/admin/firms?q=a', viewers: ['admin'] },
  { path: '/admin/firms/1', viewers: ['admin'] },
  { path: '/admin/groups', viewers: ['admin'] },
  { path: '/admin/groups/1', viewers: ['admin'] },
  { path: '/admin/players', viewers: ['admin'] },
  { path: '/admin/players/00000000-0000-0000-0000-00000000a1ce', viewers: ['admin'] },
  { path: '/admin/role-grants', viewers: ['admin'] },
  { path: '/admin/roles', viewers: ['admin'] },
  { path: '/admin/roles/player', viewers: ['admin'] },
  { path: '/admin/webhooks', viewers: ['admin'] },
  { path: '/design-system', viewers: ['player'] },
  { path: '/design-system/mock-account', viewers: ['player'] },
  { path: '/design-system/mock-account?view=public', viewers: ['player'] },
  { path: '/design-system/mock-me', viewers: ['player'] },
  { path: '/this-route-does-not-exist', viewers: ['player'] },
];

/** rule id → why it is excluded and where it is tracked. */
const KNOWN: Record<string, string> = {};

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100';

for (const route of ROUTES) {
  for (const viewer of route.viewers) {
    test(`a11y ${route.path} as ${viewer}`, async ({ page, context }, testInfo) => {
      test.skip(viewer !== 'anon' && !process.env.E2E_AUTH_SHIM, 'role scans need the auth shim');
      await context.clearCookies();
      if (viewer !== 'anon') await context.addCookies([{ name: 'e2e_viewer', value: viewer, url: baseURL }]);
      await visit(page, route.path);
      // Recharts mounts after hydration. Include its slices in the wealth scan
      // rather than occasionally checking only the empty server placeholder (the balance bands).
      if (route.path === '/economy/wealth') {
        await expect(page.locator('.recharts-bar-rectangle').first()).toBeVisible();
      }
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
