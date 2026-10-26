import '../gallery.css';
import type { Metadata } from 'next';
import Link from 'next/link';
import { AccessGate, EntityBadge, MetricCard, MetricRow, Money, PageHeader, PlayerHead, Section, TableWrap, When } from '@democracycraft/design-system';
import { EntityLink, RangePicker, SidebarLayout } from '@democracycraft/design-system/next';
import { BalanceLine, Waterfall } from '@/components/charts/AreaTimeline';
import { parseRange } from '@/lib/range';
import * as f from '../fixtures';

export const metadata: Metadata = { title: 'Mock /me overview', robots: { index: false, follow: false } };

const NAV = [
  { items: [
    { label: 'Overview', href: '/design-system/mock-me' },
    { label: 'Transactions', href: '#transactions' },
    { label: 'Statements', href: '#statements' },
    { label: 'Taxes and fines', href: '#taxes' },
  ] },
  { label: 'More', items: [
    { label: 'Market', href: '#market' },
    { label: 'Firms', href: '#firms', count: 1 },
    { label: 'Developer', href: '#developer' },
  ] },
];

/**
 * The /me overview (spec §8.1) in a SidebarLayout. Personal money is totalled;
 * firm money is listed per firm as the firm's, never added to the player's.
 */
export default async function MockMePage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const range = parseRange(await searchParams, { defaultPreset: '90d', now: new Date('2026-10-26T12:00:00Z') });
  return <SidebarLayout title="My finances" nav={NAV}>
    <PageHeader avatar={<PlayerHead uuid={f.people.ada.uuid} size={48} />} eyebrow={<>Signed in as {f.people.ada.name}</>} title="Overview"
      subtitle="Your personal money. Firm accounts you can access are listed separately and never added to it." />
    <AccessGate kind="link" inline title="One more account to link" description="You have a Bedrock profile that isn't linked yet. Run /explorer link in game to add it." action={<Link href="/link" className="btn">Link account</Link>} />
    <RangePicker value={range.value} presets={['30d', '90d', 'ytd', '1y']} max="2026-10-26" />
    <MetricRow label="Personal summary">
      <MetricCard label="My balance" value={<Money value="52990.22" />} delta={0.064} deltaLabel="vs previous 90 days" info="The sum of your personal accounts only. Business and government money is never included." />
      <MetricCard label="Income, October" value={<Money value="10710.50" compact />} delta={0.18} deltaLabel="vs September" />
      <MetricCard label="Spending, October" value={<Money value="6007.60" compact />} delta={0.03} deltaLabel="vs September" deltaTone="inverse" />
      <MetricCard label="Tax paid, October" value={<Money value="912.40" />} meta="Sales and income tax" />
    </MetricRow>
    <Section title="My balance" description={`${range.label}, personal accounts`}>
      <BalanceLine data={f.personalBalance} />
    </Section>
    <Section title="October by category" description="Transfers between your own accounts are left out." divider>
      <Waterfall label="October income and spending by category" opening="48287.32" steps={f.monthCategories} />
    </Section>
    <Section title="Accounts" divider description="Grouped by who the money belongs to.">
      <div>
        <div className="mock-group-head"><p><strong>Mine</strong> <span className="muted">· personal</span></p><Money value="52990.22" /></div>
        <TableWrap label="My personal accounts">
          <table className="data-table">
            <thead><tr><th scope="col">Account</th><th scope="col">Relationship</th><th scope="col" className="amount">Balance</th></tr></thead>
            <tbody>{f.myAccounts.map((a) => <tr key={a.id}>
              <td><EntityLink kind="account" href="#" name={a.name} accountType={a.type} id={a.id} /></td><td className="muted">{a.relation}</td><td className="amount"><Money value={a.balance} /></td>
            </tr>)}</tbody>
          </table>
        </TableWrap>
      </div>
      {f.firmAccounts.map((firm) => <div key={firm.firm}>
        <div className="mock-group-head"><p><strong>{firm.firm}</strong> <span className="muted">· your role: {firm.role} · the firm’s money</span></p><Money value={firm.subtotal} /></div>
        <TableWrap label={`${firm.firm} accounts`}>
          <table className="data-table">
            <thead><tr><th scope="col">Account</th><th scope="col">Relationship</th><th scope="col" className="amount">Balance</th></tr></thead>
            <tbody>{firm.accounts.map((a) => <tr key={a.id}>
              <td><EntityLink kind="account" href="#" name={`${firm.firm} · ${a.name}`} accountType="BUSINESS" id={a.id} /></td><td className="muted">{a.relation}</td><td className="amount"><Money value={a.balance} /></td>
            </tr>)}</tbody>
          </table>
        </TableWrap>
      </div>)}
    </Section>
    <Section title="Recent activity" divider actions={<Link href="#transactions" className="btn btn-quiet">All transactions</Link>}>
      <TableWrap label="Recent activity">
        <table className="data-table">
          <thead><tr><th scope="col">When</th><th scope="col">Description</th><th scope="col">Type</th><th scope="col" className="amount">Amount</th></tr></thead>
          <tbody>{f.recentTransactions.slice(0, 5).map((t) => <tr key={t.id}>
            <td className="ts"><When value={t.at} /></td>
            <td>{t.memo}</td>
            <td>{t.counterparty.kind === 'account' ? <EntityBadge type={t.counterparty.type} /> : <span className="muted">{t.counterparty.kind === 'player' ? 'Player' : t.counterparty.kind === 'firm' ? 'Firm' : 'Market'}</span>}</td>
            <td className="amount"><Money value={t.amount} sign="exceptZero" tone="auto" /></td>
          </tr>)}</tbody>
        </table>
      </TableWrap>
    </Section>
  </SidebarLayout>;
}
