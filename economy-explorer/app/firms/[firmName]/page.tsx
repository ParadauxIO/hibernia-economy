import Link from 'next/link';
import { redirect } from 'next/navigation';
import { MetricCard, MetricRow, Money, Num, Section } from '@democracycraft/design-system';
import { EntityLink } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { CoverageNote } from '@/components/entity/bits';
import { accountHref, itemHref, previewHref } from '@/lib/entityLinks';
import { accountLabel } from '@/lib/format';
import { itemIconUrl } from '@/lib/itemIcon';
import { entityMetadata } from '@/lib/entityMetadata';
import { sumBy } from '@/lib/money';
import { change, type PeriodTotals } from '@/lib/services/account';
import type { FirmPublic } from '@/lib/services/firm';
import type { FirmAccountRow } from '@/lib/sql/firm';
import { FirmHeader, loadFirm } from './shared';

export const dynamic = 'force-dynamic';

type Params = Promise<{ firmName: string }>;

export async function generateMetadata({ params }: { params: Params }) {
  const { firmName } = await params;
  const name = decodeURIComponent(firmName);
  return entityMetadata({ title: name, description: `${name}, a firm on {server}: accounts, staff and activity.`, path: `/firms/${encodeURIComponent(name)}` }, `${name}, firm summary`);
}

/** Spec §7.3, public: the firm's accounts and balances, staff, HQ, and 30-day activity across its accounts. */
export default async function FirmPage({ params, searchParams }: { params: Params; searchParams: Promise<{ tab?: string }> }) {
  const { firmName } = await params;
  const { tab } = await searchParams;
  if (tab === 'financials' || tab === 'members') redirect(`/firms/${encodeURIComponent(decodeURIComponent(firmName))}/${tab}`);
  const loaded = await loadFirm(firmName, 'overview');
  const { pub } = loaded;
  const open = pub.accounts.filter((a) => !a.archived);
  const activity = pub.activity;

  return <>
    <FirmHeader loaded={loaded} tab="overview" />
    <MetricRow label="Firm summary">
      <MetricCard label="Firm balance" value={<Money value={sumBy(open, (a) => a.balance)} />}
        meta={`Across ${open.length} ${open.length === 1 ? 'account' : 'accounts'}`}
        info="The firm’s own money, summed over its open accounts. It belongs to the firm, not to its proprietor or staff." />
      {activity && <Totals label="Money in, 30 days" cur={activity.last30} prev={activity.prev30} field="inflow" />}
      {activity && <Totals label="Money out, 30 days" cur={activity.last30} prev={activity.prev30} field="outflow" />}
      {activity && <MetricCard label="Transactions, 30 days" value={<Num value={activity.last30.txnCount} />}
        delta={change(activity.last30.txnCount, activity.prev30?.txnCount)} deltaLabel="vs previous 30 days" deltaTone="neutral"
        spark={activity.months.length > 1 ? activity.months.map((m) => m.txnCount) : undefined} />}
    </MetricRow>
    {activity && <CoverageNote coverage={activity.coverage} />}

    <div className="entity-layout">
      <div className="entity-main">
        <Section title="Accounts" description="Balances are public. Transfers between the firm’s own accounts aren’t counted as money in or out.">
          <Accounts accounts={pub.accounts} />
        </Section>
        <Section title="Top items, last 30 days" description="What sells most at the firm’s shops, by number of trades" divider>
          <TopItems items={pub.topItems} />
        </Section>
      </div>
      <aside className="entity-rail" aria-label="Related">
        <Section level={3} title="Explore">
          <ul className="entity-list">
            <li><Link href={`/chestshop/firms/${loaded.firm.firm_id}`}>Market profile</Link></li>
            <li><Link href="/firms">All firms</Link></li>
            <li><Link href="/players">Players directory</Link></li>
          </ul>
        </Section>
      </aside>
    </div>
  </>;
}

function Totals({ label, cur, prev, field }: { label: string; cur: PeriodTotals; prev: PeriodTotals | null; field: 'inflow' | 'outflow' }) {
  return <MetricCard label={label} value={<Money value={cur[field]} compact />} delta={change(cur[field], prev?.[field])} deltaLabel="vs previous 30 days" deltaTone="neutral" />;
}

function Accounts({ accounts }: { accounts: FirmAccountRow[] }) {
  const columns: Column<FirmAccountRow>[] = [
    {
      key: 'account', header: 'Account',
      cell: (a) => <><EntityLink kind="account" href={accountHref(a.account_id)} name={accountLabel(a)} accountType={a.account_type} id={a.account_id}
        preview={previewHref('account', a.account_id)} />{a.archived ? <span className="dt-sub"> · closed</span> : null}</>,
    },
    { key: 'balance', header: 'Balance', numeric: true, cell: (a) => <Money value={a.balance} /> },
  ];
  return <DataTable id="firm-accounts" label="Firm accounts" columns={columns} rows={accounts} rowKey={(a) => a.account_id}
    empty={<p className="dt-empty-text">This firm has no accounts.</p>} />;
}

function TopItems({ items }: { items: FirmPublic['topItems'] }) {
  const columns: Column<FirmPublic['topItems'][number]>[] = [
    { key: 'item', header: 'Item', cell: (i) => <EntityLink kind="item" href={itemHref(i.item_key)} name={i.item_name ?? i.item_key} icon={itemIconUrl(i.material, i.item_custom)} preview={previewHref('item', i.item_key)} /> },
    { key: 'trades', header: 'Trades', numeric: true, cell: (i) => i.trades.toLocaleString('en-US') },
    { key: 'qty', header: 'Items traded', numeric: true, secondary: true, cell: (i) => i.quantity.toLocaleString('en-US') },
  ];
  return <DataTable id="firm-items" label="Top items" columns={columns} rows={items} rowKey={(i) => i.item_key} density="compact"
    empty={<p className="dt-empty-text">No shop trades in the last 30 days.</p>} />;
}
