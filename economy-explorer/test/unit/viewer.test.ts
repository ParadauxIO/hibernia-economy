import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getViewer, SEEDED_PLAYER_PERMISSIONS } from '@/lib/auth/viewer';
import { auth } from '@/lib/auth/authjs';
import { findIdentityBySub } from '@/lib/sql/identity';
import { permissionsForRoles, resolveAuthorization } from '@/lib/sql/role';
import { DEFAULT_ROLE_PERMISSIONS } from '../role-fixtures';

vi.mock('next/headers', () => ({ cookies: vi.fn(async () => ({ get: () => undefined })) }));
vi.mock('@/lib/auth/authjs', () => ({ auth: vi.fn() }));
vi.mock('@/lib/sql/identity', () => ({ findIdentityBySub: vi.fn(), upsertIdentity: vi.fn() }));
vi.mock('@/lib/sql/role', () => ({ resolveAuthorization: vi.fn(), permissionsForRoles: vi.fn() }));

const session = { keycloakSub: 'sub-1', minecraftUuid: 'uuid-1', minecraftName: 'Steve' };

beforeEach(() => {
  vi.resetAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.mocked(auth).mockResolvedValue(session as never);
  vi.mocked(findIdentityBySub).mockResolvedValue({ playerUuid: 'uuid-1', minecraftName: 'Steve' } as never);
});

describe('getViewer fail-soft', () => {
  it('resolves roles and permissions normally', async () => {
    vi.mocked(resolveAuthorization).mockResolvedValue({ roles: ['player', 'government'], permissions: ['player.read', 'government.read'] });
    const v = await getViewer();
    expect(v.anon).toBe(false);
    expect(v.permissions).toEqual(['player.read', 'government.read']);
    expect(permissionsForRoles).not.toHaveBeenCalled();
  });

  it('degrades to the live player baseline when role resolution fails', async () => {
    vi.mocked(resolveAuthorization).mockRejectedValue(new Error('max_statement_time exceeded'));
    vi.mocked(permissionsForRoles).mockResolvedValue(['player.read', 'account.read']);
    const v = await getViewer();
    expect(v).toMatchObject({ anon: false, role: 'player', linked: false, minecraftUuid: 'uuid-1', capabilities: ['player'] });
    expect(v.permissions).toEqual(['player.read', 'account.read']);
    expect(permissionsForRoles).toHaveBeenCalledWith(['player']);
  });

  it('falls back to the seeded player permissions when the database is down', async () => {
    vi.mocked(findIdentityBySub).mockRejectedValue(new Error('ECONNREFUSED'));
    vi.mocked(permissionsForRoles).mockRejectedValue(new Error('ECONNREFUSED'));
    const v = await getViewer();
    expect(v.permissions).toEqual(SEEDED_PLAYER_PERMISSIONS);
    expect(v.permissions.length).toBeGreaterThan(0);
  });

  it('the seeded fallback is exactly the V31 player role and nothing elevated', () => {
    expect([...SEEDED_PLAYER_PERMISSIONS].sort()).toEqual([...DEFAULT_ROLE_PERMISSIONS.player!].sort());
    for (const p of SEEDED_PLAYER_PERMISSIONS) expect(p.endsWith('.all')).toBe(false);
  });

  it('stays anonymous without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never);
    expect((await getViewer()).anon).toBe(true);
  });
});
