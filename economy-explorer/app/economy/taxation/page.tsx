import { WindowTabs, TableWrap } from '@democracycraft/design-system';
import Link from 'next/link';
import { buildMetadata } from '@/lib/metadata';
import { getTaxationDashboard } from '@/lib/services/taxation';
import { getTaxationRates } from '@/lib/services/taxationRates';
import { taxationDays } from '@/lib/taxation';
import { fmtAmtFull } from '@/lib/format';
import { LocalTimestamp } from '@/components/LocalTimestamp';

import { SectionTabs } from '@/components/SectionTabs';
import { TaxRevenueTimeline } from '@/components/charts/AreaTimeline';

export const dynamic = 'force-dynamic';
export async function generateMetadata() {
  return buildMetadata({ title: 'Taxation', description: 'Tax collections, fines and government revenue on {server}.', path: '/economy/taxation' });
}
const percent = (value: string) => `${(Number(value) * 100).toLocaleString('en-US', { maximumFractionDigits: 4 })}%`;

export default async function TaxationPage({ searchParams }: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const days = taxationDays((await searchParams).days);
  const [data, rates] = await Promise.all([getTaxationDashboard(days), getTaxationRates()]);
  return <>
    <div className="page-heading">
      <h1>Taxation</h1>
      <span className="sub">tax and fine revenue · last {days} days</span>
      <WindowTabs basePath="/economy/taxation" days={days} />
      <SectionTabs />
    </div>
    <div className="kpi-grid">
      <div className="kpi"><div className="kpi-label">Collected</div><div className="kpi-value">{fmtAmtFull(data.totals.collected)}</div><div className="kpi-meta">taxes and fines</div></div>
      <div className="kpi"><div className="kpi-label">Fine refunds</div><div className="kpi-value">{fmtAmtFull(data.totals.refunded)}</div><div className="kpi-meta">refunded in this period</div></div>
      <div className="kpi"><div className="kpi-label">Net revenue</div><div className="kpi-value">{fmtAmtFull(data.totals.net)}</div><div className="kpi-meta">collected minus refunds</div></div>
      <div className="kpi"><div className="kpi-label">Receiving accounts</div><div className="kpi-value">{data.accounts.length}</div><div className="kpi-meta">government accounts with collections or refunds</div></div>
    </div>
    <div className="card">
      <h2 className="card-title">Revenue over time</h2>
      <p className="chart-subtitle">Daily net revenue in UTC. The first and current day may be partial; days without activity show zero.</p>
      {data.accounts.length ? <TaxRevenueTimeline data={data.daily} /> : <p className="empty">No tax collections or fine refunds in this period.</p>}
    </div>
    <div className="card">
      <h2 className="card-title">Revenue by type</h2>
      <TableWrap label="Revenue by type"><table className="data-table">
        <thead><tr><th>Type</th><th className="amount">Collected</th><th className="amount">Refunded</th><th className="amount">Net</th></tr></thead>
        <tbody>{data.byType.map(row => <tr key={row.kind}><td>{row.label}</td><td className="amount">{fmtAmtFull(row.collected)}</td><td className="amount">{fmtAmtFull(row.refunded)}</td><td className="amount">{fmtAmtFull(row.net)}</td></tr>)}</tbody>
      </table></TableWrap>
      <p className="chart-subtitle">Recognised personal and corporate balance taxes, source income taxes, ChestShop sales taxes, and recorded fines. Other government transfers and property taxes are excluded. A refund is counted when it settles, even if the fine was collected before this period.</p>
    </div>
    <div className="card">
      <h2 className="card-title">Where it lands</h2>
      <TableWrap label="Where it lands"><table className="data-table">
        <thead><tr><th>Government account</th><th className="amount">Collected</th><th className="amount">Refunded</th><th className="amount">Net</th></tr></thead>
        <tbody>{!data.accounts.length && <tr><td colSpan={4} className="empty">No receiving accounts in this period.</td></tr>}
          {data.accounts.map(row => <tr key={row.id}><td>{row.name}</td><td className="amount">{fmtAmtFull(row.collected)}</td><td className="amount">{fmtAmtFull(row.refunded)}</td><td className="amount">{fmtAmtFull(row.net)}</td></tr>)}
        </tbody>
      </table></TableWrap>
      <p className="chart-subtitle">Revenue totals include archived government accounts. Individual payments, payer identities, fine reasons and account balances remain private.</p>
    </div>
    <div className="card">
      <h2 className="card-title">Rates in effect</h2>
      <h3>Personal balance tax</h3>
      {rates.balanceTax ? <>
        <p>{rates.balanceTax.enabled ? 'Enabled' : 'Disabled'} · Published <LocalTimestamp value={rates.balanceTax.updatedAt} /></p>
        <p className="chart-subtitle">Weekly rates apply to the whole balance at the highest floor reached, prorated at login. Balances below the first floor pay no balance tax.</p>
        <TableWrap label="Personal balance tax"><table className="data-table tax-rates-table"><thead><tr><th>Balance floor</th><th className="amount">Weekly rate</th></tr></thead>
          <tbody>{rates.balanceTax.brackets.map(row => <tr key={row.floor}><td>{fmtAmtFull(row.floor)} and above</td><td className="amount">{percent(row.weeklyRate)}</td></tr>)}</tbody>
        </table></TableWrap>
      </> : <p>Live personal balance-tax rates are currently unavailable.</p>}
      <h3>Source / income tax</h3>
      {rates.sourceIncomeTax ? <>
        <p>{rates.sourceIncomeTax.enabled ? 'Enabled' : 'Disabled'} · Published <LocalTimestamp value={rates.sourceIncomeTax.updatedAt} /></p>
        <p>Default rate: {percent(rates.sourceIncomeTax.defaultRate)}. Applies to sources without an override when enabled.</p>
        <TableWrap label="Source / income tax"><table className="data-table tax-rates-table"><thead><tr><th>Income source</th><th className="amount">Rate override</th></tr></thead>
          <tbody>{!rates.sourceIncomeTax.pluginRates.length && <tr><td colSpan={2} className="empty">No source overrides.</td></tr>}
            {rates.sourceIncomeTax.pluginRates.map(row => <tr key={row.plugin}><td>{row.plugin}</td><td className="amount">{percent(row.rate)}</td></tr>)}</tbody>
        </table></TableWrap>
      </> : <p>Live source / income-tax rates are currently unavailable.</p>}
      <p className="chart-subtitle">Rates show the latest configuration published by Treasury, independently of the selected revenue period. Corporate and ChestShop rates are not published here. Unavailable does not mean disabled or zero. <Link href="/docs/guides/tax">How taxes work</Link>.</p>
    </div>
  </>;
}
