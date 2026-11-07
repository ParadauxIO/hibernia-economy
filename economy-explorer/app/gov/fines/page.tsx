import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { ExportMenu, MetricCard, MetricRow, Money, Num, PageHeader, Pct, Section, Tag, When } from '@democracycraft/design-system';
import { DataTable, TableEmpty, TablePager, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { buildMetadata } from '@/lib/metadata';
import { getFines } from '@/lib/services/government';
import type { FineRow, IssuerRow } from '@/lib/sql/government';
import { cleared, filterText, parseTableState, tableHref, withPage, withQuery, withSort, withoutFilter, type TableState } from '@/lib/tables/core';
import { finesTable } from '@/lib/tables/fines';
import { searchGovEntitiesAction } from '../actions';
import { govExportHrefs } from '../exports';
import { AccountRef, GovControls, PlayerRef, ScopeNote, govPage, isGate, type SearchParams } from '../shared';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government fines', description: 'Fines paid into government accounts in your scope on {server}.', path: '/gov/fines' });
}

const TABLE_ID = 'gov-fines-table';
const RouterLink: LinkComponent<AppHref> = Link;

const columns: Column<FineRow>[] = [
  { key: 'player', header: 'Fined player', cell: (f) => <PlayerRef uuid={f.player_uuid} name={f.player_name} /> },
  { key: 'issued', header: 'Issued', sort: 'issued', sortFirst: 'desc', secondary: true, cell: (f) => <When value={f.issued_at} time /> },
  { key: 'reason', header: 'Reason', secondary: true, cell: (f) => f.reason ?? <span className="muted">—</span> },
  { key: 'issuer', header: 'Issued by', secondary: true, cell: (f) => <PlayerRef uuid={f.issued_by_uuid} name={f.issued_by_name} /> },
  { key: 'account', header: 'Receiving account', secondary: true, optional: true, cell: (f) => <AccountRef id={f.gov_account_id} name={f.gov_account_name} /> },
  { key: 'status', header: 'Status', cell: (f) => f.revoked ? <Tag tone="warn">Revoked</Tag> : <Tag tone="pos">Active</Tag> },
  {
    key: 'amount', header: 'Amount', sort: 'amount', numeric: true,
    cell: (f) => f.txn_id ? <Link href={`/transactions/${f.txn_id}`} prefetch={false} title={`Transaction #${f.txn_id}`}><Money value={f.amount} /></Link> : <Money value={f.amount} />,
  },
];

const issuerColumns: Column<IssuerRow>[] = [
  { key: 'issuer', header: 'Official', cell: (i) => <PlayerRef uuid={i.issuer_uuid} name={i.issuer_name} /> },
  { key: 'fines', header: 'Fines', numeric: true, cell: (i) => i.fines.toLocaleString('en-US') },
  { key: 'total', header: 'Amount', numeric: true, secondary: true, cell: (i) => <Money value={i.total} /> },
];

export default async function GovFinesPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const sp = await searchParams;
  const page = await govPage(sp, '/gov/fines');
  if (isGate(page)) return page.gate;
  const state = parseTableState(sp, finesTable);
  const f = await getFines(page.scope, page.selection.ids, page.range.range, state);
  const totalPages = Math.max(1, Math.ceil(f.summary.count / state.limit));
  const href = (s: TableState) => tableHref('/gov/fines', s, finesTable, page.extra);
  const describe = { currency: CURRENCY_SYMBOL, entityLabels: f.entityLabels };
  const active = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((x) => ({ text: filterText(x, finesTable, describe), removeHref: href(withoutFilter(state, x.field)) })),
  ];
  const exportLinks = govExportHrefs('fines', state, finesTable, page.selection.ids, page.range.range, 'issued');
  const revocation = f.summary.count ? f.summary.revoked / f.summary.count : null;

  return <>
    <PageHeader title="Fines" subtitle={`${page.selection.label} · ${page.range.label}`}
      actions={<ExportMenu hrefs={exportLinks} allDescription={`${f.summary.count.toLocaleString('en-US')} fines`} />} />
    <GovControls page={page} />
    <ScopeNote page={page} />
    <MetricRow label="Fines summary for the current filters">
      <MetricCard label="Fines issued" value={<Num value={f.summary.count} />} />
      <MetricCard label="Total amount" value={<Money value={f.summary.total} />} meta={<>Revoked <Money value={f.summary.revoked_total} compact /></>} />
      <MetricCard label="Revocation rate" value={revocation === null ? '—' : <Pct value={revocation} />} meta={`${f.summary.revoked} revoked`} />
    </MetricRow>

    <FilterBar table={finesTable} total={f.summary.count} entityLabels={f.entityLabels} searchEntity={searchGovEntitiesAction}
      columns={columnOptions(columns)} tableId={TABLE_ID} currency={CURRENCY_SYMBOL} />
    <DataTable id={TABLE_ID} label="Fines" columns={columns} rows={f.rows} rowKey={(x) => x.fine_id}
      sort={state.sort} sortHref={(field, dir) => href(withSort(state, field, dir, finesTable))} link={RouterLink}
      empty={<TableEmpty noun="fines" filters={active} clearHref={href(cleared(state))} link={RouterLink} />} />
    <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(n) => href(withPage(state, n))} link={RouterLink} />

    <Section title="Top issuers" description="Officials who issued the most fines under the current filters." divider>
      <DataTable id="gov-fine-issuers" label="Top issuers" columns={issuerColumns} rows={f.issuers} rowKey={(i) => i.issuer_uuid ?? 'unknown'}
        empty={<p className="dt-empty-text">No fines match.</p>} />
    </Section>
  </>;
}
