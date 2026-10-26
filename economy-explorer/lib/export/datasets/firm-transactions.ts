import 'server-only';
import { canViewFirmFinancials, findAccount } from '@/lib/auth/access';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { exportAccountBatch, type TxnView } from '@/lib/services/account';
import { listFirmAccounts } from '@/lib/sql/firm';
import { firmTxnsExportTable, type AccountTxnsState, type FirmTxnsExportState } from '@/lib/tables/accountTxns';
import { defineExport } from '../registry';
import { TXN_COLUMNS } from './account-transactions';

// A firm's transactions across its accounts (the Financials tab), for the
// firm's finance staff: the page's check is canViewFirmFinancials. Accounts are
// exported one after another, each walked down its own (account_id, txn_id)
// index, so no batch sorts the firm's whole history.

type FirmRow = TxnView & { accountId: number; accountName: string };
interface FirmCursor { index: number; at: KeysetCursor | null }

function exportFirmId(state: FirmTxnsExportState): number | null {
  const ids = state.filters.filter((f) => f.field === 'firm').flatMap((f) => f.values);
  return ids.length === 1 && /^[1-9]\d{0,9}$/.test(ids[0]!) ? Number(ids[0]) : null;
}

defineExport<typeof firmTxnsExportTable, FirmRow, FirmCursor>({
  id: 'firm-transactions',
  title: 'Firm transactions',
  table: firmTxnsExportTable,
  authorize: async (viewer, state) => {
    const id = exportFirmId(state);
    return id !== null && (await canViewFirmFinancials(id, viewer));
  },
  rows: async (state, cursor, { limit }) => {
    const firmId = exportFirmId(state);
    if (firmId === null) return { rows: [], next: null };
    const accounts = (await listFirmAccounts(firmId)).sort((a, b) => a.account_id - b.account_id);
    const history = { ...state, filters: state.filters.filter((f) => f.field !== 'firm') } as unknown as AccountTxnsState;
    for (let pos = cursor ?? { index: 0, at: null }; pos.index < accounts.length; pos = { index: pos.index + 1, at: null }) {
      const account = await findAccount(accounts[pos.index]!.account_id);
      if (!account) continue;
      const batch = await exportAccountBatch(account, firmId, history, pos.at, limit);
      if (batch.rows.length === 0) continue;
      const name = account.display_name ?? `Account #${account.account_id}`;
      const next = batch.next ? { index: pos.index, at: batch.next } : pos.index + 1 < accounts.length ? { index: pos.index + 1, at: null } : null;
      return { rows: batch.rows.map((r) => ({ ...r, accountId: account.account_id, accountName: name })), next };
    }
    return { rows: [], next: null };
  },
  columns: [
    { key: 'account_id', header: 'Account', value: (t) => t.accountId },
    { key: 'account', header: 'Account name', value: (t) => t.accountName },
    ...TXN_COLUMNS,
  ],
});
