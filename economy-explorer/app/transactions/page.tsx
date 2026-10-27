import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { Money, PageHeader, When } from '@democracycraft/design-system';
import { DataTable, TableEmpty, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { AdminNav } from '@/components/AdminNav';
import { ExportMenuForViewer } from '@/components/ExportMenuForViewer';
import { FeedPager } from '@/components/FeedPager';
import { FilterBar } from '@/components/FilterBar';
import { PrivacyGate } from '@/components/PrivacyGate';
import { searchTransactionEntity } from '@/lib/actions/search';
import { auditView } from '@/lib/audit';
import { hasPermission } from '@/lib/auth';
import { getViewer } from '@/lib/auth/viewer';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { exportHrefs } from '@/lib/export/href';
import { buildMetadata } from '@/lib/metadata';
import {
  DEFAULT_FOLLOW_HOURS,
  FOLLOW_HOURS,
  followTheMoney,
  getPluginFacets,
  getTransactionDetail,
  getTransactionsPage,
  getTransactionsTable,
  type TxnListItem,
  type TxnSide,
} from '@/lib/services/transactions';
import { cleared, filterText, foreignParams, parseTableState, serializeTableState, stateCursor, tableHref, withQuery, withSort, withoutFilter, type TableState } from '@/lib/tables/core';
import { BACK_PARAM, JUMP_PARAM, feedLinks, parseFeedNav } from '@/lib/tables/feed';
import type { TransactionsTable } from '@/lib/tables/transactions';
import { TxnDetailBody } from './TxnDetailBody';
import { TxnDrawer } from './TxnDrawer';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Transactions', description: 'Every transfer recorded on the {server} double-entry ledger.', path: '/transactions' });
}

const TABLE_ID = 'transactions-table';
const RouterLink: LinkComponent<AppHref> = Link;
const TXN_PARAM = 'txn';
const FOLLOW_PARAM = 'follow';

function sides(list: readonly TxnSide[]) {
  if (list.length === 0) return <span className="muted">—</span>;
  const [first] = list as [TxnSide];
  return <>{first.label}{list.length > 1 && <span className="dt-sub">{` +${list.length - 1}`}</span>}</>;
}

const columns: Column<TxnListItem>[] = [
  {
    key: 'txn',
    header: 'Transaction',
    cell: (t) => <><span>{t.message || 'No message'}</span><span className="dt-sub">{` #${t.txn_id}`}</span></>,
  },
  { key: 'amount', header: 'Amount', numeric: true, cell: (t) => <Money value={t.volume} /> },
  { key: 'from', header: 'From', cell: (t) => sides(t.from) },
  { key: 'to', header: 'To', cell: (t) => sides(t.to) },
  { key: 'plugin', header: 'Plugin', secondary: true, optional: true, cell: (t) => t.plugin_system ?? <span className="muted">—</span> },
  { key: 'initiator', header: 'Initiator', secondary: true, optional: true, cell: (t) => t.initiator_name ?? <span className="muted">—</span> },
  { key: 'settled', header: 'Settled', sort: 'settled', sortFirst: 'desc', align: 'end', cell: (t) => <When value={t.settlement_time} time /> },
];

function fmtDay(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export default async function TransactionsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (viewer.anon) {
    return <PrivacyGate kind="login" title="The transaction explorer is private" hint="Sign in with moderator or admin access to search the whole ledger." />;
  }
  if (!hasPermission(viewer, 'transactions.read')) {
    return <PrivacyGate kind="private" title="Moderators and admins only" hint="Searching every transaction needs the transactions.read permission." />;
  }

  const raw = await searchParams;
  const table = await getTransactionsTable();
  const state = parseTableState(raw, table);
  const nav = parseFeedNav(raw);
  const one = (k: string) => { const v = raw[k]; return Array.isArray(v) ? v[0] : v; };
  const openId = Number(one(TXN_PARAM));
  const drawerId = Number.isSafeInteger(openId) && openId > 0 ? openId : null;
  const hoursParam = Number(one(FOLLOW_PARAM));
  const hours = (FOLLOW_HOURS as readonly number[]).includes(hoursParam) ? hoursParam : DEFAULT_FOLLOW_HOURS;

  // Privileged read of the whole ledger: the filters are the target.
  await auditView(viewer, { path: '/transactions', targetType: 'global', targetId: serializeTableState(state, table).slice(0, 64) || null });

  const [page, facets, drawer] = await Promise.all([
    getTransactionsPage(state, nav, stateCursor(state)),
    getPluginFacets(table),
    drawerId ? getTransactionDetail(drawerId) : Promise.resolve(null),
  ]);
  const followed = drawer
    ? await followTheMoney(drawer, hours, async (ids) => new Set(ids)) // transactions.read sees every account's ledger
    : [];
  if (drawer) await auditView(viewer, { path: `/transactions?txn=${drawer.txn.txn_id}`, targetType: 'transaction', targetId: String(drawer.txn.txn_id) });

  // Params that pin this exact page of the list; the drawer links keep them.
  const position = foreignParams(raw, [BACK_PARAM, JUMP_PARAM]);
  const href = (s: TableState<TransactionsTable>, extra: [string, string][] = []) => tableHref('/transactions', s, table, extra);
  const listHref = href(state, position);
  const drawerHref = (id: number, h?: number) => href(state, [...position, [TXN_PARAM, String(id)], ...(h && h !== DEFAULT_FOLLOW_HOURS ? [[FOLLOW_PARAM, String(h)] as [string, string]] : [])]);
  const describe = { currency: CURRENCY_SYMBOL, entityLabels: page.entityLabels };
  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, table, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  const { newerHref, olderHref } = feedLinks('/transactions', state, table, page.position);
  const countText = `${page.count.count.toLocaleString('en-US')}${page.count.capped ? '+' : ''}`;
  const jumpState = { ...state, cursor: null };
  const hidden = new URLSearchParams(serializeTableState(jumpState, table));

  return (
    <>
      <AdminNav />
      <PageHeader title="Transactions" subtitle="Every transfer on the ledger, newest first. Select one to see its postings and where the money went next." />

      <FilterBar
        table={table}
        total={page.count.count}
        totalIsFloor={page.count.capped}
        counts={{ plugin: facets }}
        entityLabels={page.entityLabels}
        searchEntity={searchTransactionEntity}
        columns={columnOptions(columns)}
        tableId={TABLE_ID}
        currency={CURRENCY_SYMBOL}
        exportSlot={<ExportMenuForViewer hrefs={exportHrefs('transactions', state, table)} allDescription={`${countText} transactions, up to 100,000`} />}
      />

      {page.window.bounded && page.window.from && (
        <p className="muted small">
          Amount, account type and message filters search {page.window.days} days at most: {fmtDay(page.window.from)} to {page.window.to ? fmtDay(new Date(page.window.to.getTime() - 1)) : 'now'}.
          {' '}Add a Settled filter to search another period.
        </p>
      )}

      <DataTable
        id={TABLE_ID}
        label="Transactions"
        columns={columns}
        rows={page.rows}
        rowKey={(t) => t.txn_id}
        rowHref={(t) => drawerHref(t.txn_id)}
        sort={state.sort}
        sortHref={(field, dir) => href(withSort(state, field, dir, table))}
        density="compact"
        link={RouterLink}
        empty={<TableEmpty noun="transactions" filters={activeFilters} clearHref={href(cleared(state))} link={RouterLink} />}
      />

      <FeedPager
        newerHref={newerHref}
        olderHref={olderHref}
        summary={page.rows.length ? `Showing ${page.rows.length} of ${countText}` : undefined}
        jump={{ action: '/transactions', param: JUMP_PARAM, value: nav.jump?.day, hidden: [...hidden.entries()], max: fmtDay(new Date()) }}
      />

      {drawer && (
        <TxnDrawer
          title={`Transaction #${drawer.txn.txn_id}`}
          description={drawer.txn.message || undefined}
          closeHref={listHref}
          actions={<Link href={`/transactions/${drawer.txn.txn_id}`} className="btn btn-quiet" prefetch={false}>Open full page</Link>}
        >
          <TxnDetailBody
            detail={drawer}
            followed={followed}
            hours={hours}
            followHref={(h) => drawerHref(drawer.txn.txn_id, h)}
            txnHref={(id) => drawerHref(id, hours)}
            showFollow
            inDrawer
          />
        </TxnDrawer>
      )}
    </>
  );
}
