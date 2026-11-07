import './transactions.css';
import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { Money, When } from '@democracycraft/design-system';
import { CursorPager, EntityLink } from '@democracycraft/design-system/next';
import { DataTable, TableEmpty, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { CATEGORIES } from '@/lib/categories';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { accountHref, previewHref } from '@/lib/entityLinks';
import type { TxnPage, TxnView } from '@/lib/services/account';
import { SCAN_WINDOW, accountTxnsTable, type AccountTxnsState } from '@/lib/tables/accountTxns';
import { cleared, filterText, tableHref, withCursor, withQuery, withoutFilter, type EntityOption } from '@/lib/tables/core';
import { DateWithYear } from './bits';

const RouterLink: LinkComponent<AppHref> = Link;
const TABLE_ID = 'account-txns';

function categoryTone(category: string): string {
  return String((CATEGORIES.indexOf(category as (typeof CATEGORIES)[number]) % 7) + 1);
}

function columns(running: boolean): Column<TxnView>[] {
  const cols: Column<TxnView>[] = [
    {
      key: 'when',
      header: 'When',
      cell: (t) => <span className="txn-when"><When value={t.at} time /><span className="dt-sub">{` #${t.txnId}`}</span></span>,
    },
    {
      key: 'counterparty',
      header: 'Counterparty',
      cell: (t) => t.counterparty
        ? <>
          <EntityLink kind="account" href={accountHref(t.counterparty.accountId)} name={t.counterparty.name} accountType={t.counterparty.accountType}
            id={t.counterparty.accountId} preview={previewHref('account', t.counterparty.accountId)} />
          {t.otherLegs > 0 && <span className="dt-sub">{` +${t.otherLegs} more`}</span>}
        </>
        : <span className="muted">—</span>,
    },
    {
      key: 'category',
      header: 'Category',
      optional: true,
      cell: (t) => <span className="dt-cat" data-tone={categoryTone(t.category)}>{t.categoryLabel}</span>,
    },
    {
      key: 'message',
      header: 'Message',
      secondary: true,
      optional: true,
      cell: (t) => <span className="txn-message" title={[t.message, t.memo].filter(Boolean).join(' · ') || undefined}>
        {t.message || '—'}{t.memo ? <span className="dt-sub">{` · ${t.memo}`}</span> : null}
      </span>,
    },
    {
      key: 'amount',
      header: 'Amount',
      numeric: true,
      cell: (t) => <Money value={t.amount} sign="exceptZero" tone="auto" />,
    },
  ];
  if (running) {
    cols.push({ key: 'balance', header: 'Balance after', numeric: true, secondary: true, optional: true, cell: (t) => <><span className="transaction-balance-label">Balance </span>{t.balanceAfter === null ? '—' : <Money value={t.balanceAfter} />}</> });
  }
  return cols;
}

/**
 * The account history table (spec §7.1): filter chips, cursor pagination and a
 * running balance. Filtered pages examine a bounded window of the history, and
 * say how far back they looked when it ran out before a page filled.
 */
export function AccountTransactions({ pathname, state, page, extra, searchEntity, exportSlot }: {
  pathname: AppHref;
  state: AccountTxnsState;
  page: TxnPage;
  /** Params the table doesn't own (the range), kept on every link. */
  extra: [string, string][];
  searchEntity: (field: string, text: string) => Promise<EntityOption[]>;
  exportSlot?: React.ReactNode;
}) {
  const href = (s: AccountTxnsState) => `${tableHref(pathname, s, accountTxnsTable, extra)}#transactions` as const;
  const describe = { currency: CURRENCY_SYMBOL, entityLabels: page.entityLabels };
  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, accountTxnsTable, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  const cols = columns(page.runningBalance);
  const olderHref = page.next ? href(withCursor(state, page.next)) : null;
  const newerHref = page.newer === null ? null : href(withCursor(state, page.newer === 'first' ? null : page.newer));
  const summary = page.searchedBackTo
    ? `Searched back to ${new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' }).format(page.searchedBackTo)}`
    : page.rows.length > 0 ? `${page.rows.length.toLocaleString('en-US')} shown, newest first` : undefined;

  return <>
    <FilterBar table={accountTxnsTable} entityLabels={page.entityLabels} searchEntity={searchEntity}
      columns={columnOptions(cols)} tableId={TABLE_ID} currency={CURRENCY_SYMBOL} exportSlot={exportSlot} />
    {page.implicitDays !== null && <p className="txn-note">Text search covers the past {page.implicitDays} days. Add a Date filter to search another period.</p>}
    <DataTable className="transaction-table account-transaction-table" id={TABLE_ID} label="Transactions" columns={cols} rows={page.rows} rowKey={(t) => t.txnId}
      rowHref={(t) => `/transactions/${t.txnId}`} link={RouterLink} density="compact"
      empty={page.searchedBackTo
        ? <p className="dt-empty-text">No matches in the {SCAN_WINDOW.toLocaleString('en-US')} transactions before <DateWithYear value={page.searchedBackTo} />. Use Older to keep looking, or narrow the Date filter.</p>
        : <TableEmpty noun="transactions" filters={activeFilters} clearHref={href(cleared(state))} link={RouterLink} />} />
    {page.searchedBackTo && page.rows.length > 0 && <p className="txn-note">Filters look at {SCAN_WINDOW.toLocaleString('en-US')} transactions at a time. Use Older to keep looking.</p>}
    <CursorPager newerHref={newerHref} olderHref={olderHref} summary={summary} />
  </>;
}
