import { permissionsFor } from '../role-fixtures';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { auditGovRead, govSelection, resolveGovScope, withinScope } from '@/lib/services/governmentScope';
import { getGovernmentAccounts } from '@/lib/sql/government';
import { listGovUnitAccounts, listGovUnits } from '@/lib/sql/govUnits';
import { auditView } from '@/lib/audit';

// The /gov scope rules (spec §10.1) with the SQL mocked: RBAC is checked before
// any query, a relationship viewer's scope is exactly the accounts the SQL
// returns, units never add accounts, and relationship reads are audited.
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('@/lib/sql/government', () => ({ getGovernmentAccounts: vi.fn() }));
vi.mock('@/lib/sql/govUnits', () => ({ listGovUnits: vi.fn(), listGovUnitAccounts: vi.fn() }));
vi.mock('@/lib/audit', () => ({ auditView: vi.fn() }));

const base = { anon: false as const, keycloakSub: 'sub', minecraftUuid: 'uuid', minecraftName: null, linked: true };
const government: Viewer = { ...base, role: 'government', capabilities: ['player', 'government'], permissions: permissionsFor('player', 'government') };
const moderator: Viewer = { ...base, role: 'moderator', capabilities: ['player', 'moderator'], permissions: permissionsFor('player', 'moderator') };
const player: Viewer = { ...base, role: 'player', capabilities: ['player'], permissions: permissionsFor('player') };
const account = (id: number) => ({ account_id: id, display_name: `Gov ${id}`, owner_uuid: null, owner_name: null, account_type: 'GOVERNMENT', balance: '100.00' });
const unit = (unit_id: number, slug: string, parent: number | null = null) => ({ unit_id, slug, name: slug, parent_unit_id: parent, description: null, created_at: new Date(0), created_by_uuid: null });

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getViewer).mockResolvedValue(government);
  vi.mocked(getGovernmentAccounts).mockResolvedValue([account(7), account(8)]);
  vi.mocked(listGovUnits).mockResolvedValue([unit(1, 'commerce'), unit(2, 'interior'), unit(3, 'trade', 1)]);
  // commerce: 7 (readable) + 9 (not); interior: 10 (not); trade (child of commerce): 8.
  vi.mocked(listGovUnitAccounts).mockResolvedValue([
    { unit_id: 1, account_id: 7 }, { unit_id: 1, account_id: 9 }, { unit_id: 2, account_id: 10 }, { unit_id: 3, account_id: 8 },
  ]);
});

describe('resolveGovScope', () => {
  it('rechecks RBAC before issuing any query', async () => {
    vi.mocked(getViewer).mockResolvedValue(player);
    expect(await resolveGovScope()).toEqual({ ok: false, reason: 'permission' });
    expect(getGovernmentAccounts).not.toHaveBeenCalled();
  });

  it('turns away anonymous viewers and unlinked officials', async () => {
    vi.mocked(getViewer).mockResolvedValue({ anon: true } as Viewer);
    expect(await resolveGovScope()).toEqual({ ok: false, reason: 'login' });
    vi.mocked(getViewer).mockResolvedValue({ ...government, minecraftUuid: null } as Viewer);
    expect(await resolveGovScope()).toEqual({ ok: false, reason: 'unlinked' });
    expect(getGovernmentAccounts).not.toHaveBeenCalled();
  });

  it('scopes a relationship viewer to the accounts they can read', async () => {
    const access = await resolveGovScope();
    expect(getGovernmentAccounts).toHaveBeenCalledWith({ playerUuid: 'uuid' });
    if (!access.ok) throw new Error('expected scope');
    expect(access.scope.all).toBe(false);
    expect(access.scope.ids).toEqual([7, 8]);
    expect(access.scope.canAudit).toBe(true);
    expect(access.scope.canManageUnits).toBe(false);
  });

  it('gives government.read.all every government account without a relationship', async () => {
    vi.mocked(getViewer).mockResolvedValue(moderator);
    const access = await resolveGovScope();
    expect(getGovernmentAccounts).toHaveBeenCalledWith({ all: true });
    expect(access.ok && access.scope.all).toBe(true);
  });

  it('shows a unit only with a readable account, and marks partial views', async () => {
    const access = await resolveGovScope();
    if (!access.ok) throw new Error('expected scope');
    const bySlug = Object.fromEntries(access.scope.units.map((u) => [u.slug, u]));
    expect(Object.keys(bySlug)).toEqual(['commerce', 'trade']); // interior holds nothing readable
    expect(bySlug.commerce).toMatchObject({ readableIds: [7, 8], totalAccounts: 3, partial: true, depth: 0 });
    expect(bySlug.trade).toMatchObject({ readableIds: [8], totalAccounts: 1, partial: false, depth: 1 });
  });

  it('an empty relationship scope never widens to everything', async () => {
    vi.mocked(getGovernmentAccounts).mockResolvedValue([]);
    const access = await resolveGovScope();
    if (!access.ok) throw new Error('expected scope');
    expect(access.scope.ids).toEqual([]);
    expect(access.scope.units).toEqual([]);
    expect(govSelection(access.scope, 'commerce').ids).toEqual([]);
  });
});

describe('selection and audit', () => {
  it('never selects outside the scope', async () => {
    const access = await resolveGovScope();
    if (!access.ok) throw new Error('expected scope');
    expect(govSelection(access.scope, 'interior')).toMatchObject({ key: 'all', ids: [7, 8] });
    expect(govSelection(access.scope, ['trade'])).toMatchObject({ key: 'trade', ids: [8] });
    expect(withinScope(access.scope, [7, 9, 10, 8])).toEqual([7, 8]);
  });

  it('records one account row per relationship read and one global row for .all viewers', async () => {
    const access = await resolveGovScope();
    if (!access.ok) throw new Error('expected scope');
    await auditGovRead(access.scope, '/gov', [7, 8, 99]);
    expect(vi.mocked(auditView).mock.calls.map((c) => c[1])).toEqual([
      { path: '/gov', targetType: 'account', targetId: '7', method: 'GET' },
      { path: '/gov', targetType: 'account', targetId: '8', method: 'GET' },
    ]);
    vi.mocked(auditView).mockClear();
    await auditGovRead({ ...access.scope, all: true }, '/gov/fines', [7]);
    expect(vi.mocked(auditView).mock.calls.map((c) => c[1])).toEqual([{ path: '/gov/fines', targetType: 'global', method: 'GET' }]);
  });
});
