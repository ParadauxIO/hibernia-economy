import { describe, it, expect } from 'vitest';
import { isStaff, hasCapability } from '@/lib/auth/access';
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
