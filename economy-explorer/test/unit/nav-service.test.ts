import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Viewer } from '@/lib/auth/viewer';
import type { Permission } from '@/lib/auth/policy';

const sqlNav = vi.hoisted(() => ({
  hasGovernmentAccountInScope: vi.fn<(uuid: string) => Promise<boolean>>(),
  findPersonalBalances: vi.fn<(uuid: string) => Promise<string[]>>(),
}));
vi.mock('@/lib/sql/nav', () => sqlNav);
vi.mock('@/lib/redis', () => ({ redis: () => null, tenantKey: (k: string) => `t:${k}` }));

import { chromeViewer, governmentInScope, personalBalance } from '@/lib/services/nav';
import { resetCacheForTests } from '@/lib/cache';

const UUID = '00000000-0000-0000-0000-00000000a1ce';
const signedIn = (permissions: Permission[], extra: Partial<Extract<Viewer, { anon: false }>> = {}): Viewer => ({
  anon: false, keycloakSub: 's', minecraftUuid: UUID, minecraftName: 'Alice', linked: true,
  role: 'x', capabilities: ['x'], permissions, ...extra,
});
const ANON: Viewer = { anon: true, role: null, capabilities: [], permissions: [], keycloakSub: null, minecraftUuid: null, minecraftName: null, linked: false };

beforeEach(() => {
  resetCacheForTests();
  sqlNav.hasGovernmentAccountInScope.mockReset();
  sqlNav.findPersonalBalances.mockReset();
});

describe('governmentInScope', () => {
  it('queries only for government.read holders without .all', async () => {
    sqlNav.hasGovernmentAccountInScope.mockResolvedValue(true);
    expect(await governmentInScope(ANON)).toBe(false);
    expect(await governmentInScope(signedIn(['player.read']))).toBe(false);
    expect(await governmentInScope(signedIn(['government.read', 'government.read.all']))).toBe(false); // .all needs no scope
    expect(sqlNav.hasGovernmentAccountInScope).not.toHaveBeenCalled();
    expect(await governmentInScope(signedIn(['government.read']))).toBe(true);
    expect(sqlNav.hasGovernmentAccountInScope).toHaveBeenCalledWith(UUID);
  });

  it('caches per viewer, so the next page view costs no query', async () => {
    sqlNav.hasGovernmentAccountInScope.mockResolvedValue(true);
    await governmentInScope(signedIn(['government.read']));
    await governmentInScope(signedIn(['government.read']));
    expect(sqlNav.hasGovernmentAccountInScope).toHaveBeenCalledTimes(1);
    await governmentInScope(signedIn(['government.read'], { minecraftUuid: '00000000-0000-0000-0000-000000000b0b' }));
    expect(sqlNav.hasGovernmentAccountInScope).toHaveBeenCalledTimes(2);
  });

  it('fails closed', async () => {
    sqlNav.hasGovernmentAccountInScope.mockRejectedValue(new Error('db down'));
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(await governmentInScope(signedIn(['government.read']))).toBe(false);
  });

  it('feeds the chrome snapshot', async () => {
    sqlNav.hasGovernmentAccountInScope.mockResolvedValue(true);
    expect((await chromeViewer(signedIn(['government.read']))).canSeeGovernment).toBe(true);
    resetCacheForTests();
    sqlNav.hasGovernmentAccountInScope.mockResolvedValue(false);
    expect((await chromeViewer(signedIn(['government.read']))).canSeeGovernment).toBe(false);
  });
});

describe('personalBalance', () => {
  it('sums personal accounts exactly, as a DB decimal string', async () => {
    // 0.10 + 0.20 is not 0.30 in floating point.
    sqlNav.findPersonalBalances.mockResolvedValue(['0.10', '0.20', '-1000000000000.05']);
    expect(await personalBalance(signedIn([]))).toBe('-999999999999.75');
  });

  it('is null without a linked player or with no personal account', async () => {
    sqlNav.findPersonalBalances.mockResolvedValue([]);
    expect(await personalBalance(ANON)).toBeNull();
    expect(await personalBalance(signedIn([], { minecraftUuid: null }))).toBeNull();
    expect(await personalBalance(signedIn([]))).toBeNull();
  });

  it('is cached per viewer and degrades to null on failure', async () => {
    sqlNav.findPersonalBalances.mockResolvedValue(['12.34']);
    await personalBalance(signedIn([]));
    expect(await personalBalance(signedIn([]))).toBe('12.34');
    expect(sqlNav.findPersonalBalances).toHaveBeenCalledTimes(1);
    resetCacheForTests();
    sqlNav.findPersonalBalances.mockRejectedValue(new Error('db down'));
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(await personalBalance(signedIn([]))).toBeNull();
  });
});
