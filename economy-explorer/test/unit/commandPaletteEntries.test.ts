import { describe, expect, it } from 'vitest';
import { actionEntries, buildGroups, filterEntries, pageEntries, resultEntry, score } from '@/components/CommandPaletteEntries';
import { ANON_VIEWER, viewerSnapshot } from '@/lib/viewerSnapshot';
import type { Viewer } from '@/lib/auth/viewer';
import type { Permission } from '@/lib/auth/policy';

const signedIn = (permissions: Permission[], linked = true): Viewer => ({
  anon: false, keycloakSub: 's', minecraftUuid: '00000000-0000-0000-0000-00000000a1ce', minecraftName: 'A', linked,
  role: 'x', capabilities: ['x'], permissions,
});

const labels = (entries: { label: string }[]) => entries.map((e) => e.label);

describe('palette entries', () => {
  it('lists only what the viewer may open', () => {
    expect(labels(pageEntries(ANON_VIEWER))).not.toContain('My finances');
    const player = labels(pageEntries(viewerSnapshot(signedIn([]), { governmentInScope: false })));
    expect(player).toEqual(expect.arrayContaining(['Overview', 'Players', 'Find shops', 'Docs', 'My finances', 'Statements', 'Webhooks and API']));
    expect(player).not.toContain('Sales');
    expect(player).not.toContain('Admin');
    expect(player).not.toContain('Government');
    const admin = labels(pageEntries(viewerSnapshot(signedIn(['market.sales.read', 'api_keys.read', 'transactions.read', 'government.read.all']), { governmentInScope: false })));
    expect(admin).toEqual(expect.arrayContaining(['Sales', 'Admin', 'Government', 'Investigate']));
  });

  it('offers linking to unlinked viewers only', () => {
    expect(labels(pageEntries(viewerSnapshot(signedIn([], false), { governmentInScope: false })))).toContain('Link your Minecraft account');
    expect(labels(pageEntries(viewerSnapshot(signedIn([]), { governmentInScope: false })))).not.toContain('Link your Minecraft account');
  });

  it('switches Log in and Log out with the session', () => {
    expect(labels(actionEntries(ANON_VIEWER, { serverIp: 'x', nextMode: 'light', copied: false }))).toEqual(['Copy server IP', 'Toggle theme', 'Log in']);
    expect(labels(actionEntries(viewerSnapshot(signedIn([]), { governmentInScope: false }), { serverIp: 'x', nextMode: 'light', copied: false }))).toContain('Log out');
  });

  it('ranks exact, then prefix, then word-start, then substring matches', () => {
    const e = (label: string) => ({ id: label, type: 'link' as const, kind: 'page' as const, label, href: '/' as const });
    expect(score(e('Wealth'), 'wealth')).toBe(0);
    expect(score(e('Wealth'), 'wea')).toBe(1);
    expect(score(e('Money flow'), 'flow')).toBe(2);
    expect(score(e('Statements'), 'ate')).toBe(3);
    expect(score(e('Wealth'), 'zzz')).toBeNull();
    expect(labels(filterEntries([e('Market firms'), e('Firms'), e('Find shops')], 'fi'))).toEqual(['Firms', 'Find shops', 'Market firms']);
  });

  it('formats account results with exact money and links each kind', () => {
    const account = resultEntry({ kind: 'account', label: 'Alice', secondary: 'PERSONAL', account_id: 7, account_type: 'PERSONAL', balance: '-1234.5', firm_id: null, firm_name: null, player_uuid: null, player_name: null }, '£');
    expect(account).toMatchObject({ href: '/accounts/7', detail: '#7 · PERSONAL · −£1,234.50' });
    const firm = resultEntry({ kind: 'firm', label: 'Acme & Co', secondary: '—', account_id: null, account_type: null, balance: null, firm_id: 1, firm_name: 'Acme & Co', player_uuid: null, player_name: null }, '$');
    expect(firm).toMatchObject({ href: '/firms/Acme%20%26%20Co', detail: undefined });
  });

  it('puts a page first when the query names it, entities first otherwise', () => {
    const pages = pageEntries(viewerSnapshot(signedIn([]), { governmentInScope: false }));
    const actions = actionEntries(ANON_VIEWER, { serverIp: 'x', nextMode: 'light', copied: false });
    const results = [resultEntry({ kind: 'player', label: 'Statler', secondary: null, account_id: null, account_type: null, balance: null, firm_id: null, firm_name: null, player_uuid: null, player_name: 'Statler' }, '$')];
    expect(buildGroups({ query: 'statem', recent: [], results, pages, actions }).map((g) => g.id)).toEqual(['pages', 'results', 'search']);
    expect(buildGroups({ query: 'alice', recent: [], results, pages, actions }).map((g) => g.id)).toEqual(['results', 'search']);
    expect(buildGroups({ query: '', recent: [], results: [], pages, actions }).map((g) => g.id)).toEqual(['pages', 'actions']);
  });
});
