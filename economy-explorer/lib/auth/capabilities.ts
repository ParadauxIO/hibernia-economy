import 'server-only';

/**
 * The explorer's capability vocabulary. Groups grant capabilities (see
 * lib/sql/group.ts), and a viewer's effective set is the union across every
 * group they belong to plus their legacy explorer_role grants. Gates check
 * capabilities via hasCapability() in lib/auth/access.ts.
 *
 * This list is the single source of truth the admin tool offers when editing a
 * group. The reconciliation plugin deals only in membership, never capabilities,
 * so this vocabulary stays explorer-local.
 *
 * `viewer` is the user-facing read-only financial-oversight role (PAR-265). It
 * was originally shipped under the internal name `staff.audit` (PAR-98); that
 * string is now a legacy alias — see CAPABILITY_ALIASES / normalizeCapability.
 */
export const CAPABILITIES = ['admin', 'viewer', 'government'] as const;

export type Capability = (typeof CAPABILITIES)[number];

/**
 * Capability strings that have since been renamed. Group-capability rows written
 * before the rename (and any production row not yet migrated by Flyway V21) still
 * resolve to the current vocabulary, so a viewer's access never silently
 * regresses. `staff.audit` was the internal name for what is now `viewer`.
 */
const CAPABILITY_ALIASES: Record<string, Capability> = {
  'staff.audit': 'viewer',
};

export function isCapability(s: string): s is Capability {
  return (CAPABILITIES as readonly string[]).includes(s);
}

/**
 * Map a stored capability string onto the current vocabulary (resolving legacy
 * aliases), or null if it isn't a capability the explorer recognizes. This is
 * the seam every read of a persisted capability should pass through.
 */
export function normalizeCapability(s: string): Capability | null {
  if (isCapability(s)) return s;
  return CAPABILITY_ALIASES[s] ?? null;
}

/** Short human label for a capability, for the admin UI. */
export const CAPABILITY_LABELS: Record<Capability, string> = {
  admin: 'Admin',
  viewer: 'Viewer',
  government: 'Government tier',
};

/**
 * Plain-language explanation of what each capability unlocks, shown next to the
 * checkbox on the group permissions screen. Keep these accurate to the gates in
 * lib/auth/access.ts and the per-page checks.
 */
export const CAPABILITY_DESCRIPTIONS: Record<Capability, string> = {
  admin:
    'Full control of the admin surface — manage access groups and their members, view and revoke API keys, and read the audit log. Implies everything Viewer and Government tier grant.',
  viewer:
    'Read-only financial oversight: see any firm or player’s balances, transactions, and per-entity drilldown (ChestShop sales, volume, quantities), including the cross-firm leaderboards. Does not include the trade-by-trade customer-attribution sales feed, the admin console, or the ability to change anything.',
  government:
    'Government tier: the government dashboard and fines, plus the trade-by-trade ChestShop sales feed (who bought from whom).',
};

/**
 * Legacy explorer_role → capabilities, so the existing flat admin/government
 * grants keep working unchanged while the group layer rolls out. Mirrors the
 * effective access each role had before capabilities existed: admin implied
 * everything; government implied viewer (financial-oversight) visibility.
 */
export function legacyRoleCapabilities(role: 'admin' | 'government' | 'player'): Capability[] {
  switch (role) {
    case 'admin':
      return ['admin', 'viewer', 'government'];
    case 'government':
      return ['viewer', 'government'];
    default:
      return [];
  }
}

/**
 * Collapse a capability set back to the legacy single-role enum so existing
 * viewer.role / requireRole checks are untouched. admin > government > player.
 */
export function roleFromCapabilities(caps: readonly string[]): 'admin' | 'government' | 'player' {
  if (caps.includes('admin')) return 'admin';
  if (caps.includes('government')) return 'government';
  return 'player';
}
