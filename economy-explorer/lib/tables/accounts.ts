import { defineTable, type TableState } from './core';

/** Enum option values are lower-case in URLs; the SQL mapping turns them into the column's ENUM. */
export const ACCOUNT_TYPES = [
  { value: 'personal', label: 'Personal' },
  { value: 'business', label: 'Business' },
  { value: 'government', label: 'Government' },
  { value: 'system', label: 'System' },
] as const;

/** The accounts directory (`/accounts`). Shared by the page, the SQL mapping and the FilterBar. */
export const accountsTable = defineTable({
  id: 'accounts',
  search: ['displayName', 'ownerName', 'accountId', 'ownerUuid'],
  searchPlaceholder: 'Search accounts, owners, #id or UUID',
  filters: {
    type: { label: 'Type', kind: 'enum', options: ACCOUNT_TYPES },
    balance: { label: 'Balance', kind: 'money' },
    created: { label: 'Created', kind: 'date' },
    firm: { label: 'Firm', kind: 'entity', entity: 'firm', hint: 'Accounts a firm holds' },
    archived: { label: 'Archived', kind: 'boolean', hint: 'Closed accounts are hidden unless you ask for them' },
  },
  sorts: { name: 'Name', type: 'Type', balance: 'Balance', created: 'Created', id: 'Account number' },
  defaultSort: '-balance',
  quick: [
    { label: 'Businesses', f: ['type:in:business'] },
    { label: 'Government', f: ['type:in:government'] },
    { label: 'Balance over 10k', f: ['balance:gt:10000'] },
    { label: 'Opened in the last 30 days', f: ['created:gte:-30d'] },
  ],
  noun: { one: 'account', many: 'accounts' },
  pageSize: 50,
  maxPageSize: 200,
  legacy: { filterParams: { type: 'type' }, sortParams: { name: 'name', type: 'type', balance: 'balance', created: 'created', account_id: 'id' } },
});

export type AccountsTable = typeof accountsTable;
export type AccountsTableState = TableState<AccountsTable>;
