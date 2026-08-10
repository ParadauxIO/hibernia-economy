import { describe, it, expect } from 'vitest';
import { requireRole } from '@/lib/auth/requireRole';
import { ForbiddenError, UnauthorizedError } from '@/lib/errors';
import type { Viewer } from '@/lib/auth/viewer';

const anon: Viewer = {
  anon: true,
  role: null,
  capabilities: [],
  keycloakSub: null,
  minecraftUuid: null,
  minecraftName: null,
  linked: false,
};
const base = { anon: false as const, keycloakSub: 's', minecraftUuid: 'u', minecraftName: 'n', linked: true };
const player: Viewer = { ...base, role: 'player', capabilities: [] };
const government: Viewer = { ...base, role: 'government', capabilities: ['government'] };
const admin: Viewer = { ...base, role: 'admin', capabilities: ['admin'] };

describe('requireRole — anonymous', () => {
  it('rejects anon with UnauthorizedError for every tier', () => {
    expect(() => requireRole(anon, 'player')).toThrow(UnauthorizedError);
    expect(() => requireRole(anon, 'government')).toThrow(UnauthorizedError);
    expect(() => requireRole(anon, 'admin')).toThrow(UnauthorizedError);
  });
});

describe('requireRole — player tier (rank 1)', () => {
  it('allows player, government, admin', () => {
    expect(() => requireRole(player, 'player')).not.toThrow();
    expect(() => requireRole(government, 'player')).not.toThrow();
    expect(() => requireRole(admin, 'player')).not.toThrow();
  });
});

describe('requireRole — government tier (rank 2)', () => {
  it('denies a plain player with ForbiddenError', () => {
    expect(() => requireRole(player, 'government')).toThrow(ForbiddenError);
    expect(() => requireRole(player, 'government')).toThrow('requires government access');
  });
  it('allows government and admin', () => {
    expect(() => requireRole(government, 'government')).not.toThrow();
    expect(() => requireRole(admin, 'government')).not.toThrow();
  });
});

describe('requireRole — admin tier (rank 3)', () => {
  it('denies player and government with ForbiddenError', () => {
    expect(() => requireRole(player, 'admin')).toThrow(ForbiddenError);
    expect(() => requireRole(government, 'admin')).toThrow(ForbiddenError);
    expect(() => requireRole(government, 'admin')).toThrow('requires admin access');
  });
  it('allows admin', () => {
    expect(() => requireRole(admin, 'admin')).not.toThrow();
  });
});
