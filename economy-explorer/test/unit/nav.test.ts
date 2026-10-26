import { describe, expect, it } from 'vitest';
import { NAV_SECTIONS, sectionCrumbs, sectionForPath, sectionItems, visibleSections, type NavViewer } from '@/lib/nav';
import { canSeeGovernment, viewerSnapshot } from '@/lib/viewerSnapshot';
import type { Viewer } from '@/lib/auth/viewer';
import type { Permission } from '@/lib/auth/policy';
import { isQuietPath } from '@/lib/accessLog';

const viewer = (permissions: Permission[], canSee = false): NavViewer => ({ permissions, canSeeGovernment: canSee });
const signedIn = (permissions: Permission[], extra: Partial<Extract<Viewer, { anon: false }>> = {}): Viewer => ({
  anon: false, keycloakSub: 's', minecraftUuid: '00000000-0000-0000-0000-00000000a1ce', minecraftName: 'A', linked: true,
  role: 'x', capabilities: ['x'], permissions, ...extra,
});
const ANON: Viewer = { anon: true, role: null, capabilities: [], permissions: [], keycloakSub: null, minecraftUuid: null, minecraftName: null, linked: false };

describe('lib/nav', () => {
  it('has the six sections of spec §6.1 in order', () => {
    expect(NAV_SECTIONS.map((s) => s.label)).toEqual(['Economy', 'Directory', 'Market', 'Government', 'Investigate', 'Docs']);
  });

  it('gates Government on the server-computed flag and Investigate on transactions.read', () => {
    expect(visibleSections(viewer([])).map((s) => s.id)).toEqual(['economy', 'directory', 'market', 'docs']);
    expect(visibleSections(viewer([], true)).map((s) => s.id)).toContain('government');
    expect(visibleSections(viewer(['transactions.read'])).map((s) => s.id)).toContain('investigate');
  });

  it.each([
    ['/', 'economy'], ['/economy/taxation', 'economy'], ['/money-flow', 'economy'],
    ['/players', 'directory'], ['/accounts/1', 'directory'], ['/firms', 'directory'],
    ['/market', 'market'], ['/chestshop/items/X', 'market'],
    ['/government', 'government'], ['/gov', 'government'], ['/gov/units/2', 'government'],
    ['/transactions', 'investigate'], ['/docs', 'docs'],
  ])('%s belongs to %s', (path, id) => {
    expect(sectionForPath(path)?.id).toBe(id);
  });

  it.each(['/me', '/admin', '/link', '/search', '/governmental', '/economyx', '/design-system'])('%s belongs to no section', (path) => {
    expect(sectionForPath(path)).toBeNull();
  });

  it('hides a section nav the viewer cannot see, and permission-gated items', () => {
    expect(sectionItems('/government', viewer([], true))).toBeNull(); // no sub-pages
    expect(sectionItems('/market', viewer([]))?.items.map((i) => i.label)).not.toContain('Sales');
    expect(sectionItems('/market', viewer(['market.sales.read']))?.items.map((i) => i.label)).toContain('Sales');
  });

  it('builds leading breadcrumbs from the nav', () => {
    expect(sectionCrumbs('/firms/Acme')).toEqual([{ label: 'Directory', href: '/accounts' }, { label: 'Firms', href: '/firms' }]);
    expect(sectionCrumbs('/economy/wealth')).toEqual([{ label: 'Economy', href: '/' }, { label: 'Wealth', href: '/economy/wealth' }]);
    expect(sectionCrumbs('/chestshop/items/DIAMOND')).toEqual([{ label: 'Market', href: '/market' }, { label: 'Items', href: '/market' }]);
    expect(sectionCrumbs('/transactions/1')).toEqual([{ label: 'Investigate', href: '/transactions' }]);
    expect(sectionCrumbs('/me')).toEqual([]);
  });
});

describe('viewer snapshot', () => {
  it('needs government.read plus an account in scope, or government.read.all (§10.1)', () => {
    expect(canSeeGovernment(ANON, true)).toBe(false);
    expect(canSeeGovernment(signedIn([]), true)).toBe(false);
    expect(canSeeGovernment(signedIn(['government.read']), false)).toBe(false);
    expect(canSeeGovernment(signedIn(['government.read']), true)).toBe(true);
    expect(canSeeGovernment(signedIn(['government.read.all']), false)).toBe(true);
  });

  it('exposes no identity for anonymous viewers', () => {
    expect(viewerSnapshot(ANON, { governmentInScope: true })).toMatchObject({ anon: true, loggedIn: false, canSeeGovernment: false, minecraftUuid: null, permissions: [] });
  });

  it('carries link state and the derived flags', () => {
    const s = viewerSnapshot(signedIn(['transactions.read', 'api_keys.read'], { linked: false }), { governmentInScope: false });
    expect(s).toMatchObject({ loggedIn: true, linked: false, canInvestigate: true, isAdmin: true, canSeeGovernment: false });
  });
});

describe('access log filter', () => {
  it.each([
    ['/api/avatar/00000000-0000-0000-0000-00000000a1ce', true],
    ['/icons/coin.png', true],
    ['/fonts/geist.woff2', true],
    ['/accounts/1', false],
    ['/api/viewer', false],
    ['/docs/guides/tax', false],
  ])('%s quiet: %s', (path, quiet) => {
    expect(isQuietPath(path)).toBe(quiet);
  });
});
