import { defineTable, type TableState } from './core';

/**
 * Every action explorer_audit records (V45 backfills older rows to these).
 * URL values can't contain dots, so `value` is the action with `.` → `-`;
 * lib/sql/audit.ts maps it back.
 */
export const AUDIT_ACTIONS = [
  { action: 'view', label: 'Viewed' },
  { action: 'relationship.read', label: 'Read through a relationship' },
  { action: 'account.transfer', label: 'Account: transfer' },
  { action: 'account.rename', label: 'Account: rename' },
  { action: 'account.owner.change', label: 'Account: change owner' },
  { action: 'account.archive', label: 'Account: archive' },
  { action: 'account.unarchive', label: 'Account: unarchive' },
  { action: 'firm.rename', label: 'Firm: rename' },
  { action: 'firm.details.update', label: 'Firm: update details' },
  { action: 'firm.disband', label: 'Firm: disband' },
  { action: 'api_key.revoke', label: 'API key: revoke' },
  { action: 'api_key.rotate', label: 'API key: rotate' },
  { action: 'rate_limit.update', label: 'Rate limit: change' },
  { action: 'webhook.create', label: 'Webhook: create' },
  { action: 'webhook.active', label: 'Webhook: pause or enable' },
  { action: 'webhook.url', label: 'Webhook: change URL' },
  { action: 'webhook.secret.rotate', label: 'Webhook: rotate secret' },
  { action: 'webhook.delete', label: 'Webhook: delete' },
  { action: 'group.create', label: 'Group: create' },
  { action: 'group.delete', label: 'Group: delete' },
  { action: 'group.roles.update', label: 'Group: change roles' },
  { action: 'group.sync.update', label: 'Group: change LuckPerms source' },
  { action: 'group.source.update', label: 'Group: change firm or region source' },
  { action: 'group.member.add', label: 'Group: add member' },
  { action: 'group.member.remove', label: 'Group: remove member' },
  { action: 'role.create', label: 'Role: create' },
  { action: 'role.update', label: 'Role: change permissions' },
  { action: 'role.delete', label: 'Role: delete' },
  { action: 'role.assign', label: 'Role: assign' },
  { action: 'role.unassign', label: 'Role: remove assignment' },
  { action: 'other', label: 'Other' },
] as const;

export type AuditAction = (typeof AUDIT_ACTIONS)[number]['action'];

export const actionValue = (action: string): string => action.replaceAll('.', '-');

export const AUDIT_TARGET_TYPES = [
  { value: 'account', label: 'Account' },
  { value: 'firm', label: 'Firm' },
  { value: 'player', label: 'Player' },
  { value: 'transaction', label: 'Transaction' },
  { value: 'chestshop', label: 'ChestShop' },
  { value: 'group', label: 'Group' },
  { value: 'role', label: 'Role' },
  { value: 'api_key', label: 'API key' },
  { value: 'webhook', label: 'Webhook' },
  { value: 'global', label: 'Page or list' },
] as const;

/** The admin audit log (`/admin/audit`). An unbounded feed, newest first, cursor-paged. */
export const auditTable = defineTable({
  id: 'audit',
  search: ['actorName', 'targetId', 'path'],
  searchPlaceholder: 'Search actor name, target id or path',
  filters: {
    actor: { label: 'Actor', kind: 'entity', entity: 'player', hint: 'Who did it' },
    action: { label: 'Action', kind: 'enum', options: AUDIT_ACTIONS.map((a) => ({ value: actionValue(a.action), label: a.label })) },
    target: { label: 'Target type', kind: 'enum', options: AUDIT_TARGET_TYPES },
    targetId: { label: 'Target id', kind: 'text', hint: 'Account number, firm id, player UUID…' },
    at: { label: 'Time', kind: 'date' },
  },
  sorts: { at: 'Time' },
  defaultSort: '-at',
  quick: [
    { label: 'Admin changes', f: ['action:in:account-transfer,account-rename,account-owner-change,account-archive,account-unarchive,firm-rename,firm-details-update,firm-disband,api_key-revoke,api_key-rotate,rate_limit-update'] },
    { label: 'Access changes', f: ['action:in:group-create,group-delete,group-roles-update,group-sync-update,group-source-update,group-member-add,group-member-remove,role-create,role-update,role-delete,role-assign,role-unassign'] },
    { label: 'Relationship reads', f: ['action:in:relationship-read'] },
    { label: 'Last 24 hours', f: ['at:gte:-1d'] },
  ],
  pagination: 'cursor',
  pageSize: 50,
  maxPageSize: 200,
  noun: { one: 'entry', many: 'entries' },
});

export type AuditTable = typeof auditTable;
export type AuditTableState = TableState<AuditTable>;
