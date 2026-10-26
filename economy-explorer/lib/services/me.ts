import 'server-only';
import { memo } from '@/lib/cache';
import { hasPermission } from '@/lib/auth/policy';
import type { Viewer } from '@/lib/auth/viewer';
import { parseMoney, toDbString, type Cents } from '@/lib/money';
import { addDays, utcDay, type DayRange } from '@/lib/rollup/days';
import type { RollupJob } from '@/lib/rollup/steps';
import { rollupCoverage, type CoverageMap, type RangeCoverage } from '@/lib/services/rollup';
import {
  selectAccountRelations, selectFirmRoles, selectMonthlyFlowGroups, selectPostings, selectTopCounterparties,
  type AccountRelationRow, type FirmRoleRow, type SubjectAccount, type TopCounterpartyRow,
} from '@/lib/sql/me';
import { selectAccountNetSince } from '@/lib/sql/rollup';
import { groupAccounts, isMine, toAccounts, type AccountGroup, type MyAccount } from './meGroups';
import {
  addMonths, changeFraction, monthFirstDay, monthOf, monthOrEmpty, summariseMonths, toFeedRow, type FeedRow, type MonthFlows,
} from './meFlows';

// The player's own workspace (spec §8). Pages call these; they call lib/sql.
//
// Access: every function takes the resolved Viewer and the player whose data
// is shown. Readable accounts are the player's relationships (ownership plus
// account_read_access_web, spelled out per kind in selectAccountRelations),
// gated by RBAC exactly like readableAccountIds: `account.read.all`, or
// `account.read` with a linked identity.

/** Per-viewer cache window for /me (spec §8.8). */
export const ME_CACHE_MS = 30_000;
const ME_MAX_STALE_MS = 60_000;

export type MeGate = { ok: true; uuid: string; name: string | null } | { ok: false; kind: 'private' | 'login' | 'link' };

/**
 * The /me gates in order: missing permission, then anonymous, then unlinked.
 * Every /me page calls this itself; the layout's sidebar is presentation only.
 */
export function meGate(viewer: Viewer, permission: 'player.read' | 'webhooks.read' = 'player.read'): MeGate {
  if (!viewer.anon && !hasPermission(viewer, permission)) return { ok: false, kind: 'private' };
  if (viewer.anon) return { ok: false, kind: 'login' };
  if (!viewer.linked || !viewer.minecraftUuid) return { ok: false, kind: 'link' };
  return { ok: true, uuid: viewer.minecraftUuid, name: viewer.minecraftName };
}

/** RBAC half of account reads: may this viewer read accounts through relationships at all? */
function canReadAccounts(viewer: Viewer): boolean {
  return hasPermission(viewer, 'account.read.all') || (hasPermission(viewer, 'account.read') && !!viewer.minecraftUuid);
}

/** The cache key suffix: who is looking, when it isn't the player themselves (the admin player view). */
function viewerSuffix(viewer: Viewer, uuid: string): string {
  return viewer.minecraftUuid === uuid ? '' : `:as:${viewer.minecraftUuid ?? 'none'}`;
}

/** Relationship rows for the player, cached per player for the /me window. One query. */
export function relationsFor(uuid: string): Promise<AccountRelationRow[]> {
  return memo(`me-accounts:${uuid}`, ME_CACHE_MS, () => selectAccountRelations(uuid), { maxStaleMs: ME_MAX_STALE_MS });
}

/** Firm roles for the player, cached like relations. One query. */
export function firmRolesFor(uuid: string): Promise<FirmRoleRow[]> {
  return memo(`me-firm-roles:${uuid}`, ME_CACHE_MS, () => selectFirmRoles(uuid), { maxStaleMs: ME_MAX_STALE_MS });
}

/** Accounts the viewer may read on the player's behalf, with every relationship kind. */
export async function myAccounts(viewer: Viewer, uuid: string): Promise<MyAccount[]> {
  if (!canReadAccounts(viewer)) return [];
  return toAccounts(await relationsFor(uuid));
}

export function subjectOf(a: MyAccount): SubjectAccount {
  return { accountId: a.id, accountType: a.type, ownerUuid: a.ownerUuid, firmId: a.firmId };
}

// ── Rollup coverage ───────────────────────────────────────────────────────
//
// lib/services/rollup's helpers each read the coverage table; /me reads it once
// per window and applies the same rule here, so a page stays inside its budget.

/** Every rollup job's coverage, shared by all /me pages for the cache window. One small-table query. */
export function coverageMap(): Promise<CoverageMap> {
  return memo('me-rollup-coverage', ME_CACHE_MS, rollupCoverage, { maxStaleMs: ME_MAX_STALE_MS });
}

/** The same rule as lib/services/rollup rangeCoverage. */
export function coverageOf(map: CoverageMap, job: RollupJob, range: DayRange): RangeCoverage {
  const record = map[job];
  if (!record) return { job, covered: false, coveredFrom: null, coveredTo: null, historyComplete: false, updatedAt: null };
  const historyComplete = record.history_complete === 1;
  return {
    job,
    covered: (historyComplete || range.from >= record.from_day) && range.to <= record.to_day,
    coveredFrom: record.from_day,
    coveredTo: record.to_day,
    historyComplete,
    updatedAt: record.updated_at,
  };
}

/** The part of `range` a job has computed, or null if none of it is. */
export function coveredPart(c: RangeCoverage, range: DayRange): DayRange | null {
  if (!c.coveredFrom || !c.coveredTo) return null;
  const from = c.historyComplete || range.from > c.coveredFrom ? range.from : c.coveredFrom;
  const to = range.to < c.coveredTo ? range.to : c.coveredTo;
  return from <= to ? { from, to } : null;
}

/**
 * Closing balance per day for [range.from, range.to], derived like
 * lib/services/rollup balanceSeries: current balance minus the net of every
 * later day. `nets` must cover range.from + 1 through today.
 */
export function deriveBalances(current: Cents, nets: readonly { day: string; net: string }[], range: DayRange): { day: string; balance: string }[] {
  const netByDay = new Map(nets.map((n) => [n.day, parseMoney(n.net)]));
  let balance = current;
  for (const [day, net] of netByDay) if (day > range.to) balance -= net;
  const out: { day: string; balance: string }[] = [];
  for (let day = range.to; day >= range.from; day = addDays(day, -1)) {
    out.push({ day, balance: toDbString(balance) });
    balance -= netByDay.get(day) ?? 0n;
  }
  return out.reverse();
}

// ── Overview ──────────────────────────────────────────────────────────────

export interface CounterpartyTotal {
  accountId: number;
  accountType: string;
  name: string;
  firmId: number | null;
  firmName: string | null;
  ownerUuid: string | null;
  ownerName: string | null;
  amount: string;
  txnCount: number;
}

export interface MeOverview {
  uuid: string;
  /** Sum of the player's own PERSONAL accounts. Never business or government money. */
  myBalance: string;
  personalCount: number;
  groups: AccountGroup[];
  balance: {
    range: DayRange;
    points: { day: string; balance: string }[];
    /** Change over the range, and as a fraction of the opening balance. */
    change: string | null;
    changeFraction: number | null;
    coverage: RangeCoverage;
  };
  month: { month: string; current: MonthFlows; previous: MonthFlows; coverage: RangeCoverage };
  topSources: CounterpartyTotal[];
  topDestinations: CounterpartyTotal[];
  counterpartyCoverage: RangeCoverage;
  recent: FeedRow[];
}

function counterpartyTotal(r: TopCounterpartyRow): CounterpartyTotal {
  const name = r.display_name && !/^[0-9a-f-]{36}$/i.test(r.display_name) ? r.display_name : r.owner_name ?? `Account #${r.counterparty_id}`;
  return {
    accountId: r.counterparty_id, accountType: r.account_type, name, firmId: r.firm_id, firmName: r.firm_name,
    ownerUuid: r.owner_uuid, ownerName: r.owner_name, amount: r.amount, txnCount: r.txn_count,
  };
}

const TOP_LIMIT = 5;
export const RECENT_LIMIT = 10;

/**
 * Everything the /me overview shows, cached per viewer for 30 s under
 * `me:<uuid>:<range>`. Cold cost: 7 queries (coverage, relationships, firm
 * roles, daily nets, monthly flows, counterparties, recent activity).
 */
export async function getMeOverview(viewer: Viewer, uuid: string, range: DayRange, now: Date = new Date()): Promise<MeOverview> {
  const key = `me:${uuid}:${range.from}..${range.to}${viewerSuffix(viewer, uuid)}${canReadAccounts(viewer) ? '' : ':norbac'}`;
  return memo(key, ME_CACHE_MS, () => computeOverview(viewer, uuid, range, now), { maxStaleMs: ME_MAX_STALE_MS });
}

async function computeOverview(viewer: Viewer, uuid: string, range: DayRange, now: Date): Promise<MeOverview> {
  const today = utcDay(now);
  const thisMonth = monthOf(today);
  const lastMonth = addMonths(thisMonth, -1);
  const monthRange = { from: monthFirstDay(lastMonth), to: today };
  const [map, accounts, roles] = await Promise.all([coverageMap(), myAccounts(viewer, uuid), firmRolesFor(uuid)]);
  const personal = accounts.filter((a) => isMine(a, uuid));
  const ids = personal.map((a) => a.id);
  const current = personal.reduce((s, a) => s + parseMoney(a.balance), 0n);

  const balanceCoverage = coverageOf(map, 'account_day', { from: range.from, to: range.to > today ? range.to : today });
  const flowCoverage = coverageOf(map, 'account_day', monthRange);
  const flowPart = coveredPart(flowCoverage, monthRange);
  const pairCoverage = coverageOf(map, 'pair_month', { from: monthFirstDay(thisMonth), to: today });
  const [nets, flows, top, recent] = await Promise.all([
    balanceCoverage.covered && ids.length ? selectAccountNetSince(ids, addDays(range.from, 1)) : Promise.resolve(null),
    flowPart ? selectMonthlyFlowGroups(ids, flowPart.from, flowPart.to) : Promise.resolve([]),
    pairCoverage.coveredTo ? selectTopCounterparties(ids, monthFirstDay(thisMonth), monthFirstDay(thisMonth), TOP_LIMIT) : Promise.resolve([]),
    selectPostings({
      accounts: personal.map(subjectOf), beforeTxnId: null, fromTxnId: null, conditions: [],
      categories: null, counterpartyIds: null, texts: [], order: 'recent', limit: RECENT_LIMIT,
    }),
  ]);

  const points = nets ? deriveBalances(current, nets, range) : [];
  const opening = points[0] ? parseMoney(points[0].balance) : null;
  const months = summariseMonths(flows);
  return {
    uuid,
    myBalance: toDbString(current),
    personalCount: personal.length,
    groups: groupAccounts(accounts, uuid, roles),
    balance: {
      range,
      points,
      change: opening === null ? null : toDbString(current - opening),
      changeFraction: opening === null ? null : changeFraction(current, opening),
      coverage: balanceCoverage,
    },
    month: { month: thisMonth, current: monthOrEmpty(months, thisMonth), previous: monthOrEmpty(months, lastMonth), coverage: flowCoverage },
    topSources: top.filter((t) => t.side === 'in').map(counterpartyTotal),
    topDestinations: top.filter((t) => t.side === 'out').map(counterpartyTotal),
    counterpartyCoverage: pairCoverage,
    recent: recent.map(toFeedRow),
  };
}
