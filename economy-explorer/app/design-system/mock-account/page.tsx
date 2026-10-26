import '../gallery.css';
import type { Metadata } from 'next';
import Link from 'next/link';
import { AccessGate, EntityBadge, ExportMenu, InfoTip, MetricCard, MetricRow, Money, Num, PageHeader, Section, TableWrap, When } from '@democracycraft/design-system';
import { CursorPager, EntityLink, RangePicker } from '@democracycraft/design-system/next';
import { BalanceLine, InOutChart, Waterfall } from '@/components/charts/AreaTimeline';
import { parseRange } from '@/lib/range';
import * as f from '../fixtures';

export const metadata: Metadata = { title: 'Mock account page', robots: { index: false, follow: false } };

/**
 * A business account page (spec §7.1) composed only from primitives, with
 * fixtures. `?view=public` shows what a logged-out visitor from a game link sees.
 */
export default async function MockAccountPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const raw = await searchParams;
  const publicView = raw.view === 'public';
  const range = parseRange(raw, { now: new Date('2026-10-26T12:00:00Z') });

  const header = <PageHeader
    eyebrow={<><EntityBadge type="BUSINESS" /><span className="num">#1204</span><span>Account</span></>}
    title="Riverside Builders · Operating"
    subtitle={<>Held by <EntityLink kind="firm" href="#" name="Riverside Builders" preview="/design-system/preview/firm" />. <EntityLink kind="player" href="#" name={f.people.ada.name} uuid={f.people.ada.uuid} /> is the firm’s proprietor.</>}
    meta={<>
      <span>Created <When value="2024-03-04T10:00:00Z" /></span>
      <span>Firm of 14 staff · 3 accounts</span>
      <span className="mock-meta">Business account<InfoTip label="About business accounts">A firm’s money. It belongs to the firm, not to its proprietor; firms can have shareholders and hold deposits.</InfoTip></span>
    </>}
    actions={<>
      <button type="button" className="btn btn-quiet">Copy link</button>
      <button type="button" className="btn">Copy /pay command</button>
      {!publicView && <ExportMenu hrefs={{ page: { csv: '#', json: '#' }, all: { csv: '#', json: '#' } }} allDescription="2,418 transactions" />}
    </>}
  />;

  const metrics = <MetricRow label="Account summary">
    <MetricCard label="Balance" value={<Money value="84210.55" />} meta="As of 09:12 UTC" />
    <MetricCard label="Money in, 30 days" value={<Money value="41205.00" compact />} delta={0.12} deltaLabel="vs previous 30 days" spark={f.sparkValues} />
    <MetricCard label="Money out, 30 days" value={<Money value="37940.10" compact />} delta={-0.04} deltaLabel="vs previous 30 days" deltaTone="inverse" />
    <MetricCard label="Transactions, 30 days" value={<Num value={214} />} delta={0.05} deltaLabel="vs previous 30 days" />
  </MetricRow>;

  const related = <Section level={3} title="Related">
    <ul className="mock-related">
      <li><EntityLink kind="firm" href="#" name="Riverside Builders" /><span className="muted small">Firm</span></li>
      <li><EntityLink kind="account" href="#" name="Riverside Builders · Payroll" accountType="BUSINESS" id={1205} /><Money value="9120.00" compact /></li>
      <li><EntityLink kind="player" href="#" name={f.people.ada.name} uuid={f.people.ada.uuid} /><span className="muted small">Proprietor</span></li>
      <li><EntityLink kind="account" href="#" name="Treasury · Payroll" accountType="GOVERNMENT" id={12} /><span className="muted small">Top payer</span></li>
    </ul>
  </Section>;

  if (publicView) {
    return <>
      {header}
      {metrics}
      <div className="mock-layout">
        <div>
          <AccessGate kind="login" title="This account’s history is private"
            description="Its owners, members and Riverside Builders’ finance staff can see transactions and counterparties. Totals above are public for business accounts."
            action={<><button type="button" className="btn btn-primary">Log in to see your own account</button><Link href="/design-system/mock-account" className="btn btn-quiet">View with access</Link></>} />
        </div>
        <div>{related}</div>
      </div>
    </>;
  }

  return <>
    {header}
    <RangePicker value={range.value} max="2026-10-26" />
    {metrics}
    <div className="mock-layout">
      <div>
        <Section title="Balance" description={range.label}>
          <BalanceLine data={f.personalBalance.slice(-30).map((d) => ({ date: d.date, balance: (Number(d.balance) * 2.05).toFixed(2) }))} />
        </Section>
        <Section title="In and out" divider>
          <InOutChart data={f.inOut} />
        </Section>
        <Section title="Where the money went" description="October, by category" divider>
          <Waterfall label="October by category" opening="77380.20" steps={f.monthCategories} />
        </Section>
        <Section title="Transactions" divider actions={<Link href="#" className="btn btn-quiet">Open in explorer</Link>}>
          <TableWrap label="Recent transactions">
            <table className="data-table">
              <thead><tr><th scope="col">When</th><th scope="col">Counterparty</th><th scope="col">Memo</th><th scope="col" className="amount">Amount</th></tr></thead>
              <tbody>
                {f.recentTransactions.map((t) => <tr key={t.id}>
                  <td className="ts"><When value={t.at} /></td>
                  <td>{t.counterparty.kind === 'player'
                    ? <EntityLink kind="player" href="#" name={t.counterparty.name} uuid={t.counterparty.uuid} />
                    : t.counterparty.kind === 'account'
                      ? <EntityLink kind="account" href="#" name={t.counterparty.name} accountType={t.counterparty.type} id={t.counterparty.id} />
                      : <EntityLink kind={t.counterparty.kind} href="#" name={t.counterparty.name} />}</td>
                  <td className="muted">{t.memo}</td>
                  <td className="amount"><Money value={t.amount} sign="exceptZero" tone="auto" /></td>
                </tr>)}
              </tbody>
            </table>
          </TableWrap>
          <CursorPager newerHref={null} olderHref="#" summary="2,418 transactions" />
        </Section>
      </div>
      <div>
        {related}
        <Section level={3} title="Top counterparties" description="Last 12 months">
          <ul className="mock-related">
            {f.counterparties.map((c) => <li key={c.id}><EntityLink kind="account" href="#" name={c.name} accountType={c.type} id={c.id} /><Money value={c.total} compact tone="auto" sign="exceptZero" /></li>)}
          </ul>
        </Section>
      </div>
    </div>
  </>;
}
