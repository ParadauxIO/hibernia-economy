import '@/components/entity/transactions.css';
import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { Money, When } from '@democracycraft/design-system';
import { DataTable, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import type { ReactNode } from 'react';
import type { FeedRow } from '@/lib/services/meFlows';
import { PartyLink } from './PartyLink';

const RouterLink: LinkComponent<AppHref> = Link;

const CATEGORY_TONE: Record<string, string> = {
  wages: '1', shop_sales: '2', shop_purchases: '3', taxes: '4', fines: '5', property: '6', business: '7',
  crime: '5', jobs: '6', transfers: '1', api: 'muted', internal: 'muted', other: 'muted',
};

/** Postings as a table: description, when, account, category, counterparty, amount. Rows open the transaction. */
export function FeedTable({ id, label, rows, showAccount = true, empty }: { id: string; label: string; rows: readonly FeedRow[]; showAccount?: boolean; empty?: ReactNode }) {
  const columns: Column<FeedRow>[] = [
    { key: 'description', header: 'Description', cell: (r) => <>{r.description}<span className="dt-sub">{` #${r.txnId}`}</span></> },
    { key: 'when', header: 'When', cell: (r) => <When value={r.at} time /> },
    ...(showAccount ? [{ key: 'account', header: 'Account', secondary: true, optional: true, cell: (r: FeedRow) => r.account.name }] : []),
    { key: 'category', header: 'Category', secondary: true, optional: true, cell: (r) => <span className="dt-cat" data-tone={CATEGORY_TONE[r.category] ?? 'muted'}>{r.categoryLabel}</span> },
    { key: 'counterparty', header: 'Counterparty', secondary: true, optional: true, cell: (r) => (r.counterparty ? <PartyLink party={r.counterparty} /> : <span className="muted">—</span>) },
    { key: 'amount', header: 'Amount', numeric: true, cell: (r) => <Money value={r.amount} sign="exceptZero" tone="auto" /> },
  ];
  return <DataTable className="transaction-table feed-transaction-table" id={id} label={label} columns={columns} rows={rows} rowKey={(r) => r.postingId}
    rowHref={(r) => `/transactions/${r.txnId}`} link={RouterLink} empty={empty} />;
}

