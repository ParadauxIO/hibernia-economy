import Link from 'next/link';
import { DataTable, TableEmpty, TablePager, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { CsvButton } from '@/components/CsvButton';
import { FilterBar } from '@/components/FilterBar';
import { JsonButton } from '@/components/JsonButton';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { accountLabel, fmtAmtFull, looksLikeUuid, shortenUuid } from '@/lib/format';
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

const RouterLink: LinkComponent = Link;

export default async function AccountsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const state = parseTableState(await searchParams, accountsTable);
  const { rows, total, typeCounts, entityLabels } = await getAccountsDirectory(state);
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof accountsTable>) => tableHref('/accounts', s, accountsTable);
  const describe = { currency: CURRENCY_SYMBOL, entityLabels };

  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, accountsTable, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];

  return (
    <>
      <div className="page-heading">
        <h1>Accounts</h1>
      </div>

      <FilterBar
        table={accountsTable}
        total={total}
        counts={{ type: typeCounts }}
        entityLabels={entityLabels}
        searchEntity={searchFirmsAction}
        columns={columnOptions(columns)}
        tableId={TABLE_ID}
        currency={CURRENCY_SYMBOL}
        exportSlot={
          <span className="toolbar-export">
            <CsvButton
              filename={`accounts-page-${state.page}.csv`}
              headers={['Account ID', 'Type', 'Name', 'Owner', 'Owner UUID', 'Balance', 'Created']}
              rows={rows.map((a) => [
                a.account_id,
                a.account_type,
                accountLabel(a),
                a.owner_name ?? '',
                a.owner_uuid ?? '',
                a.balance,
                a.created_at.toISOString(),
              ])}
            />
            <JsonButton filename={`accounts-page-${state.page}.json`} data={rows} />
          </span>
        }
      />

      <DataTable
        id={TABLE_ID}
        label="Accounts"
        columns={columns}
        rows={rows}
        rowKey={(a) => a.account_id}
        rowHref={(a) => `/accounts/${a.account_id}`}
        sort={state.sort}
        sortHref={(field, dir) => href(withSort(state, field, dir, accountsTable))}
        link={RouterLink}
        empty={<TableEmpty noun="accounts" filters={activeFilters} clearHref={href(cleared(state))} link={RouterLink} />}
      />

      <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} />
    </>
  );
}
