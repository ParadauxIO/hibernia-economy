import Link from 'next/link';
import type { Route } from 'next';
import { PageHeader } from '@democracycraft/design-system';
import { CursorPager } from '@democracycraft/design-system/next';
import { TableEmpty, type LinkComponent } from '@democracycraft/design-system/table';
import { ExportMenuForViewer } from '@/components/ExportMenuForViewer';
import { FilterBar } from '@/components/FilterBar';
import { getViewer } from '@/lib/auth/viewer';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { exportHrefs } from '@/lib/export/href';
import { buildMetadata } from '@/lib/metadata';
import { meGate } from '@/lib/services/me';
import { getMyTransactions, SEARCH_WINDOW_DAYS } from '@/lib/services/meTransactions';
import { cleared, filterText, parseTableState, tableHref, withoutFilter, withQuery, type TableState } from '@/lib/tables/core';
import { meTransactionsTable } from '@/lib/tables/me';
import { FeedTable } from '../_components/FeedTable';
import { MeGateView } from '../_components/MeGateView';
import { searchMyAccountsAction } from './actions';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'My transactions', description: 'Your full {server} transaction history, searchable and exportable.', path: '/me/transactions' });
}

const TABLE_ID = 'me-transactions';
const RouterLink: LinkComponent = Link;
const fmtDay = (d: Date) => d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });

export default async function MyTransactionsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return <MeGateView kind={gate.kind} title="My transactions" />;
  const state = parseTableState(await searchParams, meTransactionsTable);
  const page = await getMyTransactions(viewer, gate.uuid, state);
  const href = (s: TableState<typeof meTransactionsTable>) => tableHref('/me/transactions', s, meTransactionsTable);
  const describe = { currency: CURRENCY_SYMBOL, entityLabels: page.entityLabels };
  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, meTransactionsTable, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  const firstPage = { ...state, cursor: null };

  return <>
    <PageHeader title="Transactions"
      subtitle={page.usingDefault
        ? 'Your personal accounts, newest first. Add an Account filter to include other accounts you can access.'
        : `${page.selected.map((a) => a.name).join(', ') || 'No readable account selected'}, newest first.`} />

    <FilterBar
      table={meTransactionsTable}
      entityLabels={page.entityLabels}
      searchEntity={searchMyAccountsAction}
      tableId={TABLE_ID}
      currency={CURRENCY_SYMBOL}
      exportSlot={<ExportMenuForViewer hrefs={exportHrefs('me-transactions', state, meTransactionsTable)} allDescription="Every transaction the filters select, up to 100,000" />}
    />

    {page.ignoredAccounts.length > 0 && <p className="me-note" role="note">
      {page.ignoredAccounts.map((id) => page.entityLabels[`account:${id}`] ?? `#${id}`).join(', ')} {page.ignoredAccounts.length === 1 ? 'is' : 'are'} not an account you can read, so {page.ignoredAccounts.length === 1 ? 'it is' : 'they are'} left out.
    </p>}
    {page.window && <p className="me-note" role="note">
      Category, counterparty and memo filters search {SEARCH_WINDOW_DAYS} days at a time. This page searched {fmtDay(page.window.from)} – {fmtDay(page.window.to)}{page.nextCursor ? '; use Older to keep searching back.' : '.'}
    </p>}

    <FeedTable id={TABLE_ID} label="Transactions" rows={page.rows}
      empty={<TableEmpty noun={page.window ? 'transactions in this window' : 'transactions'} filters={activeFilters} clearHref={href(cleared(state))} link={RouterLink} />} />

    <CursorPager
      newerHref={page.isFirstPage ? undefined : href(firstPage) as Route}
      olderHref={page.nextCursor ? href({ ...state, cursor: page.nextCursor }) as Route : undefined}
      summary={page.isFirstPage ? 'Newest first' : 'Older transactions · Newer returns to the latest'} />
  </>;
}
