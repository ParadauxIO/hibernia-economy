import 'server-only';
import type { Viewer } from '@/lib/auth/viewer';
import { NotFoundError } from '@/lib/errors';
import { parseMoney, toDbString, type Cents } from '@/lib/money';
import { utcDay } from '@/lib/rollup/days';
import type { RangeCoverage } from '@/lib/services/rollup';
import { selectFirstTxnIdsAtOrAfter, selectMonthlyFlowGroups, selectPostings } from '@/lib/sql/me';
import { isMine, type MyAccount } from './meGroups';
import { addMonths, monthFirstDay, monthLastDay, monthOf, monthOrEmpty, summariseMonths, toFeedRow, type CategoryFlow, type FeedRow } from './meFlows';
import { coverageMap, coverageOf, myAccounts, subjectOf } from './me';

// Monthly statements (spec §8.3), for one personal account or all of them
// together. Business and government accounts get statements on their own
// pages, never combined with personal ones.
//
// Opening and closing balances come from the rollups without a snapshot
// table: closing(month) = current balance − every later day's net, and
// opening = closing − the month's net. Per statement: one rollup query, one
// txn-bound lookup and one query for the largest transactions. Relationships
// and coverage are the workspace's shared 30 s lookups.

const MONTH_RE = /^(\d{4})-(0[1-9]|1[0-2])$/;

export function parseMonth(value: string | undefined | null, now: Date = new Date()): string | null {
  if (!value || !MONTH_RE.test(value)) return null;
  const today = monthOf(utcDay(now));
  return value <= today && value >= '2020-01' ? value : null;
}

export interface StatementStep {
  key: string;
  label: string;
  /** Signed DECIMAL string. */
  value: string;
  direction: 'in' | 'out';
  internal: boolean;
}

export interface Statement {
  month: string;
  /** Null for all personal accounts together. */
  account: MyAccount | null;
  personal: MyAccount[];
  opening: string;
  closing: string;
  income: string;
  spending: string;
  taxPaid: string;
  taxByKind: Record<string, string>;
  /** Inflows by category, then outflows by category; internal moves last. */
  steps: StatementStep[];
  largest: FeedRow[];
  coverage: RangeCoverage;
  /** The month is still running: closing is "so far". */
  partial: boolean;
}

function stepsOf(categories: readonly CategoryFlow[]): StatementStep[] {
  const ins = categories.filter((c) => c.inflow > 0n && c.category !== 'internal')
    .map((c) => ({ key: `${c.key}:in`, label: c.label, value: toDbString(c.inflow), direction: 'in' as const, internal: false }));
  const outs = categories.filter((c) => c.outflow > 0n && c.category !== 'internal')
    .map((c) => ({ key: `${c.key}:out`, label: c.label, value: toDbString(-c.outflow), direction: 'out' as const, internal: false }));
  const internal = categories.find((c) => c.category === 'internal');
  const moves: StatementStep[] = [];
  if (internal && internal.inflow > 0n) moves.push({ key: 'internal:in', label: 'From your other accounts', value: toDbString(internal.inflow), direction: 'in', internal: true });
  if (internal && internal.outflow > 0n) moves.push({ key: 'internal:out', label: 'To your other accounts', value: toDbString(-internal.outflow), direction: 'out', internal: true });
  return [...ins, ...outs, ...moves];
}

const LARGEST_LIMIT = 10;

export async function getStatement(viewer: Viewer, uuid: string, month: string, accountId: number | null, now: Date = new Date()): Promise<Statement> {
  const today = utcDay(now);
  const [map, accounts] = await Promise.all([coverageMap(), myAccounts(viewer, uuid)]);
  const personal = accounts.filter((a) => isMine(a, uuid));
  const account = accountId === null ? null : personal.find((a) => a.id === accountId) ?? null;
  if (accountId !== null && !account) throw new NotFoundError();
  const subjects = account ? [account] : personal;
  const ids = subjects.map((a) => a.id);
  const first = monthFirstDay(month);
  const coverage = coverageOf(map, 'account_day', { from: first, to: today });
  const current: Cents = subjects.reduce((s, a) => s + parseMoney(a.balance), 0n);

  const nextMonthStart = new Date(`${monthFirstDay(addMonths(month, 1))}T00:00:00Z`);
  const [flows, bounds] = await Promise.all([
    coverage.covered ? selectMonthlyFlowGroups(ids, first, today) : Promise.resolve([]),
    selectFirstTxnIdsAtOrAfter([new Date(`${first}T00:00:00Z`), nextMonthStart]),
  ]);
  const months = summariseMonths(flows);
  const m = monthOrEmpty(months, month);
  let later = 0n;
  for (const [key, value] of months) if (key > month) later += value.net;
  const closing = current - later;
  const opening = closing - m.net;

  const [fromTxn, toTxn] = bounds;
  const largest = fromTxn == null ? [] : await selectPostings({
    accounts: subjects.map(subjectOf), beforeTxnId: toTxn ?? null, fromTxnId: fromTxn, conditions: [],
    settled: { from: new Date(`${first}T00:00:00Z`), before: nextMonthStart },
    categories: null, counterpartyIds: null, texts: [], order: 'largest', limit: LARGEST_LIMIT,
  });

  return {
    month, account, personal,
    opening: toDbString(opening), closing: toDbString(closing),
    income: toDbString(m.income), spending: toDbString(m.spending), taxPaid: toDbString(m.taxPaid),
    taxByKind: Object.fromEntries(Object.entries(m.taxByKind).map(([k, v]) => [k, toDbString(v)])),
    steps: stepsOf(m.categories),
    largest: largest.map(toFeedRow),
    coverage,
    partial: monthLastDay(month) >= today,
  };
}

export interface StatementMonth {
  month: string;
  opening: string | null;
  closing: string | null;
  income: string | null;
  spending: string | null;
  covered: boolean;
}

/** The last 12 months with their opening, closing, income and spending (all personal accounts). One rollup query. */
export async function getStatementIndex(viewer: Viewer, uuid: string, now: Date = new Date()): Promise<{ months: StatementMonth[]; personal: MyAccount[]; coverage: RangeCoverage }> {
  const today = utcDay(now);
  const thisMonth = monthOf(today);
  const oldest = addMonths(thisMonth, -11);
  const [map, accounts] = await Promise.all([coverageMap(), myAccounts(viewer, uuid)]);
  const personal = accounts.filter((a) => isMine(a, uuid));
  const coverage = coverageOf(map, 'account_day', { from: monthFirstDay(oldest), to: today });
  const from = coverage.covered ? monthFirstDay(oldest) : coverage.coveredFrom && coverage.coveredFrom > monthFirstDay(oldest) ? coverage.coveredFrom : monthFirstDay(oldest);
  const flows = coverage.coveredTo ? await selectMonthlyFlowGroups(personal.map((a) => a.id), from, today) : [];
  const months = summariseMonths(flows);
  let closing = personal.reduce((s, a) => s + parseMoney(a.balance), 0n);
  const out: StatementMonth[] = [];
  for (let i = 0; i < 12; i++) {
    const month = addMonths(thisMonth, -i);
    const covered = coverageOf(map, 'account_day', { from: monthFirstDay(month), to: today }).covered;
    const m = monthOrEmpty(months, month);
    const opening = closing - m.net;
    out.push({
      month,
      covered,
      opening: covered ? toDbString(opening) : null,
      closing: covered ? toDbString(closing) : null,
      income: covered ? toDbString(m.income) : null,
      spending: covered ? toDbString(m.spending) : null,
    });
    closing = opening;
  }
  return { months: out, personal, coverage };
}
