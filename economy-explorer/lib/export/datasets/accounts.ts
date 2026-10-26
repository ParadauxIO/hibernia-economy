import 'server-only';
import { accountLabel } from '@/lib/format';
import { listAccounts, type ExplorerAccountRow } from '@/lib/sql/ledger';
import { accountsTable } from '@/lib/tables/accounts';
import { defineExport } from '../registry';

// The accounts directory (/accounts). Public data, so any signed-in viewer may
// export it. Bounded (139k rows), so it pages by offset.

defineExport<typeof accountsTable, ExplorerAccountRow, number>({
  id: 'accounts',
  title: 'Accounts',
  table: accountsTable,
  authorize: () => true,
  rows: async (state, offset, { limit }) => {
    const start = offset ?? (state.page - 1) * state.limit;
    const rows = await listAccounts({ state, limit: limit + 1, offset: start });
    return { rows: rows.slice(0, limit), next: rows.length > limit ? start + limit : null };
  },
  columns: [
    { key: 'account_id', header: 'Account ID', value: (a) => a.account_id },
    { key: 'type', header: 'Type', value: (a) => a.account_type },
    { key: 'name', header: 'Name', value: (a) => accountLabel(a) },
    { key: 'owner_name', header: 'Owner', value: (a) => a.owner_name },
    { key: 'owner_uuid', header: 'Owner UUID', value: (a) => a.owner_uuid },
    { key: 'balance', header: 'Balance', value: (a) => a.balance },
    { key: 'archived', header: 'Archived', value: (a) => (a.archived ? 'yes' : 'no') },
    { key: 'created_at', header: 'Created', value: (a) => a.created_at.toISOString() },
  ],
});
