import type { AppHref } from '@/lib/routes';
import type { Viewer } from './viewer';
import { hasPermission, type Permission } from './policy';

export interface AdminTool {
  href: AppHref;
  title: string;
  /** The permission that opens the tool. */
  permission: Permission;
  /** Further permissions the page also checks; the tool is listed only when all are held. */
  alsoRequires?: readonly Permission[];
  desc: string;
  /** Sidebar group on /admin. */
  group: 'Economy' | 'Access' | 'Integrations' | 'Oversight';
}

export const ADMIN_TOOLS: AdminTool[] = [
  { href: '/admin/players', title: 'Players', permission: 'player.read.all', desc: 'Look up a player and open their personal dashboard.', group: 'Economy' },
  { href: '/admin/accounts', title: 'Accounts', permission: 'account.admin.read', desc: 'Move money, rename, change owner, archive. Each change needs its own permission.', group: 'Economy' },
  { href: '/admin/firms', title: 'Firms', permission: 'firm.admin.read', desc: 'Rename, update details or disband a firm.', group: 'Economy' },
  { href: '/transactions', title: 'Transactions', permission: 'transactions.read', desc: 'The global ledger and transaction details.', group: 'Economy' },
  { href: '/admin/groups', title: 'Groups', permission: 'groups.read', desc: 'Manual, LuckPerms, firm and region groups, and the roles they grant.', group: 'Access' },
  { href: '/admin/roles', title: 'Roles', permission: 'roles.read', desc: 'Role definitions, their permissions and direct assignments.', group: 'Access' },
  { href: '/admin/role-grants', title: 'Role grants', permission: 'roles.read', alsoRequires: ['groups.read'], desc: 'Every role each user holds, and where it comes from.', group: 'Access' },
  { href: '/admin/api-keys', title: 'API keys', permission: 'api_keys.read', desc: 'API keys and issuer rate limits.', group: 'Integrations' },
  { href: '/admin/webhooks', title: 'Webhooks', permission: 'webhooks.read.all', desc: 'Every transaction-feed webhook.', group: 'Integrations' },
  { href: '/admin/audit', title: 'Audit log', permission: 'audit.read', desc: 'Who viewed or changed what, with the values before and after.', group: 'Oversight' },
  { href: '/admin/gov-units', title: 'Government units', permission: 'gov_units.manage', desc: 'Group government accounts into units for the government workspace.', group: 'Oversight' },
];

/** True when the viewer holds every permission the tool's page checks. */
export function canUseAdminTool(viewer: Pick<Viewer, 'anon' | 'permissions'>, tool: AdminTool): boolean {
  return hasPermission(viewer as Viewer, tool.permission) && (tool.alsoRequires ?? []).every((p) => hasPermission(viewer as Viewer, p));
}

/** The tools this viewer can open, in sidebar order. */
export function adminToolsFor(viewer: Pick<Viewer, 'anon' | 'permissions'>): AdminTool[] {
  return ADMIN_TOOLS.filter((tool) => canUseAdminTool(viewer, tool));
}

export function canAccessAdmin(viewer: Viewer): boolean {
  return adminToolsFor(viewer).length > 0;
}
