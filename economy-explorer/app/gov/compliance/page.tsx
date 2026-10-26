import Link from 'next/link';
import type { Route } from 'next';
import { Money, PageHeader, Section, When } from '@democracycraft/design-system';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { buildMetadata } from '@/lib/metadata';
import { complianceAccess, getComplianceHome, type WatchView } from '@/lib/services/governmentCompliance';
import { findFirmsByPrefix } from '@/lib/sql/tableLookups';
import { ledgerColumns } from '../columns';
import { GovGate } from '../GovGate';
import { Freshness, PartyRef, type SearchParams } from '../shared';
import { WatchButton } from './WatchButton';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Compliance', description: 'Compliance watchlist on {server}.', path: '/gov/compliance' });
}

const watchColumns: Column<WatchView>[] = [
  { key: 'account', header: 'Account', cell: (w) => <PartyRef row={w} /> },
  { key: 'since', header: 'Watched since', secondary: true, cell: (w) => <When value={w.created_at} /> },
  { key: 'in', header: 'In, 7 days', numeric: true, secondary: true, cell: (w) => <Money value={w.inflow7} /> },
  { key: 'out', header: 'Out, 7 days', numeric: true, secondary: true, cell: (w) => <Money value={w.outflow7} /> },
  { key: 'balance', header: 'Balance', numeric: true, cell: (w) => <Money value={w.balance} /> },
  { key: 'firm', header: 'Investigate', secondary: true, cell: (w) => w.firm_id ? <Link href={`/gov/compliance/firms/${w.firm_id}` as Route} prefetch={false}>{w.firm_name}</Link> : <span className="muted">—</span> },
  { key: 'watch', header: <span className="sr-only">Watchlist</span>, label: 'Watchlist', cell: (w) => <WatchButton accountId={w.account_id} watched label={w.display_name ?? `account #${w.account_id}`} /> },
];

export default async function CompliancePage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const access = await complianceAccess();
  if (!access.ok) return <GovGate reason={access.reason} title="Compliance access required" description="The compliance tools need every firm’s financials and the global ledger. Your administrator grants them through a group linked to the compliance role." />;
  const sp = await searchParams;
  const q = typeof sp.firm === 'string' ? sp.firm.trim().slice(0, 64) : '';
  const [home, firms] = await Promise.all([getComplianceHome(access.viewer), q ? findFirmsByPrefix(q, 10) : Promise.resolve([])]);

  return <>
    <PageHeader title="Compliance" subtitle="Your watchlist and firm investigations" />
    <Section title="Investigate a firm">
      <form className="gov-inline-actions" action="/gov/compliance" method="get" role="search">
        <label htmlFor="compliance-firm" className="sr-only">Firm name</label>
        <input id="compliance-firm" className="input" name="firm" defaultValue={q} placeholder="Firm name starts with…" maxLength={64} />
        <button type="submit" className="btn">Find</button>
      </form>
      {q && (firms.length
        ? <ul className="gov-list">{firms.map((f) => <li key={f.value}><Link href={`/gov/compliance/firms/${f.value}` as Route} prefetch={false}>{f.label}</Link>{f.detail && <span className="muted"> · {f.detail}</span>}</li>)}</ul>
        : <p className="muted">No firm name starts with “{q}”.</p>)}
    </Section>

    <Section title="Watchlist" description={<>Accounts you watch, with the last seven days of activity. <Freshness coverage={home.coverage} /></>} divider>
      <DataTable id="compliance-watchlist" label="Watched accounts" columns={watchColumns} rows={home.watched} rowKey={(w) => w.account_id}
        empty={<p className="dt-empty-text">You aren’t watching any account yet. Open a firm investigation and choose Watch on its accounts.</p>} />
    </Section>

    <Section title="Recent activity on watched accounts" description="The latest movements over the last seven days." divider>
      <DataTable id="compliance-recent" label="Recent activity on watched accounts" columns={ledgerColumns} rows={home.recent} rowKey={(m) => `${m.txn_id}-${m.account_id}`}
        empty={<p className="dt-empty-text">No activity on watched accounts this week.</p>} />
    </Section>
  </>;
}
