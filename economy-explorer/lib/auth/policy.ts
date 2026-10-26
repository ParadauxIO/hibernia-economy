import type { Viewer } from './viewer';

/** Stable action vocabulary. Role-to-permission mappings live in the database. */
export const PERMISSION_DESCRIPTIONS = {
  "player.read": "Read your own player dashboard and market activity.",
  "player.read.all": "Read any player dashboard.",
  "account.read": "Read account history through ownership or an account/firm relationship.",
  "account.read.all": "Read private history for any account.",
  "firm.read": "Read firm rosters through membership or ownership.",
  "firm.read.all": "Read rosters and roles for any firm.",
  "firm.financials.read": "Read financials for firms you own or have finance access to.",
  "firm.financials.read.all": "Read financials for any firm.",
  "government.read": "Read government records for related government accounts.",
  "government.read.all": "Read all government accounts and fines.",
  "government.audit.read": "Read who moved money in or out of, and who viewed, the government accounts in your scope.",
  "gov_units.manage": "Create government units and assign government accounts to them.",
  "transactions.read": "Read the global ledger and transaction details.",
  "market.sales.read": "Read trade-by-trade sales with customer attribution.",
  "market.figures.read": "Read seller-attributed figures and market leaderboards.",
  "account.admin.read": "Open account administration tools.",
  "account.rename": "Rename any account.",
  "account.owner.change": "Change any account owner.",
  "account.archive": "Archive or unarchive any account.",
  "account.transfer": "Transfer money between arbitrary accounts.",
  "firm.admin.read": "Open firm administration tools.",
  "firm.rename": "Rename any firm.",
  "firm.details.update": "Update any firm’s HQ and business details.",
  "firm.disband": "Disband a firm and sweep its accounts.",
  "api_keys.read": "List API keys and issuer rate limits.",
  "api_keys.revoke": "Revoke any API key.",
  "api_keys.rotate": "Force rotation of any API key.",
  "rate_limits.update": "Change issuer API rate limits.",
  "webhooks.read": "Read your own webhook subscriptions and deliveries.",
  "webhooks.manage": "Create and manage your own authorized webhook subscriptions.",
  "webhooks.read.all": "Read all webhook subscriptions.",
  "webhooks.create.all": "Register a webhook for any account.",
  "webhooks.update.all": "Enable or disable any webhook.",
  "webhooks.delete.all": "Delete any webhook.",
  "groups.read": "List access groups, role assignments, sources and members.",
  "groups.create": "Create an access group.",
  "groups.delete": "Delete an access group.",
  "groups.members.update": "Manage manual group members, limited to roles you can grant.",
  "groups.roles.update": "Assign roles to groups, limited to permissions you hold.",
  "groups.sync.update": "Change LP group sources, limited to roles you can grant.",
  "roles.read": "List role definitions, permissions and direct assignments.",
  "roles.create": "Create roles, limited to permissions you hold.",
  "roles.update": "Edit role permissions, limited to permissions you hold.",
  "roles.delete": "Delete roles and their assignments.",
  "roles.assign": "Manage direct role assignments, limited to permissions you hold.",
  "docs.admin.read": "Read administrator documentation.",
  "audit.read": "Read the full explorer audit log, including what each admin change altered."
} as const;
export type Permission = keyof typeof PERMISSION_DESCRIPTIONS;
export const PERMISSIONS = Object.keys(PERMISSION_DESCRIPTIONS) as Permission[];
export function isPermission(value: string): value is Permission {
  return Object.hasOwn(PERMISSION_DESCRIPTIONS, value);
}
/** No role-name or wildcard bypass: even admin uses its current stored grants. */
export function hasPermission(viewer: Viewer, permission: Permission): boolean {
  return !viewer.anon && viewer.permissions.includes(permission);
}
export function hasAnyPermission(viewer: Viewer, permissions: readonly Permission[]): boolean {
  return permissions.some(p => hasPermission(viewer, p));
}
