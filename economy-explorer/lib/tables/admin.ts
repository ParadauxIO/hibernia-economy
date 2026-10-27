// Table schemas for the admin lists (spec §5.5, §12). /admin/accounts reuses
// the public directory's `accountsTable` (lib/tables/accounts.ts) rather than
// declaring its own.

import { defineTable, type TableState } from './core';

/** `/admin/players`: everyone in economy_players. */
export const adminPlayersTable = defineTable({
  id: 'admin-players',
  search: ['name', 'uuid'],
  searchPlaceholder: 'Search players by name or UUID',
  filters: {
    firstSeen: { label: 'First seen', kind: 'date' },
    lastSeen: { label: 'Last seen', kind: 'date' },
  },
  sorts: { name: 'Name', lastSeen: 'Last seen', firstSeen: 'First seen' },
  defaultSort: 'name',
  quick: [{ label: 'Seen this week', f: ['lastSeen:gte:-7d'] }],
  noun: { one: 'player', many: 'players' },
  pageSize: 50,
});

/** `/admin/webhooks`: every webhook subscription. */
export const adminWebhooksTable = defineTable({
  id: 'admin-webhooks',
  search: ['url', 'ownerName', 'accountName', 'firmName', 'id'],
  searchPlaceholder: 'Search URL, owner, account or firm, or #id',
  filters: {
    scope: { label: 'Scope', kind: 'enum', options: [{ value: 'account', label: 'Account' }, { value: 'firm', label: 'Firm' }] },
    status: {
      label: 'Status',
      kind: 'enum',
      options: [{ value: 'active', label: 'Active' }, { value: 'paused', label: 'Paused' }, { value: 'disabled', label: 'Auto-disabled' }],
    },
    source: { label: 'Created through', kind: 'enum', options: [{ value: 'explorer', label: 'Explorer' }, { value: 'apikey', label: 'API key' }] },
    failures: { label: 'Failures in a row', kind: 'number', integer: true },
    created: { label: 'Created', kind: 'date' },
  },
  sorts: { id: 'Number', created: 'Created', failures: 'Failures' },
  defaultSort: '-id',
  quick: [
    { label: 'Auto-disabled', f: ['status:in:disabled'] },
    { label: 'Failing', f: ['failures:gt:0'] },
  ],
  noun: { one: 'webhook', many: 'webhooks' },
  pageSize: 50,
  maxPageSize: 200,
  legacy: { filterParams: { scope: 'scope', status: 'status', source: 'source' } },
});

/** `/admin/api-keys`: every API key, with issuer limits. */
export const adminApiKeysTable = defineTable({
  id: 'admin-api-keys',
  search: ['ownerName', 'accountName', 'firmName', 'id'],
  searchPlaceholder: 'Search owner, account or firm, or #key',
  filters: {
    type: {
      label: 'Type',
      kind: 'enum',
      options: [
        { value: 'personal', label: 'Personal' },
        { value: 'business', label: 'Business' },
        { value: 'government', label: 'Government' },
        { value: 'service', label: 'Service' },
      ],
    },
    status: { label: 'Status', kind: 'enum', options: [{ value: 'active', label: 'Active' }, { value: 'revoked', label: 'Revoked' }] },
    owner: { label: 'Owner', kind: 'entity', entity: 'player' },
    issued: { label: 'Issued', kind: 'date' },
    expires: { label: 'Expires', kind: 'date' },
  },
  sorts: { issued: 'Issued', expires: 'Expires', id: 'Key number' },
  defaultSort: '-issued',
  quick: [
    { label: 'Active', f: ['status:in:active'] },
    { label: 'Revoked', f: ['status:in:revoked'] },
    { label: 'Issued in the last 30 days', f: ['issued:gte:-30d'] },
  ],
  noun: { one: 'key', many: 'keys' },
  pageSize: 50,
  maxPageSize: 200,
});

/** `/admin/groups`: explorer access groups. */
export const adminGroupsTable = defineTable({
  id: 'admin-groups',
  search: ['name', 'description', 'luckpermsNode'],
  searchPlaceholder: 'Search groups or LuckPerms nodes',
  filters: {
    source: {
      label: 'Membership source',
      kind: 'enum',
      options: [
        { value: 'manual', label: 'Manual only' },
        { value: 'luckperms', label: 'LuckPerms' },
        { value: 'business', label: 'Firm' },
        { value: 'realty', label: 'Region' },
      ],
    },
    role: { label: 'Grants role', kind: 'text', hint: 'A role key, e.g. compliance' },
  },
  sorts: { name: 'Name', members: 'Members' },
  defaultSort: 'name',
  noun: { one: 'group', many: 'groups' },
  pageSize: 100,
});

/** `/admin/roles`: role definitions. */
export const adminRolesTable = defineTable({
  id: 'admin-roles',
  search: ['key', 'name', 'description'],
  searchPlaceholder: 'Search roles',
  filters: {
    permission: { label: 'Has permission', kind: 'text', hint: 'A permission key, e.g. audit.read' },
  },
  sorts: { name: 'Name', permissions: 'Permissions', members: 'Direct members' },
  defaultSort: 'name',
  noun: { one: 'role', many: 'roles' },
  pageSize: 100,
});

/** `/admin/role-grants`: one row per role source per user. */
export const adminRoleGrantsTable = defineTable({
  id: 'admin-role-grants',
  search: ['name', 'uuid', 'group', 'selector'],
  searchPlaceholder: 'Search user, UUID, group or source node',
  filters: {
    role: { label: 'Role key', kind: 'text' },
    source: {
      label: 'Source',
      kind: 'enum',
      options: [
        { value: 'baseline', label: 'Player baseline' },
        { value: 'direct', label: 'Direct' },
        { value: 'manual', label: 'Manual group' },
        { value: 'luckperms', label: 'LuckPerms' },
        { value: 'business', label: 'Firm' },
        { value: 'realty', label: 'Region' },
      ],
    },
    status: { label: 'Status', kind: 'enum', options: [{ value: 'active', label: 'Active' }, { value: 'inactive', label: 'Inactive' }] },
  },
  sorts: { name: 'User', role: 'Role', source: 'Source' },
  defaultSort: 'name',
  quick: [{ label: 'Group grants', f: ['source:in:manual,luckperms,business,realty'] }, { label: 'Inactive', f: ['status:in:inactive'] }],
  noun: { one: 'grant', many: 'grants' },
  pageSize: 50,
  maxPageSize: 200,
  legacy: { filterParams: { source: 'source', status: 'status', role: 'role' } },
});

export type AdminPlayersState = TableState<typeof adminPlayersTable>;
export type AdminWebhooksState = TableState<typeof adminWebhooksTable>;
export type AdminApiKeysState = TableState<typeof adminApiKeysTable>;
export type AdminGroupsState = TableState<typeof adminGroupsTable>;
export type AdminRolesState = TableState<typeof adminRolesTable>;
export type AdminRoleGrantsState = TableState<typeof adminRoleGrantsTable>;

/** Members are paged in SQL, including groups synced from LuckPerms default. */
export const adminGroupMembersTable = defineTable({
  id: 'admin-group-members',
  search: ['name', 'uuid'],
  searchPlaceholder: 'Search members by name or UUID',
  filters: {
    source: { label: 'Source', kind: 'enum', options: [
      { value: 'manual', label: 'Manual' }, { value: 'luckperms', label: 'LuckPerms' },
      { value: 'business', label: 'Firm' }, { value: 'realty', label: 'Region' },
    ] },
  },
  sorts: { name: 'Player' },
  defaultSort: 'name',
  noun: { one: 'membership', many: 'memberships' },
  pageSize: 50,
  maxPageSize: 200,
});
export type AdminGroupMembersState = TableState<typeof adminGroupMembersTable>;

export const adminRoleMembersTable = defineTable({
  id: 'admin-role-members', search: ['name', 'uuid'],
  searchPlaceholder: 'Search direct members by name or UUID',
  filters: {}, sorts: { name: 'Player' }, defaultSort: 'name',
  noun: { one: 'direct member', many: 'direct members' }, pageSize: 50, maxPageSize: 200,
});
export type AdminRoleMembersState = TableState<typeof adminRoleMembersTable>;
