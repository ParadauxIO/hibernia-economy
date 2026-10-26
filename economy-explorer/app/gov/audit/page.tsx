import Link from 'next/link';
import { ExportMenu, PageHeader, Section } from '@democracycraft/design-system';
import { CursorPager } from '@democracycraft/design-system/next';
import { DataTable, TableEmpty, type LinkComponent } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { buildMetadata } from '@/lib/metadata';
import { getLedgerAudit } from '@/lib/services/government';
import { cleared, filterText, parseTableState, tableHref, withCursor, withQuery, withoutFilter, type TableState } from '@/lib/tables/core';
import { govLedgerExportTable, govLedgerTable } from '@/lib/tables/gov';
import { searchGovEntitiesAction } from '../actions';
import { govExportHrefs } from '../exports';
import { ledgerColumns, officialColumns } from '../columns';
import { GovGate } from '../GovGate';
import { GovControls, RawNote, ScopeNote, govPage, isGate, type SearchParams } from '../shared';
import { AuditTabs } from './tabs';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government audit', description: 'Who moved money in and out of government accounts in your scope on {server}.', path: '/gov/audit' });
}

const RouterLink: LinkComponent = Link;

export default async function GovAuditPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const sp = await searchParams;
  const page = await govPage(sp, '/gov/audit');
  if (isGate(page)) return page.gate;
  if (!page.scope.canAudit) return <GovGate reason="permission" title="Audit access required" description="The audit view needs the government.audit.read permission." />;

  const state = parseTableState(sp, govLedgerTable);
  const a = await getLedgerAudit(page.scope, page.selection.ids, page.range.range, state);
  const href = (s: TableState) => tableHref('/gov/audit', s, govLedgerTable, page.extra);
  const chips = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, govLedgerTable, { currency: CURRENCY_SYMBOL, entityLabels: a.labels }), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  const exportLinks = govExportHrefs('gov-transactions', state, govLedgerExportTable, page.selection.ids, a.range, 'settled');

  return <>
    <PageHeader title="Audit" subtitle={`${page.selection.label} · ${page.range.label}`} tabs={<AuditTabs current="ledger" query={new URLSearchParams(page.extra).toString()} />}
      actions={<ExportMenu hrefs={exportLinks} allDescription="Up to 100,000 movements, last 31 days of the range" />} />
    <GovControls page={page} />
    <ScopeNote page={page} />

    <div className="gov-grid-2">
      <Section title="Money out by official" description={<RawNote range={a.range} />}>
        <DataTable id="gov-audit-out" label="Money out by official" columns={officialColumns('Moved out')} rows={a.movers.out}
          rowKey={(o) => `${o.initiator_uuid ?? ''}-${o.authorizer_uuid ?? ''}`} empty={<p className="dt-empty-text">Nothing moved out.</p>} />
      </Section>
      <Section title="Money in by official" description="Who initiated each payment in.">
        <DataTable id="gov-audit-in" label="Money in by official" columns={officialColumns('Moved in')} rows={a.movers.in}
          rowKey={(o) => `${o.initiator_uuid ?? ''}-${o.authorizer_uuid ?? ''}`} empty={<p className="dt-empty-text">Nothing moved in.</p>} />
      </Section>
    </div>

    <Section title="Movements" description={<>Every posting on these accounts with who initiated and who approved it, newest first. <RawNote range={a.range} /></>} divider>
      <FilterBar table={govLedgerTable} entityLabels={a.labels} searchEntity={searchGovEntitiesAction} currency={CURRENCY_SYMBOL} tableId="gov-ledger-table" />
      <DataTable id="gov-ledger-table" label="Movements on in-scope accounts" columns={ledgerColumns} rows={a.rows} rowKey={(m) => `${m.txn_id}-${m.account_id}`} link={RouterLink}
        empty={<TableEmpty noun="movements" filters={chips} clearHref={href(cleared(state))} link={RouterLink} />} />
      <CursorPager newerHref={state.cursor ? href(withCursor(state, null)) : undefined} olderHref={a.next ? href(withCursor(state, a.next)) : undefined} />
    </Section>
  </>;
}
