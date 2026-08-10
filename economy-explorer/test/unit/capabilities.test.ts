import { describe, it, expect } from 'vitest';
import {
  CAPABILITIES,
  CAPABILITY_LABELS,
  CAPABILITY_DESCRIPTIONS,
  legacyRoleCapabilities,
  roleFromCapabilities,
  isCapability,
  normalizeCapability,
} from '@/lib/auth/capabilities';

describe('capability metadata stays in lockstep with the vocabulary', () => {
  it('every capability has a label and a non-trivial description', () => {
    for (const c of CAPABILITIES) {
      expect(CAPABILITY_LABELS[c], `label for ${c}`).toBeTruthy();
      expect((CAPABILITY_DESCRIPTIONS[c] ?? '').length, `description for ${c}`).toBeGreaterThan(20);
    }
  });
});

describe('legacyRoleCapabilities', () => {
  it('admin implies every capability', () => {
    expect(legacyRoleCapabilities('admin')).toEqual(
      expect.arrayContaining(['admin', 'viewer', 'government']),
    );
  });

  it('government implies viewer + government but not admin', () => {
    const caps = legacyRoleCapabilities('government');
    expect(caps).toContain('viewer');
    expect(caps).toContain('government');
    expect(caps).not.toContain('admin');
  });

  it('player implies nothing', () => {
    expect(legacyRoleCapabilities('player')).toEqual([]);
  });
});

describe('roleFromCapabilities', () => {
  it('admin capability collapses to admin role', () => {
    expect(roleFromCapabilities(['admin'])).toBe('admin');
  });

  it('government without admin collapses to government role', () => {
    expect(roleFromCapabilities(['viewer', 'government'])).toBe('government');
  });

  it('viewer alone (a read-only auditor) stays player role', () => {
    // The capability grants oversight access (isStaff) without elevating the role,
    // so requireRole('government') still keeps a viewer out of government-only pages.
    expect(roleFromCapabilities(['viewer'])).toBe('player');
  });

  it('empty set is player', () => {
    expect(roleFromCapabilities([])).toBe('player');
  });
});

describe('isCapability', () => {
  it('accepts current capabilities and rejects unknown or legacy strings', () => {
    expect(isCapability('viewer')).toBe(true);
    expect(isCapability('admin')).toBe(true);
    // 'staff.audit' is a legacy alias, not a current capability — use
    // normalizeCapability to resolve it.
    expect(isCapability('staff.audit')).toBe(false);
    expect(isCapability('not-a-capability')).toBe(false);
  });
});

describe('normalizeCapability', () => {
  it('passes current capabilities through unchanged', () => {
    expect(normalizeCapability('viewer')).toBe('viewer');
    expect(normalizeCapability('admin')).toBe('admin');
    expect(normalizeCapability('government')).toBe('government');
  });

  it('resolves the legacy staff.audit alias to viewer', () => {
    expect(normalizeCapability('staff.audit')).toBe('viewer');
  });

  it('returns null for unknown strings', () => {
    expect(normalizeCapability('not-a-capability')).toBeNull();
  });
});
