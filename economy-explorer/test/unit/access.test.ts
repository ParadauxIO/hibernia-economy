import { permissionsFor } from '../role-fixtures';
import { beforeEach, describe, it, expect, vi } from 'vitest';
import type { Viewer } from '@/lib/auth/viewer';
import { canViewAccount, canViewTransaction, canViewFirmFinancials, canViewFirmInternals, isStaff, hasElevatedRead, auditRole, requirePermission, readableAccountIds } from '@/lib/auth/access';
import { hasPermission, PERMISSIONS } from '@/lib/auth/policy';
import { requireRole } from '@/lib/auth/requireRole';
import { ForbiddenError, UnauthorizedError } from '@/lib/errors';
import { findAccount, canReadAccount, findTransactionAccountIds, findReadableAccountIds } from '@/lib/sql/ledger';
import { getAccountFirmId, hasFirmFinancialAccess, isFirmMember } from '@/lib/sql/firm';
vi.mock('@/lib/sql/ledger', () => ({ findAccount: vi.fn(), canReadAccount: vi.fn(), findTransactionAccountIds: vi.fn(), findReadableAccountIds: vi.fn() }));
vi.mock('@/lib/sql/firm', () => ({ getAccountFirmId: vi.fn(), hasFirmFinancialAccess: vi.fn(), isFirmMember: vi.fn(), findFirmByDisplayName: vi.fn(), findFirmById: vi.fn() }));
const anon: Viewer = { anon: true, role: null, capabilities: [], permissions: [], keycloakSub: null, minecraftUuid: null, minecraftName: null, linked: false };
const base = { anon: false as const, keycloakSub: 's', minecraftUuid: 'u', minecraftName: 'n', linked: true };
const player: Viewer = { ...base, role: 'player', capabilities: ['player', 'player'], permissions: permissionsFor('player', 'player') };
const government: Viewer = { ...base, role: 'government', capabilities: ['player', 'government'], permissions: permissionsFor('player', 'government') };
const moderator: Viewer = { ...base, role: 'moderator', capabilities: ['player', 'moderator'], permissions: permissionsFor('player', 'moderator') };
const admin: Viewer = { ...base, role: 'admin', capabilities: ['player', 'admin'], permissions: permissionsFor('player', 'admin') };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(findAccount).mockResolvedValue({ owner_uuid: 'other' } as Awaited<ReturnType<typeof findAccount>>);
  vi.mocked(canReadAccount).mockResolvedValue(false);
  vi.mocked(getAccountFirmId).mockResolvedValue(null);
  vi.mocked(hasFirmFinancialAccess).mockResolvedValue(false);
  vi.mocked(isFirmMember).mockResolvedValue(false);
  // The batched query models the same rule as findAccount + canReadAccount, so the
  // relationship tests below drive both paths (the SQL agreement is covered by the
  // integration suite).
  vi.mocked(findReadableAccountIds).mockImplementation(async (ids, uuid) => {
    const out: number[] = [];
    for (const id of ids) {
      const account = await findAccount(id);
      if (account && (account.owner_uuid === uuid || await canReadAccount(id, uuid))) out.push(id);
    }
    return out;
  });
});
describe('RBAC', () => {
  it('the default admin bundle contains all permissions', () => {
    for (const permission of PERMISSIONS) expect(hasPermission(admin, permission)).toBe(true);
  });
  it('anonymous has no private permissions', () => {
    for (const permission of PERMISSIONS) expect(hasPermission(anon, permission)).toBe(false);
    expect(() => requirePermission(anon, 'account.read')).toThrow(UnauthorizedError);
  });
  it('moderator can investigate but cannot administer', () => {
    expect(isStaff(moderator)).toBe(true);
    expect(() => requirePermission(moderator, 'roles.update')).toThrow(ForbiddenError);
    expect(() => requireRole(moderator, 'government')).toThrow(ForbiddenError);
    expect(auditRole(moderator as Extract<Viewer, { anon: false }>)).toBe('moderator');
  });
  it('government has no global oversight and roles combine without hierarchy', () => {
    expect(isStaff(government)).toBe(false);
    expect(hasElevatedRead(government)).toBe(false);
    expect(hasPermission({ ...moderator, capabilities: ['moderator', 'government'], permissions: permissionsFor('moderator', 'government') }, 'government.read')).toBe(true);
  });
});
describe('account RBAC + ReBAC', () => {
  it.each([player, government])('denies unrelated accounts for $role', async v => {
    expect(await canViewAccount(12, v)).toBe(false);
  });
  it('permits ownership', async () => {
    vi.mocked(findAccount).mockResolvedValue({ owner_uuid: 'u' } as Awaited<ReturnType<typeof findAccount>>);
    expect(await canViewAccount(12, player)).toBe(true);
  });
  it('permits a direct or synced group read relationship only on that account', async () => {
    vi.mocked(canReadAccount).mockImplementation(async id => id === 12);
    expect(await canViewAccount(12, player)).toBe(true);
    expect(await canViewAccount(13, player)).toBe(false);
    expect(await canViewAccount(12, anon)).toBe(false);
  });
  it('requires a linked UUID for relationships', async () => {
    expect(await canViewAccount(12, { ...player, minecraftUuid: null })).toBe(false);
    expect(canReadAccount).not.toHaveBeenCalled();
  });
  it.each([admin, moderator])('allows global investigation for $role', async v => {
    expect(await canViewAccount(12, v)).toBe(true);
  });
});
describe('transaction relationships', () => {
  beforeEach(() => {
    vi.mocked(findTransactionAccountIds).mockResolvedValue([12, 13]);
  });
  it.each([player, government])('allows a readable participant for $role without granting unrelated history', async v => {
    vi.mocked(canReadAccount).mockImplementation(async id => id === 13);
    expect(await canViewTransaction(1, v)).toBe(true);
    expect(await canViewAccount(12, v)).toBe(false);
    vi.mocked(findTransactionAccountIds).mockResolvedValue([12]);
    expect(await canViewTransaction(2, v)).toBe(false);
  });
  it('allows account ownership', async () => {
    vi.mocked(findAccount).mockResolvedValue({ owner_uuid: 'u' } as Awaited<ReturnType<typeof findAccount>>);
    expect(await canViewTransaction(1, player)).toBe(true);
  });
  it('rechecks relationships when access is revoked', async () => {
    vi.mocked(canReadAccount).mockResolvedValue(true);
    expect(await canViewTransaction(1, government)).toBe(true);
    vi.mocked(canReadAccount).mockResolvedValue(false);
    expect(await canViewTransaction(1, government)).toBe(false);
  });
  it.each([anon, { ...player, permissions: [] }, { ...player, minecraftUuid: null }])('rejects missing authentication, permission or identity', async v => {
    expect(await canViewTransaction(1, v)).toBe(false);
    expect(findTransactionAccountIds).not.toHaveBeenCalled();
  });
  it('allows transaction investigators without account grants or a linked identity', async () => {
    expect(await canViewTransaction(1, { ...player, minecraftUuid: null, permissions: ['transactions.read'] })).toBe(true);
    expect(findTransactionAccountIds).not.toHaveBeenCalled();
  });
  it('allows global account readers but does not grant the global ledger permission', async () => {
    const viewer: Viewer = { ...player, minecraftUuid: null, permissions: ['account.read.all'] };
    expect(await canViewTransaction(1, viewer)).toBe(true);
    expect(hasPermission(viewer, 'transactions.read')).toBe(false);
  });
  it('denies transactions with no participating accounts', async () => {
    vi.mocked(findTransactionAccountIds).mockResolvedValue([]);
    expect(await canViewTransaction(99, player)).toBe(false);
  });
});
describe('readableAccountIds (batched)', () => {
  it('returns only owned or related accounts, in one query', async () => {
    vi.mocked(findReadableAccountIds).mockResolvedValue([12]);
    expect(await readableAccountIds(player, [12, 13, 14])).toEqual(new Set([12]));
    expect(findReadableAccountIds).toHaveBeenCalledTimes(1);
    expect(findReadableAccountIds).toHaveBeenCalledWith([12, 13, 14], 'u');
    expect(findAccount).not.toHaveBeenCalled();
    expect(canReadAccount).not.toHaveBeenCalled();
  });
  it('agrees with canViewAccount id by id', async () => {
    vi.mocked(findAccount).mockImplementation(async id => (id === 404 ? null : { owner_uuid: id === 1 ? 'u' : 'other' }) as Awaited<ReturnType<typeof findAccount>>);
    vi.mocked(canReadAccount).mockImplementation(async id => id === 2);
    const ids = [1, 2, 3, 404];
    const batched = await readableAccountIds(player, ids);
    for (const id of ids) expect(batched.has(id)).toBe(await canViewAccount(id, player));
    expect(batched).toEqual(new Set([1, 2]));
  });
  it('global readers get every id without a lookup', async () => {
    expect(await readableAccountIds(moderator, [5, 6])).toEqual(new Set([5, 6]));
    expect(findReadableAccountIds).not.toHaveBeenCalled();
  });
  it.each([anon, { ...player, permissions: [] }, { ...player, minecraftUuid: null }])('is empty without authentication, permission or identity', async v => {
    expect(await readableAccountIds(v, [1, 2])).toEqual(new Set());
    expect(findReadableAccountIds).not.toHaveBeenCalled();
  });
  it('skips the query for an empty list', async () => {
    expect(await readableAccountIds(player, [])).toEqual(new Set());
    expect(findReadableAccountIds).not.toHaveBeenCalled();
  });
  it('canViewTransaction checks all participants in one batched query', async () => {
    vi.mocked(findTransactionAccountIds).mockResolvedValue([12, 13, 14]);
    vi.mocked(findReadableAccountIds).mockResolvedValue([14]);
    expect(await canViewTransaction(1, player)).toBe(true);
    expect(findReadableAccountIds).toHaveBeenCalledTimes(1);
  });
});
describe('firm relationships', () => {
  it('plain membership sees the roster, not finances', async () => {
    vi.mocked(isFirmMember).mockResolvedValue(true);
    expect(await canViewFirmInternals(7, player)).toBe(true);
    expect(await canViewFirmFinancials(7, player)).toBe(false);
  });
  it('proprietors and finance staff see both', async () => {
    vi.mocked(hasFirmFinancialAccess).mockResolvedValue(true);
    expect(await canViewFirmInternals(7, player)).toBe(true);
    expect(await canViewFirmFinancials(7, player)).toBe(true);
  });
  it('account membership does not expose a whole firm', async () => {
    vi.mocked(canReadAccount).mockResolvedValue(true);
    expect(await canViewFirmFinancials(7, player)).toBe(false);
    expect(await canViewFirmInternals(7, government)).toBe(false);
  });
});

describe('configurable permissions', () => {
  it('an admin role name never bypasses removed permissions', () => {
    const stripped = { ...admin, permissions: [] };
    for (const permission of PERMISSIONS) expect(hasPermission(stripped, permission)).toBe(false);
  });
  it('a relationship cannot replace a missing role permission', async () => {
    vi.mocked(findAccount).mockResolvedValue({ owner_uuid: 'u' } as Awaited<ReturnType<typeof findAccount>>);
    vi.mocked(canReadAccount).mockResolvedValue(true);
    vi.mocked(hasFirmFinancialAccess).mockResolvedValue(true);
    const restricted = { ...player, permissions: [] };
    expect(await canViewAccount(12, restricted)).toBe(false);
    expect(await canViewFirmInternals(7, restricted)).toBe(false);
    expect(await canViewFirmFinancials(7, restricted)).toBe(false);
  });
  it('custom global account access does not grant firm access or account writes', async () => {
    const custom: Viewer = { ...player, role: 'auditor', capabilities: ['auditor'], permissions: ['account.read.all'] };
    expect(await canViewAccount(12, custom)).toBe(true);
    expect(await canViewFirmFinancials(7, custom)).toBe(false);
    expect(hasPermission(custom, 'account.transfer')).toBe(false);
  });
});
