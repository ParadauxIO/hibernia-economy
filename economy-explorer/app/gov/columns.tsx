import Link from 'next/link';
import { Money, When } from '@democracycraft/design-system';
import type { Column } from '@democracycraft/design-system/table';
import type { MovementRow, OfficialRow, PayerRow } from '@/lib/sql/government';
import { AccountRef, PartyRef, PlayerRef } from './shared';

// Column sets shared by several /gov tables.

function Counterparty({ m }: { m: MovementRow }) {
  if (m.counterparty_id === null) return <span className="muted">—</span>;
  const name = m.counterparty_type === 'PERSONAL' && m.counterparty_owner_name ? m.counterparty_owner_name : m.counterparty_name;
  return <AccountRef id={m.counterparty_id} name={name} type={m.counterparty_type} />;
}

export const movementColumns: Column<MovementRow>[] = [
  { key: 'account', header: 'Account', cell: (m) => <AccountRef id={m.account_id} name={m.account_name} /> },
  { key: 'when', header: 'When', secondary: true, cell: (m) => <When value={m.settled_at} time /> },
  { key: 'counterparty', header: 'Counterparty', secondary: true, cell: (m) => <Counterparty m={m} /> },
  { key: 'memo', header: 'Description', secondary: true, cell: (m) => <><span>{m.message}</span>{m.plugin_system && <span className="dt-sub"> · {m.plugin_system}</span>}</> },
  { key: 'by', header: 'Initiated by', secondary: true, cell: (m) => <PlayerRef uuid={m.initiator_uuid} name={m.initiator_name} fallback="—" /> },
  { key: 'amount', header: 'Amount', numeric: true, cell: (m) => <Link href={`/transactions/${m.txn_id}`} prefetch={false} title={`Transaction #${m.txn_id}`}><Money value={m.amount} sign="exceptZero" tone="auto" /></Link> },
];

/** The ledger-side audit feed: the same rows with both people shown. */
export const ledgerColumns: Column<MovementRow>[] = [
  { key: 'account', header: 'Account', cell: (m) => <AccountRef id={m.account_id} name={m.account_name} /> },
  { key: 'when', header: 'When', secondary: true, cell: (m) => <When value={m.settled_at} time /> },
  { key: 'by', header: 'Initiated by', cell: (m) => <PlayerRef uuid={m.initiator_uuid} name={m.initiator_name} fallback="—" /> },
  { key: 'auth', header: 'Authorized by', secondary: true, cell: (m) => m.authorizer_uuid ? <PlayerRef uuid={m.authorizer_uuid} name={m.authorizer_name} /> : <span className="muted">—</span> },
  { key: 'counterparty', header: 'Counterparty', secondary: true, cell: (m) => <Counterparty m={m} /> },
  { key: 'memo', header: 'Description', secondary: true, optional: true, cell: (m) => m.message },
  { key: 'amount', header: 'Amount', numeric: true, cell: (m) => <Link href={`/transactions/${m.txn_id}`} prefetch={false} title={`Transaction #${m.txn_id}`}><Money value={m.amount} sign="exceptZero" tone="auto" /></Link> },
];

export function officialColumns(verb: string): Column<OfficialRow>[] {
  return [
    { key: 'initiator', header: 'Initiated by', cell: (o) => <PlayerRef uuid={o.initiator_uuid} name={o.initiator_name} fallback="—" /> },
    { key: 'authorizer', header: 'Authorized by', secondary: true, cell: (o) => o.authorizer_uuid ? <PlayerRef uuid={o.authorizer_uuid} name={o.authorizer_name} /> : <span className="muted">—</span> },
    { key: 'count', header: 'Transactions', numeric: true, secondary: true, cell: (o) => o.transactions.toLocaleString('en-US') },
    { key: 'last', header: 'Last', secondary: true, cell: (o) => <When value={o.last_at} /> },
    { key: 'total', header: verb, numeric: true, cell: (o) => <Money value={o.total} /> },
  ];
}

export const payerColumns: Column<PayerRow>[] = [
  { key: 'payer', header: 'Payer', cell: (p) => <PartyRef row={p} /> },
  { key: 'n', header: 'Payments', numeric: true, secondary: true, cell: (p) => p.payments.toLocaleString('en-US') },
  { key: 'total', header: 'Paid', numeric: true, cell: (p) => <Money value={p.total} /> },
];
