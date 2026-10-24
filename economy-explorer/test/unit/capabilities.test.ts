import { describe, it, expect } from 'vitest';
import { CAPABILITIES, CAPABILITY_LABELS, CAPABILITY_DESCRIPTIONS, legacyRoleCapabilities, roleFromCapabilities, isCapability, normalizeCapability } from '@/lib/auth/capabilities';
describe('role vocabulary and migration', () => {
  it('defines four independently grantable default roles', () => {
    expect(CAPABILITIES).toEqual(['player', 'government', 'moderator', 'admin']);
    for (const role of CAPABILITIES) {
      expect(CAPABILITY_LABELS[role]).toBeTruthy();
      expect(CAPABILITY_DESCRIPTIONS[role].length).toBeGreaterThan(20);
      expect(legacyRoleCapabilities(role)).toEqual([role]);
      expect(roleFromCapabilities([role])).toBe(role);
    }
  });
  it('migrates both old viewer spellings to player without oversight', () => {
    expect(normalizeCapability('viewer')).toBe('player');
    expect(normalizeCapability('staff.audit')).toBe('player');
    expect(isCapability('viewer')).toBe(false);
    expect(normalizeCapability('custom_role')).toBe('custom_role');
    expect(normalizeCapability('invalid.role')).toBeNull();
  });
  it('display priority does not discard combined grants', () => {
    expect(roleFromCapabilities(['moderator', 'government'])).toBe('moderator');
    expect(roleFromCapabilities(['admin', 'government'])).toBe('admin');
    expect(roleFromCapabilities([])).toBe('player');
  });
});
