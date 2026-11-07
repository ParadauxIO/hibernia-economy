import { PERMISSIONS, type Permission } from '@/lib/auth/policy';
export const DEFAULT_ROLE_PERMISSIONS: Record<string, readonly Permission[]> = {
  "player": [
    "player.read",
    "account.read",
    "firm.read",
    "firm.financials.read",
    "webhooks.read",
    "webhooks.manage"
  ],
  "government": [
    "government.read",
    "government.audit.read"
  ],
  "compliance": [
    "firm.financials.read",
    "firm.financials.read.all",
    "transactions.read",
    "market.sales.read"
  ],
  "moderator": [
    "player.read.all",
    "account.read.all",
    "firm.read.all",
    "firm.financials.read.all",
    "government.read.all",
    "transactions.read",
    "market.sales.read",
    "market.figures.read"
  ]
};
DEFAULT_ROLE_PERMISSIONS.admin = PERMISSIONS;
export function permissionsFor(...roles: string[]): Permission[] {
  return [...new Set(roles.flatMap(role => DEFAULT_ROLE_PERMISSIONS[role] ?? []))];
}
