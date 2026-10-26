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
  { path: '/accounts?f=type:in:business,government&f=balance:gt:1000&f=created:gte:-1y', viewers: ['anon'] },
  { path: '/accounts?f=type:in:system&f=balance:gt:1b', viewers: ['anon'] },
  { path: '/accounts/1', viewers: ['anon', 'player'] },
  { path: '/accounts/1?f=direction:in:out&limit=1', viewers: ['player'] },
  { path: '/accounts/3', viewers: ['anon', 'admin'] },
  { path: '/accounts/5', viewers: ['anon'] },
  { path: '/players', viewers: ['anon'] },
  { path: '/players?f=hasFirm:eq:true', viewers: ['anon'] },
  { path: '/players/Alice', viewers: ['anon', 'player'] },
  { path: '/firms', viewers: ['anon'] },
  { path: '/firms?f=staff:gt:1', viewers: ['anon'] },
  { path: '/firms/Acme%20Corp', viewers: ['anon', 'player'] },
  { path: '/firms/Acme%20Corp/members', viewers: ['anon', 'player'] },
  { path: '/firms/Acme%20Corp/financials', viewers: ['anon', 'player'] },
  { path: '/market', viewers: ['anon', 'admin'] },
  { path: '/market/activity', viewers: ['anon'] },
  { path: '/chestshop', viewers: ['anon'] },
  { path: '/chestshop/shops?item=DIAMOND', viewers: ['anon'] },
  { path: '/chestshop/items/DIAMOND?compare=IRON_INGOT', viewers: ['anon', 'admin'] },
  { path: '/chestshop/firms', viewers: ['anon'] },
  { path: '/chestshop/firms/1', viewers: ['anon', 'admin'] },
  { path: '/chestshop/sales', viewers: ['admin'] },
  { path: '/chestshop/sales?f=direction:in:sell&f=item:in:DIAMOND', viewers: ['admin'] },
  { path: '/economy/wealth', viewers: ['anon'] },
  { path: '/economy/taxation', viewers: ['anon'] },
  { path: '/money-flow', viewers: ['anon'] },
  { path: '/gov', viewers: ['anon', 'government'] },
  { path: '/gov/fines', viewers: ['admin'] },
  { path: '/admin/gov-units', viewers: ['admin'] },
  { path: '/transactions', viewers: ['anon', 'moderator'] },
  { path: '/transactions?f=plugin:in:treasury&f=amount:gt:50', viewers: ['moderator'] },
  { path: '/transactions?txn=3', viewers: ['admin'] },
  { path: '/transactions/1', viewers: ['admin'] },
  { path: '/search?q=Alice', viewers: ['anon'] },
  { path: '/search?q=acme&type=firms', viewers: ['anon'] },
  { path: '/me', viewers: ['anon', 'player'] },
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
  { path: '/design-system', viewers: ['anon'] },
  { path: '/design-system/mock-account', viewers: ['anon'] },
  { path: '/design-system/mock-account?view=public', viewers: ['anon'] },
  { path: '/design-system/mock-me', viewers: ['anon'] },
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
