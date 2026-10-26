import Link from 'next/link';
import type { Route } from 'next';
import { MiniSpark, Money, PageHeader, Tag } from '@democracycraft/design-system';
import { DataTable, TableEmpty, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { buildMetadata } from '@/lib/metadata';
import { parseMoney } from '@/lib/money';
import { getGovAccounts, type GovAccountView } from '@/lib/services/government';
import { cleared, filterText, parseTableState, tableHref, withQuery, withSort, withoutFilter, type FilterState, type TableState } from '@/lib/tables/core';
import { govAccountsTable } from '@/lib/tables/gov';
import { AccountRef, Freshness, GovControls, ScopeNote, govPage, isGate, type SearchParams } from '../shared';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government accounts', description: 'Government accounts in your scope on {server}.', path: '/gov/accounts' });
}

const TABLE_ID = 'gov-accounts-table';
const RouterLink: LinkComponent = Link;

const columns: Column<GovAccountView>[] = [
  { key: 'name', header: 'Account', sort: 'name', cell: (a) => <AccountRef id={a.accountId} name={a.displayName} /> },
  { key: 'unit', header: 'Unit', sort: 'unit', secondary: true, optional: true, cell: (a) => a.unit ? a.unit.name : <Tag>Unassigned</Tag> },
  { key: 'trend', header: 'Last 30 days', secondary: true, optional: true, cell: (a) => <MiniSpark values={a.spark} width={96} height={24} fill={false} /> },
  { key: 'net', header: '30-day net', sort: 'net', numeric: true, cell: (a) => <Money value={a.net30} sign="exceptZero" tone="auto" /> },
  { key: 'balance', header: 'Balance', sort: 'balance', numeric: true, cell: (a) => <Money value={a.balance} /> },
];

function compareCents(a: string, b: string): number {
  const x = parseMoney(a);
  const y = parseMoney(b);
  return x < y ? -1 : x > y ? 1 : 0;
}

function matches(a: GovAccountView, f: FilterState): boolean {
  if (f.field === 'assigned') return (a.unit !== null) === (f.values[0] === 'true');
  const value = f.field === 'net' ? a.net30 : a.balance;
  const [lo = '0', hi = lo] = f.values;
  const c = compareCents(value, lo);
  switch (f.op) {
    case 'gt': return c > 0;
    case 'gte': return c >= 0;
    case 'lt': return c < 0;
    case 'lte': return c <= 0;
    case 'eq': return c === 0;
    case 'between': return c >= 0 && compareCents(value, hi) <= 0;
    default: return true;
  }
}

/** The list is every in-scope account (a few dozen), so the table state is applied here rather than in SQL. */
function applyState(rows: GovAccountView[], state: TableState<typeof govAccountsTable>): GovAccountView[] {
  const q = state.q.toLowerCase().replace(/^#/, '');
  let out = rows.filter((a) => !q || (a.displayName ?? '').toLowerCase().includes(q) || String(a.accountId) === q);
  for (const f of state.filters) out = out.filter((a) => matches(a, f));
  const dir = state.sort.dir === 'asc' ? 1 : -1;
  const key = state.sort.field;
  return out.sort((a, b) => dir * (key === 'balance' ? compareCents(a.balance, b.balance)
    : key === 'net' ? compareCents(a.net30, b.net30)
      : key === 'unit' ? (a.unit?.name ?? '~').localeCompare(b.unit?.name ?? '~')
        : (a.displayName ?? '').localeCompare(b.displayName ?? '')) || a.accountId - b.accountId);
}

export default async function GovAccountsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const sp = await searchParams;
  const page = await govPage(sp, '/gov/accounts');
  if (isGate(page)) return page.gate;
  const state = parseTableState(sp, govAccountsTable);
  const { rows, coverage } = await getGovAccounts(page.scope, page.selection.ids);
  const shown = applyState(rows, state);
  const href = (s: TableState) => tableHref('/gov/accounts', s, govAccountsTable, page.extra);
  const active = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, govAccountsTable, { currency: CURRENCY_SYMBOL }), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  return <>
    <PageHeader title="Accounts" subtitle={`${page.selection.label} · balances as the ledger has them`} />
    <GovControls page={page} />
    <ScopeNote page={page} />
    <p className="muted small"><Freshness coverage={coverage} /></p>
    <FilterBar table={govAccountsTable} total={shown.length} columns={columnOptions(columns)} tableId={TABLE_ID} currency={CURRENCY_SYMBOL} />
    <DataTable id={TABLE_ID} label="Government accounts" columns={columns} rows={shown} rowKey={(a) => a.accountId}
      sort={state.sort} sortHref={(field, dir) => href(withSort(state, field, dir, govAccountsTable))} link={RouterLink}
      empty={<TableEmpty noun="accounts" filters={active} clearHref={href(cleared(state))} link={RouterLink} />} />
    <p className="muted small">Each account links to its page. <Link href={'/gov' as Route}>Back to the overview</Link>.</p>
  </>;
}
