import { describe, it, expect } from 'vitest';
import { isStaff, hasCapability, hasElevatedRead, auditRole } from '@/lib/auth/access';
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
const government: Viewer = { ...base, role: 'government', capabilities: ['viewer', 'government'] };
const admin: Viewer = { ...base, role: 'admin', capabilities: ['admin', 'viewer', 'government'] };
// A read-only auditor: has the viewer capability via a group but not government tier.
const docAuditor: Viewer = { ...base, role: 'player', capabilities: ['viewer'] };

describe('isStaff', () => {
  it('is true for any viewer holding the viewer capability', () => {
    expect(isStaff(admin)).toBe(true);
    expect(isStaff(government)).toBe(true);
    expect(isStaff(docAuditor)).toBe(true);
  });

  it('is false for anonymous and plain players', () => {
    expect(isStaff(anon)).toBe(false);
    expect(isStaff(player)).toBe(false);
  });
});

describe('hasCapability', () => {
  it('reflects the viewer capability set', () => {
    expect(hasCapability(admin, 'admin')).toBe(true);
    expect(hasCapability(government, 'admin')).toBe(false);
    expect(hasCapability(docAuditor, 'viewer')).toBe(true);
    expect(hasCapability(anon, 'viewer')).toBe(false);
  });
});

// The audit predicate. Regression: account-history and firm-financials reads were
// audited only for role admin/government, so the read-only `viewer` role (role
// 'player' + the viewer capability, PAR-265) read other people's data unaudited.
describe('hasElevatedRead', () => {
  it('covers admin, government, and the read-only viewer role', () => {
    expect(hasElevatedRead(admin)).toBe(true);
    expect(hasElevatedRead(government)).toBe(true);
    expect(hasElevatedRead(docAuditor)).toBe(true);
  });

  it('covers a government-tier grant that does not carry the viewer capability', () => {
    const govOnly: Viewer = { ...base, role: 'government', capabilities: ['government'] };
    expect(isStaff(govOnly)).toBe(false);
    expect(hasElevatedRead(govOnly)).toBe(true);
  });

  it('is false for anonymous and plain players', () => {
    expect(hasElevatedRead(anon)).toBe(false);
    expect(hasElevatedRead(player)).toBe(false);
  });
});

describe('auditRole', () => {
  const asAuthed = (v: Viewer) => v as Extract<Viewer, { anon: false }>;

  it('records a viewer-only actor as viewer, not player', () => {
    expect(auditRole(asAuthed(docAuditor))).toBe('viewer');
  });

  it('keeps the tier for admin / government / plain players', () => {
    expect(auditRole(asAuthed(admin))).toBe('admin');
    expect(auditRole(asAuthed(government))).toBe('government');
    expect(auditRole(asAuthed(player))).toBe('player');
  });
});
