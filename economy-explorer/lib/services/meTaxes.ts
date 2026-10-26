import 'server-only';
import type { Viewer } from '@/lib/auth/viewer';
import { parseMoney, toDbString } from '@/lib/money';
import { utcDay } from '@/lib/rollup/days';
import type { RangeCoverage } from '@/lib/services/rollup';
import { getTaxRates } from '@/lib/services/taxRates';
import { selectFinesForPlayer, selectMonthlyFlowGroups, type MyFineRow } from '@/lib/sql/me';
import type { TaxRates } from '@/lib/taxRates';
import { isMine } from './meGroups';
import { addMonths, monthFirstDay, monthLastDay, monthOf, monthOrEmpty, summariseMonths } from './meFlows';
import { coverageMap, coverageOf, myAccounts } from './me';

// /me/taxes (spec §8.4): tax the player's personal accounts paid, by kind and
// month (rollup tax_kind), the fines issued to them, and the rates that apply.
//
// Rates: GOV (PAR-539, spec §10.5) is unifying the source. Until then this
// reads the economy_config publication (lib/services/taxRates, what the tax
// docs show), cached for 60 s.

export interface TaxMonth { month: string; total: string; byKind: Record<string, string>; /** The rollups cover the whole month. */ covered: boolean }

export interface MyTaxes {
  byMonth: TaxMonth[];
  /** Kinds that appear in any month, for the table's columns. */
  kinds: string[];
  total: string;
  coverage: RangeCoverage;
  fines: MyFineRow[];
  finesTotal: string;
  activeFinesTotal: string;
  rates: TaxRates;
  personalBalance: string;
}

export const FINES_LIMIT = 500;

/** 3 queries cold: rollup flows, fines, rates (rates and the workspace lookups are cached). */
export async function getMyTaxes(viewer: Viewer, uuid: string, now: Date = new Date()): Promise<MyTaxes> {
  const today = utcDay(now);
  const thisMonth = monthOf(today);
  const oldest = addMonths(thisMonth, -11);
  const range = { from: monthFirstDay(oldest), to: today };
  const [map, accounts] = await Promise.all([coverageMap(), myAccounts(viewer, uuid)]);
  const personal = accounts.filter((a) => isMine(a, uuid));
  const coverage = coverageOf(map, 'account_day', range);
  const from = coverage.covered || !coverage.coveredFrom || coverage.coveredFrom < range.from ? range.from : coverage.coveredFrom;
  const [flows, fines, rates] = await Promise.all([
    coverage.coveredTo ? selectMonthlyFlowGroups(personal.map((a) => a.id), from, today) : Promise.resolve([]),
    selectFinesForPlayer(uuid, FINES_LIMIT),
    getTaxRates(),
  ]);
  const months = summariseMonths(flows);
  const kinds = new Set<string>();
  const byMonth: TaxMonth[] = [];
  let total = 0n;
  for (let i = 0; i < 12; i++) {
    const month = addMonths(thisMonth, -i);
    const m = monthOrEmpty(months, month);
    for (const k of Object.keys(m.taxByKind)) kinds.add(k);
    total += m.taxPaid;
    const covered = coverageOf(map, 'account_day', { from: monthFirstDay(month), to: month === thisMonth ? today : monthLastDay(month) }).covered;
    byMonth.push({ month, covered, total: toDbString(m.taxPaid), byKind: Object.fromEntries(Object.entries(m.taxByKind).map(([k, v]) => [k, toDbString(v)])) });
  }
  const finesTotal = fines.reduce((s, f) => s + parseMoney(f.amount), 0n);
  const active = fines.filter((f) => f.revoked === 0).reduce((s, f) => s + parseMoney(f.amount), 0n);
  return {
    byMonth, kinds: [...kinds].sort(), total: toDbString(total), coverage,
    fines, finesTotal: toDbString(finesTotal), activeFinesTotal: toDbString(active), rates,
    personalBalance: toDbString(personal.reduce((s, a) => s + parseMoney(a.balance), 0n)),
  };
}
