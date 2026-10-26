/** Default role display metadata only; the database registry is authoritative. */
export const CAPABILITIES = ['player', 'government', 'moderator', 'admin'] as const;
export type DefaultRole = (typeof CAPABILITIES)[number];
export type Capability = string;
export type Role = Capability;
export function isCapability(s: string): s is DefaultRole {
  return (CAPABILITIES as readonly string[]).includes(s);
}
/** Old viewer grants now mean player, never global oversight. */
export function normalizeCapability(s: string): Capability | null {
  if (s === 'viewer' || s === 'staff.audit') return 'player';
  return /^[a-z][a-z0-9_-]{0,31}$/.test(s) ? s : null;
}
export const CAPABILITY_LABELS: Record<DefaultRole, string> = {
  player: 'Player', government: 'Government', moderator: 'Moderator', admin: 'Admin',
};
export const CAPABILITY_DESCRIPTIONS: Record<DefaultRole, string> = {
  player: 'Read your own data and accounts or firms to which you have an appropriate relationship.',
  government: 'Read government records for accounts you own or have direct or group access to.',
  moderator: 'Read-only investigations across accounts, firms, transactions and sales. No administrative changes.',
  admin: 'All permissions, including access management and administrative changes.',
};
export function legacyRoleCapabilities(role: Role): Capability[] { return [role]; }
/** Display label only: roles are not an authorization hierarchy. */
export function roleFromCapabilities(caps: readonly string[]): Role {
  for (const role of ['admin', 'moderator', 'government'] as const) {
    if (caps.includes(role)) return role;
  }
  return caps.find(role => role !== 'player') ?? 'player';
}
