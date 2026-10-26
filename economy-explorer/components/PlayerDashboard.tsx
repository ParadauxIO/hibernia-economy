import '@/app/me/me.css';
import 'server-only';
import Link from 'next/link';
import type { Route } from 'next';
import { MetricCard, MetricRow, Money, Section, TableWrap } from '@democracycraft/design-system';
import { BalanceLine } from '@/components/charts/AreaTimeline';
import { AccountGroups } from '@/app/me/_components/AccountGroups';
import { FeedTable } from '@/app/me/_components/FeedTable';
import { PartyLink } from '@/app/me/_components/PartyLink';
import { PartlyCovered, Preparing, Updated } from '@/app/me/_components/Freshness';
import { getViewer, requirePlayerRead } from '@/lib/auth';
import { toDbString, type Cents } from '@/lib/money';
import type { DayRange } from '@/lib/rollup/days';
import { getMeOverview, type CounterpartyTotal } from '@/lib/services/me';
import { changeFraction, monthLabel, type CategoryFlow, type MonthFlows } from '@/lib/services/meFlows';

/**
 * The /me overview body for one player (spec §8.1): their own money, the
 * accounts they can access grouped by whose money they are, this month's
 * income and spending, who pays them and whom they pay, and recent activity.
 * Backs `/me` and the admin player view (`/admin/players/[uuid]`); the caller
 * renders the page header and the gate.
 */
export async function PlayerDashboard({ uuid, self = false, range, rangeLabel }: { uuid: string; name?: string | null; self?: boolean; range: DayRange; rangeLabel: string }) {
  const viewer = await getViewer();
  requirePlayerRead(uuid, viewer);
  const o = await getMeOverview(viewer, uuid, range);
  const you = self ? 'you' : 'they';
  const your = self ? 'your' : 'their';
  const cur = o.month.current;
  const prev = o.month.previous;
  const thisMonth = monthLabel(o.month.month, false);
  const lastMonth = monthLabel(prev.month, false);
  const monthCovered = o.month.coverage.coveredTo !== null;

  return <>
    <MetricRow label="Personal summary">
      <MetricCard label="My balance" value={<Money value={o.myBalance} />}
        {...(o.balance.changeFraction !== null ? { delta: o.balance.changeFraction, deltaLabel: `over the ${rangeLabel.toLowerCase()}` } : {})}
        info={`The sum of ${your} own personal accounts only (${o.personalCount}). Business and government money is never included, even where ${you} run the firm.`}
        {...(o.balance.change !== null ? { meta: <><Money value={o.balance.change} sign="exceptZero" /> since {new Date(`${o.balance.range.from}T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })}</> } : {})} />
      <MetricCard label={`Income, ${thisMonth}`} value={monthCovered ? <Money value={cur.income} /> : '—'}
        delta={monthCovered ? changeFraction(cur.income, prev.income) : null} deltaLabel={`vs ${lastMonth}`}
        info="Money into your personal accounts. Transfers between your own accounts are left out; wages and draws from a firm count." />
      <MetricCard label={`Spending, ${thisMonth}`} value={monthCovered ? <Money value={cur.spending} /> : '—'}
        delta={monthCovered ? changeFraction(cur.spending, prev.spending) : null} deltaLabel={`vs ${lastMonth}`} deltaTone="inverse" />
      <MetricCard label={`Tax paid, ${thisMonth}`} value={monthCovered ? <Money value={cur.taxPaid} /> : '—'}
        meta={<Link href="/me/taxes" prefetch={false}>Taxes and fines</Link>} />
    </MetricRow>

    <Section title="My balance" description={<>{rangeLabel}, {your} personal accounts. <Updated coverage={o.balance.coverage} /></>}>
      {o.balance.points.length
        ? <BalanceLine data={o.balance.points.map((p) => ({ date: p.day, balance: p.balance }))} />
        : o.personalCount === 0
          ? <p className="me-empty">No personal account yet.</p>
          : <Preparing coverage={o.balance.coverage} what="this range" />}
    </Section>

    <Section title={`${thisMonth} by category`} divider
      description={<>Compared with {lastMonth}. Transfers between {your} own accounts are left out. <Updated coverage={o.month.coverage} /></>}
      actions={<Link href={`/me/statements/${o.month.month}` as Route} className="btn btn-quiet" prefetch={false}>Statement</Link>}>
      {monthCovered ? <>
        <PartlyCovered coverage={o.month.coverage} />
        <div className="me-split">
          <CategoryTable title="Income" side="inflow" current={cur} previous={prev} thisMonth={thisMonth} lastMonth={lastMonth} />
          <CategoryTable title="Spending" side="outflow" current={cur} previous={prev} thisMonth={thisMonth} lastMonth={lastMonth} />
        </div>
      </> : <Preparing coverage={o.month.coverage} what="this month" />}
    </Section>

    <Section title="Who pays you and whom you pay" divider description={<>{thisMonth}, by amount. {your === 'your' ? 'Your' : 'Their'} own accounts are left out. <Updated coverage={o.counterpartyCoverage} /></>}>
      {o.counterpartyCoverage.coveredTo ? <div className="me-split">
        <CounterpartyList title="Top income sources" rows={o.topSources} empty="No money in yet this month." />
        <CounterpartyList title="Top spending destinations" rows={o.topDestinations} empty="No money out yet this month." />
      </div> : <Preparing coverage={o.counterpartyCoverage} what="this month" />}
    </Section>

    <Section title="Accounts" divider description={`Grouped by whose money it is. Only “Mine” is ${your} money; the other groups are never added to it.`}>
      <AccountGroups groups={o.groups} />
    </Section>

    <Section title="Recent activity" divider description={`The latest movements on ${your} personal accounts.`}
      actions={self ? <Link href="/me/transactions" className="btn btn-quiet" prefetch={false}>All transactions</Link> : undefined}>
      <FeedTable id="me-recent" label="Recent activity" rows={o.recent} showAccount={false} empty={<p className="dt-empty-text">No transactions yet.</p>} />
    </Section>
  </>;
}

function CategoryTable({ title, side, current, previous, thisMonth, lastMonth }: {
  title: string; side: 'inflow' | 'outflow'; current: MonthFlows; previous: MonthFlows; thisMonth: string; lastMonth: string;
}) {
  const pick = (f: CategoryFlow) => f[side];
  const keys = new Map<string, string>();
  for (const f of [...current.categories, ...previous.categories]) if (f.category !== 'internal' && pick(f) > 0n) keys.set(f.key, f.label);
  const amount = (m: MonthFlows, key: string): Cents => { const f = m.categories.find((c) => c.key === key); return f ? pick(f) : 0n; };
  const rows = [...keys.entries()].sort((a, b) => Number(amount(current, b[0]) - amount(current, a[0])));
  const total = side === 'inflow' ? [current.income, previous.income] : [current.spending, previous.spending];
  return <div>
    <h3 className="me-subhead">{title}</h3>
    <TableWrap label={`${title} by category`}>
      <table className="data-table">
        <thead><tr><th scope="col">Category</th><th scope="col" className="amount">{thisMonth}</th><th scope="col" className="amount">{lastMonth}</th></tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={3} className="me-empty">Nothing yet.</td></tr>}
          {rows.map(([key, label]) => <tr key={key}>
            <th scope="row">{label}</th>
            <td className="amount"><Money value={toDbString(amount(current, key))} /></td>
            <td className="amount muted"><Money value={toDbString(amount(previous, key))} /></td>
          </tr>)}
        <tr className="me-total"><th scope="row">Total</th><td className="amount"><Money value={toDbString(total[0]!)} /></td><td className="amount muted"><Money value={toDbString(total[1]!)} /></td></tr>
        </tbody>
      </table>
    </TableWrap>
  </div>;
}

function CounterpartyList({ title, rows, empty }: { title: string; rows: readonly CounterpartyTotal[]; empty: string }) {
  return <div>
    <h3 className="me-subhead">{title}</h3>
    {rows.length === 0 ? <p className="me-empty">{empty}</p> : <ol className="me-ranked">
      {rows.map((r) => <li key={r.accountId}>
        <PartyLink party={{ accountId: r.accountId, accountType: r.accountType, name: r.name, firmName: r.firmName }} />
        <Money value={r.amount} />
      </li>)}
    </ol>}
  </div>;
}
