import { getViewer } from '@/lib/auth/viewer';
import { publicDirectoryTable } from '@/lib/tables/publicDirectory';
import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { DataTable, TableEmpty, TablePager, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { ExportMenuForViewer } from '@/components/ExportMenuForViewer';
import { FilterBar } from '@/components/FilterBar';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { accountLabel, fmtAmtFull, looksLikeUuid, shortenUuid } from '@/lib/format';
import { exportHrefs } from '@/lib/export/href';
import { buildMetadata } from '@/lib/metadata';
import { getAccountsDirectory } from '@/lib/services/accountsDirectory';
import type { ExplorerAccountRow } from '@/lib/sql/ledger';
import { searchFirmsAction } from '@/lib/tables/actions';
import { accountsTable } from '@/lib/tables/accounts';
import { filterText, parseTableState, tableHref, withPage, withQuery, withSort, withoutFilter, cleared, type TableState } from '@/lib/tables/core';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Accounts', description: 'Browse every account on the {server} economy ledger, with balances and recent activity.', path: '/accounts' });
}

const TABLE_ID = 'accounts-table';
const TYPE_TONE: Record<string, string> = { PERSONAL: '1', BUSINESS: '3', GOVERNMENT: '4', SYSTEM: '2' };
const TYPE_LABEL: Record<string, string> = { PERSONAL: 'Personal', BUSINESS: 'Business', GOVERNMENT: 'Government', SYSTEM: 'System' };

const columns: Column<ExplorerAccountRow>[] = [
  {
    key: 'name',
    header: 'Account',
    sort: 'name',
    cell: (a) => {
      const label = accountLabel(a);
      const owner = a.display_name && !looksLikeUuid(a.display_name) && a.owner_name && a.display_name !== a.owner_name ? a.owner_name : null;
      return (
        <>
          <span>{label}</span>
          <span className="dt-sub">{` #${a.account_id}`}</span>
          {owner && <span className="dt-sub">{` · ${owner}`}</span>}
          {looksLikeUuid(a.display_name) && !a.owner_name && a.owner_uuid && (
            <span className="dt-sub" title={a.owner_uuid}>{` · ${shortenUuid(a.owner_uuid)}`}</span>
          )}
          {a.archived ? <span className="dt-sub"> · archived</span> : null}
        </>
      );
    },
  },
  {
    key: 'type',
    header: 'Type',
    sort: 'type',
    secondary: true,
    optional: true,
    cell: (a) => <span className="dt-cat" data-tone={TYPE_TONE[a.account_type] ?? 'muted'}>{TYPE_LABEL[a.account_type] ?? a.account_type}</span>,
  },
  {
    key: 'balance',
    header: 'Balance',
    sort: 'balance',
    numeric: true,
    cell: (a) => fmtAmtFull(a.balance),
  },
  {
    key: 'created',
    header: 'Created',
    sort: 'created',
    sortFirst: 'desc',
    secondary: true,
    optional: true,
    cell: (a) => <LocalTimestamp value={a.created_at} />,
  },
];

const RouterLink: LinkComponent<AppHref> = Link;

export default async function AccountsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  const showBalances = !viewer.anon && viewer.linked && !!viewer.minecraftUuid;
  const table = showBalances ? accountsTable : publicDirectoryTable(accountsTable);
  const visibleColumns = showBalances ? columns : columns.filter(column => column.key !== 'balance');
  const state = parseTableState(await searchParams, table);
  const { rows: directoryRows, total, typeCounts, entityLabels } = await getAccountsDirectory(state);
  const rows = showBalances ? directoryRows : directoryRows.map(row => ({ ...row, balance: '0' }));
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof table>) => tableHref('/accounts', s, table);
  const describe = { currency: CURRENCY_SYMBOL, entityLabels };

  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, table, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];

  return (
    <>
      <div className="page-heading">
        <h1>Accounts</h1>
      </div>

      <FilterBar
        table={table}
        total={total}
        counts={{ type: typeCounts }}
        entityLabels={entityLabels}
        {...(showBalances ? { searchEntity: searchFirmsAction } : {})}
        columns={columnOptions(visibleColumns)}
        tableId={TABLE_ID}
        currency={CURRENCY_SYMBOL}
        exportSlot={showBalances && <ExportMenuForViewer hrefs={exportHrefs('accounts', state, table)} allDescription={`${total.toLocaleString('en-US')} accounts`} />}
      />

      <DataTable
        id={TABLE_ID}
        label="Accounts"
        columns={visibleColumns}
        rows={rows}
        rowKey={(a) => a.account_id}
        rowHref={(a) => `/accounts/${a.account_id}`}
        sort={state.sort}
        sortHref={(field, dir) => href(withSort(state, field, dir, table))}
        link={RouterLink}
        empty={<TableEmpty noun="accounts" filters={activeFilters} clearHref={href(cleared(state))} link={RouterLink} />}
      />

      <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} />
    </>
  );
}
