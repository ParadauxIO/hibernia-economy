import { permissionsFor } from '../role-fixtures';
import { describe, it, expect } from 'vitest';
import { requireRole } from '@/lib/auth/requireRole';
import { ForbiddenError, UnauthorizedError } from '@/lib/errors';
import type { Viewer } from '@/lib/auth/viewer';

const anon: Viewer = {
  anon: true,
  role: null,
  capabilities: [], permissions: [],
  keycloakSub: null,
  minecraftUuid: null,
  minecraftName: null,
  linked: false,
};
const base = { anon: false as const, keycloakSub: 's', minecraftUuid: 'u', minecraftName: 'n', linked: true };
const player: Viewer = { ...base, role: 'player', capabilities: ['player'], permissions: permissionsFor('player') };
const government: Viewer = { ...base, role: 'government', capabilities: ['player', 'government'], permissions: permissionsFor('player', 'government') };
const admin: Viewer = { ...base, role: 'admin', capabilities: ['player', 'admin'], permissions: permissionsFor('player', 'admin') };

describe('requireRole — anonymous', () => {
  it('rejects anon with UnauthorizedError for every tier', () => {
    expect(() => requireRole(anon, 'player')).toThrow(UnauthorizedError);
    expect(() => requireRole(anon, 'government')).toThrow(UnauthorizedError);
    expect(() => requireRole(anon, 'admin')).toThrow(UnauthorizedError);
  });
});

describe('requireRole — player membership', () => {
  it('allows player, government, admin', () => {
    expect(() => requireRole(player, 'player')).not.toThrow();
    expect(() => requireRole(government, 'player')).not.toThrow();
    expect(() => requireRole(admin, 'player')).not.toThrow();
  });
});

describe('requireRole — government membership', () => {
  it('denies a plain player with ForbiddenError', () => {
    expect(() => requireRole(player, 'government')).toThrow(ForbiddenError);
    expect(() => requireRole(player, 'government')).toThrow('requires government access');
  });
  it('government membership is independent of admin', () => {
    expect(() => requireRole(government, 'government')).not.toThrow();
    expect(() => requireRole(admin, 'government')).toThrow(ForbiddenError);
  });
});

describe('requireRole — admin membership', () => {
  it('denies player and government with ForbiddenError', () => {
    expect(() => requireRole(player, 'admin')).toThrow(ForbiddenError);
    expect(() => requireRole(government, 'admin')).toThrow(ForbiddenError);
    expect(() => requireRole(government, 'admin')).toThrow('requires admin access');
  });
  it('allows admin', () => {
    expect(() => requireRole(admin, 'admin')).not.toThrow();
  });
});
