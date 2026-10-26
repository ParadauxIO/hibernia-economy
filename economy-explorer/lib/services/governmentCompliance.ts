import 'server-only';
import { getViewer, hasPermission } from '@/lib/auth';
import type { Viewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { memo } from '@/lib/cache';
import { parseMoney, toDbString, type Cents } from '@/lib/money';
import { addDays, type DayRange } from '@/lib/range';
import { eachDay, monthStart, utcDay } from '@/lib/rollup/days';
import { rollupCoverage } from '@/lib/services/rollup';
import {
  accountExists, addWatch, findComplianceFirm, listFirmAccountsForCompliance, listPersonalAccountIds, listWatchlist, removeWatch,
  selectAccountFlows, selectFlaggedPostings, selectPairMonths, watchedIds,
  type ComplianceAccountRow, type ComplianceFirmRow, type PairMonthRow, type WatchRow,
} from '@/lib/sql/compliance';
import { selectGovAccountDayNets, selectGovCounterparties, selectGovLedger, type CounterpartyRow, type MovementRow } from '@/lib/sql/government';
import { parseTableState } from '@/lib/tables/core';
import { govLedgerTable } from '@/lib/tables/gov';
import { jobCoverage, rawRange, txnWindow, type Coverage } from './government';
import { balanceAnomalies, isRoundAmount, type BalanceAnomaly } from './governmentModel';

// Compliance tools (spec §10.4). RBAC only: firm.financials.read.all together
// with transactions.read, which in practice arrive through the seeded
// `compliance` role linked to an explorer group (LuckPerms group sync → group
// → role). Nothing here names a group, department or server.

export type ComplianceViewer = Extract<Viewer, { anon: false }> & { minecraftUuid: string };

export function canUseCompliance(viewer: Viewer): boolean {
  return hasPermission(viewer, 'firm.financials.read.all') && hasPermission(viewer, 'transactions.read');
}

export type ComplianceAccess = { ok: true; viewer: ComplianceViewer } | { ok: false; reason: 'login' | 'permission' | 'unlinked' };

export async function complianceAccess(): Promise<ComplianceAccess> {
  const viewer = await getViewer();
  if (viewer.anon) return { ok: false, reason: 'login' };
  if (!canUseCompliance(viewer)) return { ok: false, reason: 'permission' };
  // The watchlist is per player.
  if (!viewer.minecraftUuid) return { ok: false, reason: 'unlinked' };
  return { ok: true, viewer: viewer as ComplianceViewer };
}

// ── home ──────────────────────────────────────────────────────────────────

export interface WatchView extends WatchRow { inflow7: string; outflow7: string; txns7: number }

export interface ComplianceHome {
  watched: WatchView[];
  coverage: Coverage;
  recent: MovementRow[];
}

export async function getComplianceHome(viewer: ComplianceViewer): Promise<ComplianceHome> {
  const today = utcDay();
  const week = { from: addDays(today, -6), to: today };
  const watched = await listWatchlist(viewer.minecraftUuid);
  const ids = watched.map((w) => w.account_id);
  const [map, flows, window] = await Promise.all([rollupCoverage(), selectAccountFlows(ids, week.from, week.to), ids.length ? txnWindow(week) : Promise.resolve(null)]);
  const recent = window ? await selectGovLedger(ids, window, parseTableState({}, govLedgerTable), null, 25) : [];
  const byId = new Map(flows.map((f) => [f.account_id, f]));
  await auditView(viewer, { path: '/gov/compliance', targetType: 'global' });
  return {
    watched: watched.map((w) => ({ ...w, inflow7: byId.get(w.account_id)?.inflow ?? '0.00', outflow7: byId.get(w.account_id)?.outflow ?? '0.00', txns7: byId.get(w.account_id)?.txn_count ?? 0 })),
    coverage: jobCoverage(map, 'account_day', week),
    recent,
  };
}

// ── firm investigation ────────────────────────────────────────────────────

/** A transfer at or above this is "large"; multiples of ROUND_UNIT are "round". Exact DECIMAL strings. */
export const LARGE_AMOUNT = '100000.00';
export const ROUND_UNIT = '1000.00';

export interface FirmInvestigation {
  firm: ComplianceFirmRow;
  accounts: (ComplianceAccountRow & { watched: boolean })[];
  totalBalance: string;
  counterparties: { incoming: CounterpartyRow[]; outgoing: CounterpartyRow[]; coverage: Coverage; months: DayRange };
  proprietor: { months: PairMonthRow[]; toProprietor: string; fromProprietor: string; accounts: number };
  flagged: { rows: (MovementRow & { large: boolean; round: boolean })[]; range: DayRange & { capped: boolean } };
  anomalies: { rows: BalanceAnomaly[]; coverage: Coverage; baseline: DayRange; recent: DayRange };
}

/**
 * Everything the firm view shows. Aggregates are rollup-backed; the flagged
 * postings read the ledger in a window of at most 31 days, cached briefly.
 * Every view is written to explorer_audit against the firm.
 */
export async function getFirmInvestigation(viewer: ComplianceViewer, firmId: number, range: DayRange): Promise<FirmInvestigation | null> {
  const firm = await findComplianceFirm(firmId);
  if (!firm) return null;
  const accounts = await listFirmAccountsForCompliance(firmId);
  const ids = accounts.map((a) => a.account_id);
  const months = { from: monthStart(range.from), to: range.to };
  const today = utcDay();
  const recent = { from: addDays(today, -13), to: today };
  const baseline = { from: addDays(recent.from, -90), to: addDays(recent.from, -1) };
  const raw = rawRange(range);

  const [map, incoming, outgoing, personal, nets, watched, flagged] = await Promise.all([
    rollupCoverage(),
    selectGovCounterparties(ids, months.from, monthStart(range.to), 'in', 15),
    selectGovCounterparties(ids, months.from, monthStart(range.to), 'out', 15),
    firm.proprietor_uuid ? listPersonalAccountIds(firm.proprietor_uuid) : Promise.resolve([]),
    selectGovAccountDayNets(ids, baseline.from, recent.to),
    watchedIds(viewer.minecraftUuid, ids),
    memo(`compliance-flagged:${firmId}:${raw.from}:${raw.to}`, 5 * 60_000, async () => {
      const window = await txnWindow(raw);
      return window ? selectFlaggedPostings(ids, window, LARGE_AMOUNT, ROUND_UNIT, 50) : [];
    }),
  ]);
  const proprietorMonths = await selectPairMonths(ids, personal, months.from, monthStart(range.to));

  // One point per day, quiet days as zero, summed over the firm's accounts.
  const byDay = new Map<string, Cents>();
  for (const n of nets) byDay.set(n.day, (byDay.get(n.day) ?? 0n) + parseMoney(n.net));
  const series = (r: DayRange) => eachDay(r).map((day) => ({ day, net: toDbString(byDay.get(day) ?? 0n) }));
  const anomalyCoverage = jobCoverage(map, 'account_day', { from: baseline.from, to: recent.to });

  await auditView(viewer, { path: `/gov/compliance/firms/${firmId}`, targetType: 'firm', targetId: String(firmId) });
  const large = parseMoney(LARGE_AMOUNT);
  return {
    firm,
    accounts: accounts.map((a) => ({ ...a, watched: watched.has(a.account_id) })),
    totalBalance: toDbString(accounts.filter((a) => a.removed_at === null).reduce((s, a) => s + parseMoney(a.balance), 0n)),
    counterparties: { incoming, outgoing, coverage: jobCoverage(map, 'pair_month', months), months },
    proprietor: {
      months: proprietorMonths,
      // pair rows are from the firm's side: outflow went to the proprietor.
      toProprietor: toDbString(proprietorMonths.reduce((s, m) => s + parseMoney(m.outflow), 0n)),
      fromProprietor: toDbString(proprietorMonths.reduce((s, m) => s + parseMoney(m.inflow), 0n)),
      accounts: personal.length,
    },
    flagged: {
      rows: flagged.map((m) => {
        const a = parseMoney(m.amount);
        return { ...m, large: (a < 0n ? -a : a) >= large, round: isRoundAmount(m.amount, parseMoney(ROUND_UNIT)) };
      }),
      range: raw,
    },
    anomalies: { rows: anomalyCoverage.covered ? balanceAnomalies(series(baseline), series(recent)) : [], coverage: anomalyCoverage, baseline, recent },
  };
}

// ── watchlist writes ──────────────────────────────────────────────────────

export async function setWatched(accountId: number, watched: boolean): Promise<{ ok: true } | { ok: false; error: string }> {
  const access = await complianceAccess();
  if (!access.ok) return { ok: false, error: 'You need the compliance permissions to use the watchlist.' };
  if (!Number.isSafeInteger(accountId) || accountId <= 0 || !await accountExists(accountId)) return { ok: false, error: 'No such account.' };
  if (watched) await addWatch(access.viewer.minecraftUuid, accountId);
  else await removeWatch(access.viewer.minecraftUuid, accountId);
  await auditView(access.viewer, { path: '/gov/compliance/watchlist', targetType: 'account', targetId: String(accountId), method: 'POST' });
  return { ok: true };
}
