import type { Viewer } from './viewer';
import { hasPermission, type Permission } from './policy';

export const ADMIN_TOOLS: { href: string; title: string; permission: Permission; desc: string }[] = [
  { href: '/admin/players', title: 'Players', permission: 'player.read.all', desc: 'Inspect player dashboards within your account-read permissions.' },
  { href: '/admin/accounts', title: 'Accounts', permission: 'account.admin.read', desc: 'Account administration; each change requires its own permission.' },
  { href: '/transactions', title: 'Transactions', permission: 'transactions.read', desc: 'Global ledger and transaction details.' },
  { href: '/admin/api-keys', title: 'API keys', permission: 'api_keys.read', desc: 'API keys and issuer limits; reading does not grant changes.' },
  { href: '/admin/groups', title: 'Groups', permission: 'groups.read', desc: 'Manual and LP-backed groups and role assignments.' },
  { href: '/admin/roles', title: 'Roles', permission: 'roles.read', desc: 'Create roles, configure permissions and manage direct assignments.' },
  { href: '/admin/firms', title: 'Firms', permission: 'firm.admin.read', desc: 'Firm administration; each change requires its own permission.' },
  { href: '/admin/webhooks', title: 'Webhooks', permission: 'webhooks.read.all', desc: 'Inspect and manage subscriptions according to your permissions.' },
];
export function canAccessAdmin(viewer: Viewer): boolean {
  return ADMIN_TOOLS.some(tool => hasPermission(viewer, tool.permission));
}
