import 'server-only';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { exportFinesBatch, exportLedgerBatch } from '@/lib/services/government';
import { govScopeOrNull } from '@/lib/services/governmentScope';
import type { FineRow, MovementRow } from '@/lib/sql/government';
import { finesTable, type FinesTable } from '@/lib/tables/fines';
import { govLedgerExportTable } from '@/lib/tables/gov';
import { defineExport } from '../registry';

// The government workspace's exports (PAR-539). Authorization is the /gov
// pages' own: resolveGovScope (government.read or .all, plus the viewer's
// account relationships). Rows are always computed over the viewer's scope;
// an `account` chip can only narrow it, never reach outside it. The pages put
// their unit selection in that chip and their range in a date chip
// (app/gov/exports.ts), so an export selects what the page shows.

defineExport<FinesTable, FineRow, number>({
  id: 'fines',
  title: 'Fines',
  table: finesTable,
  authorize: async () => ((await govScopeOrNull())?.ids.length ?? 0) > 0,
  rows: async (state, cursor, { limit }) => {
    const scope = await govScopeOrNull();
    return scope ? exportFinesBatch(scope, state, cursor, limit) : { rows: [], next: null };
  },
  columns: [
    { key: 'fine_id', header: 'Fine', value: (f) => f.fine_id },
    { key: 'issued_at', header: 'Issued (UTC)', value: (f) => f.issued_at.toISOString() },
    { key: 'status', header: 'Status', value: (f) => (f.revoked ? 'revoked' : 'active') },
    { key: 'amount', header: 'Amount', value: (f) => f.amount },
    { key: 'player_name', header: 'Fined player', value: (f) => f.player_name },
    { key: 'player_uuid', header: 'Player UUID', value: (f) => f.player_uuid },
    { key: 'reason', header: 'Reason', value: (f) => f.reason },
    { key: 'issued_by_name', header: 'Issued by', value: (f) => f.issued_by_name },
    { key: 'issued_by_uuid', header: 'Issuer UUID', value: (f) => f.issued_by_uuid },
    { key: 'gov_account_id', header: 'Receiving account', value: (f) => f.gov_account_id },
    { key: 'gov_account_name', header: 'Receiving account name', value: (f) => f.gov_account_name },
    { key: 'txn_id', header: 'Transaction', value: (f) => f.txn_id },
    { key: 'revoked_at', header: 'Revoked (UTC)', value: (f) => f.revoked_at?.toISOString() ?? null },
  ],
});

defineExport<typeof govLedgerExportTable, MovementRow, KeysetCursor>({
  id: 'gov-transactions',
  title: 'Government transactions',
  table: govLedgerExportTable,
  // The ledger side of /gov/audit: it names who moved the money.
  authorize: async () => {
    const scope = await govScopeOrNull();
    return !!scope && scope.canAudit && scope.ids.length > 0;
  },
  rows: async (state, cursor, { limit }) => {
    const scope = await govScopeOrNull();
    return scope?.canAudit ? exportLedgerBatch(scope, state, cursor, limit) : { rows: [], next: null };
  },
  columns: [
    { key: 'txn_id', header: 'Transaction', value: (m) => m.txn_id },
    { key: 'settled_at', header: 'Settled (UTC)', value: (m) => m.settled_at.toISOString() },
    { key: 'account_id', header: 'Account', value: (m) => m.account_id },
    { key: 'account_name', header: 'Account name', value: (m) => m.account_name },
    { key: 'amount', header: 'Amount', value: (m) => m.amount },
    { key: 'message', header: 'Description', value: (m) => m.message },
    { key: 'plugin', header: 'Plugin', value: (m) => m.plugin_system },
    { key: 'initiator_name', header: 'Initiator', value: (m) => m.initiator_name },
    { key: 'initiator_uuid', header: 'Initiator UUID', value: (m) => m.initiator_uuid },
    { key: 'authorizer_name', header: 'Authorizer', value: (m) => m.authorizer_name },
    { key: 'authorizer_uuid', header: 'Authorizer UUID', value: (m) => m.authorizer_uuid },
    { key: 'counterparty_id', header: 'Counterparty', value: (m) => m.counterparty_id },
    { key: 'counterparty_name', header: 'Counterparty name', value: (m) => m.counterparty_name },
  ],
});
