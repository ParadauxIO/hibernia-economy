import 'server-only';
import type { Kysely } from 'kysely';
import { withQueryLabel } from '@/lib/metrics';
import { addDays, daysBetween, eachDay, utcDay } from './days';
import type { LeaderLease } from './lease';
import { LEASE_RENEW_MS } from './lease';
import { rollupMetrics } from './metrics';
import { DEFAULT_ROLLUP_CONFIG, type RollupConfig } from './config';
import {
  ROLLUP_JOBS, type DayJob, earliestDay, jobsRecomputedSince, lastNightly, logRun, markHistoryComplete, pairDayPruneRange,
  prunePairDays, pruneRuns, readCoverage, recomputeDay, snapshotWealth,
} from './steps';

// The in-process scheduler (spec §4.2). Every tick (ROLLUP_TICK_MS, default a
// minute) the leader:
//   1. recomputes today (UTC), plus any days missed while no worker ran, oldest
//      first so coverage stays contiguous. Yesterday is recomputed every tick
//      only for the first hour after 00:00 UTC (ROLLUP_YESTERDAY_WINDOW_MS),
//      while late settlements and the tail of the day land, then once more to
//      finalise it; after that the nightly pass owns it;
//   2. refreshes today's wealth snapshot at most every ROLLUP_WEALTH_EVERY_MS
//      (default 15 minutes);
//   3. once a night (first tick after 00:15 UTC), recomputes today-8..today-2,
//      absorbing late settlement_time updates, and prunes staging and run-log
//      rows. A late change to day D after its first-hour window is therefore
//      picked up by the nightly pass on D+2 (D is then today-2);
//   4. spends the rest of the tick backfilling history newest-first, one day
//      per transaction, pausing ROLLUP_BACKFILL_PAUSE_MS between days.
// Every step is idempotent (delete + insert of whole days), so a crash, a
// restart or a brief overlap between two leaders only repeats work.

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Executor = Kysely<any>;

/** Which part of a round a step belongs to, for metrics. */
type Pass = 'fresh' | 'nightly' | 'backfill';

const NIGHTLY_AFTER_MINUTES = 15;
const NIGHTLY_DAYS = 7;
const RUN_LOG_KEEP_DAYS = 7;
/** Leave this much of each tick (at most) for the next tick's fresh-day pass. */
const BACKFILL_MARGIN_MS = 10_000;
const DAY_MS = 86_400_000;

export interface WorkerDeps {
  db: Executor;
  lease: LeaderLease;
  now?: () => Date;
  /** Cadences; defaults in lib/rollup/config.ts. */
  config?: Partial<RollupConfig>;
  log?: Pick<Console, 'info' | 'warn'>;
}

export interface TickResult {
  leader: boolean;
  freshDays: string[];
  wealth: boolean;
  nightly: boolean;
  backfilled: string[];
  errors: number;
}

export class RollupWorker {
  private readonly db: Executor;
  private readonly lease: LeaderLease;
  private readonly now: () => Date;
  private readonly config: RollupConfig;
  private lastWealthAt: number | null = null;
  private readonly log: Pick<Console, 'info' | 'warn'>;
  private tickTimer: NodeJS.Timeout | null = null;
  private renewTimer: NodeJS.Timeout | null = null;
  private running: Promise<TickResult> | null = null;
  private stopping = false;

  constructor(deps: WorkerDeps) {
    this.db = deps.db;
    this.lease = deps.lease;
    this.now = deps.now ?? (() => new Date());
    this.config = { ...DEFAULT_ROLLUP_CONFIG, ...deps.config };
    this.log = deps.log ?? console;
  }

  start(): void {
    this.stopping = false;
    const tick = () => { void this.tick().catch((e: unknown) => this.log.warn('[rollup] tick failed', e)); };
    this.tickTimer = setInterval(tick, this.config.tickMs);
    this.renewTimer = setInterval(() => { if (this.lease.held) void this.lease.renew(); }, LEASE_RENEW_MS);
    this.tickTimer.unref();
    this.renewTimer.unref();
    tick();
  }

  /** Stop scheduling, let the current step finish, and release the lease. */
  async stop(): Promise<void> {
    this.stopping = true;
    if (this.tickTimer) clearInterval(this.tickTimer);
    if (this.renewTimer) clearInterval(this.renewTimer);
    this.tickTimer = this.renewTimer = null;
    await this.running?.catch(() => undefined);
    await this.lease.release();
    rollupMetrics().leader.set(0);
  }

  /** False once stop() has been called; re-read after every await. */
  private active(): boolean {
    return !this.stopping;
  }

  /** One scheduling round. Overlapping calls share the round in progress. */
  tick(deadline?: number): Promise<TickResult> {
    if (!this.running) {
      const margin = Math.min(BACKFILL_MARGIN_MS, this.config.tickMs / 6);
      this.running = this.round(deadline ?? this.now().getTime() + this.config.tickMs - margin)
        .finally(() => { this.running = null; });
    }
    return this.running;
  }

  private async round(deadline: number): Promise<TickResult> {
    const result: TickResult = { leader: false, freshDays: [], wealth: false, nightly: false, backfilled: [], errors: 0 };
    const leading = this.active() && await this.lease.acquire();
    rollupMetrics().leader.set(leading ? 1 : 0);
    if (!leading) return result;
    result.leader = true;
    const today = utcDay(this.now());

    result.freshDays = await this.freshDays(today);
    for (const day of result.freshDays) result.errors += await this.runDay(day, ROLLUP_JOBS, 'fresh');
    const now = this.now().getTime();
    if (this.lastWealthAt === null || now - this.lastWealthAt >= this.config.wealthEveryMs) {
      const failed = await this.step('wealth', today, 'fresh', () => snapshotWealth(this.db, today));
      if (!failed) this.lastWealthAt = now;
      result.errors += failed;
      result.wealth = true;
    }

    if (await this.nightlyDue(today)) {
      result.nightly = true;
      result.errors += await this.nightly(today);
    }

    while (this.active() && this.lease.held && this.now().getTime() < deadline) {
      const day = await this.backfillOne(today);
      if (!day) break;
      result.backfilled.push(day.day);
      result.errors += day.errors;
      // A failed day is retried next minute, not hammered in this one.
      if (day.errors > 0) break;
      await new Promise((resolve) => setTimeout(resolve, this.config.backfillPauseMs));
    }
    return result;
  }

  /**
   * Today; any days after the oldest job's coverage (catch-up, oldest first);
   * and yesterday while it is still settling: inside the window after 00:00
   * UTC, or until every job has recomputed it once after the window closed.
   */
  async freshDays(today: string): Promise<string[]> {
    const yesterday = addDays(today, -1);
    const coverage = await readCoverage(this.db);
    let from = today;
    for (const job of ROLLUP_JOBS) {
      const to = coverage.get(job)?.to_day;
      if (!to) from = yesterday; // first run: start coverage at yesterday
      else if (addDays(to, 1) < from) from = addDays(to, 1);
    }
    if (from === today && await this.yesterdayUnsettled(today)) from = yesterday;
    return eachDay({ from, to: today });
  }

  private async yesterdayUnsettled(today: string): Promise<boolean> {
    const windowEnd = Date.parse(`${today}T00:00:00Z`) + this.config.yesterdayWindowMs;
    if (this.now().getTime() < windowEnd) return true;
    const done = await jobsRecomputedSince(this.db, addDays(today, -1), new Date(Math.min(windowEnd, Date.parse(`${today}T00:00:00Z`) + DAY_MS)));
    return ROLLUP_JOBS.some((job) => !done.has(job));
  }

  private async nightlyDue(today: string): Promise<boolean> {
    const now = this.now();
    if (now.getUTCHours() * 60 + now.getUTCMinutes() < NIGHTLY_AFTER_MINUTES) return false;
    const last = await lastNightly(this.db);
    return last === null || last < today;
  }

  private async nightly(today: string): Promise<number> {
    const startedAt = this.now();
    let errors = 0;
    for (const day of eachDay({ from: addDays(today, -1 - NIGHTLY_DAYS), to: addDays(today, -2) })) {
      if (!this.active() || !this.lease.held) return errors + 1;
      errors += await this.runDay(day, ROLLUP_JOBS, 'nightly');
    }
    const coverage = await readCoverage(this.db);
    const prune = pairDayPruneRange(today, coverage.get('pair_month')?.from_day ?? null);
    let rows = 0;
    try {
      await withQueryLabel('rollup:prune', async () => {
        if (prune) rows += await prunePairDays(this.db, prune);
        rows += await pruneRuns(this.db, RUN_LOG_KEEP_DAYS);
      });
    } catch (e) {
      errors++;
      this.log.warn('[rollup] nightly prune failed', e);
    }
    await this.record({ job: 'nightly', day: today, startedAt, rows, error: errors ? `${errors} step(s) failed` : null });
    return errors;
  }

  /** Recompute the next older day for every job whose history is incomplete. */
  async backfillOne(today: string): Promise<{ day: string; errors: number } | null> {
    const coverage = await readCoverage(this.db);
    const due = new Map<string, DayJob[]>();
    for (const job of ROLLUP_JOBS) {
      const row = coverage.get(job);
      if (!row || row.history_complete) continue;
      const earliest = await earliestDay(this.db, job);
      const next = addDays(row.from_day, -1);
      if (earliest === null || next < earliest || next >= today) {
        await markHistoryComplete(this.db, job);
        rollupMetrics().backfillRemaining.set({ job }, 0);
        continue;
      }
      rollupMetrics().backfillRemaining.set({ job }, daysBetween(earliest, row.from_day));
      due.set(next, [...(due.get(next) ?? []), job]);
    }
    if (due.size === 0) return null;
    const day = [...due.keys()].sort().at(-1)!;
    return { day, errors: await this.runDay(day, due.get(day)!, 'backfill') };
  }

  /** Run the given jobs for one day; returns the number that failed. */
  private async runDay(day: string, jobs: readonly DayJob[], pass: Pass): Promise<number> {
    let errors = 0;
    for (const job of jobs) {
      if (!this.active()) break;
      errors += await this.step(job, day, pass, () => recomputeDay(this.db, job, day));
    }
    return errors;
  }

  /** One job-day: its queries are labelled rollup:<job> in explorer_db_query_seconds. */
  private async step(job: string, day: string, pass: Pass, work: () => Promise<number>): Promise<number> {
    const startedAt = this.now();
    const m = rollupMetrics();
    const end = m.stepSeconds.startTimer({ job, pass });
    try {
      const rows = await withQueryLabel(`rollup:${job}`, work);
      end();
      m.steps.inc({ job, pass, outcome: 'ok' });
      m.lastSuccess.set({ job }, Date.now() / 1000);
      await this.record({ job, day, startedAt, rows, error: null });
      return 0;
    } catch (e) {
      end();
      m.steps.inc({ job, pass, outcome: 'error' });
      const message = e instanceof Error ? e.message : String(e);
      this.log.warn(`[rollup] ${job} ${day} failed: ${message}`);
      await this.record({ job, day, startedAt, rows: 0, error: message });
      return 1;
    }
  }

  private async record(entry: { job: string; day: string; startedAt: Date; rows: number; error: string | null }) {
    try {
      await withQueryLabel('rollup:run_log', () => logRun(this.db, { ...entry, finishedAt: this.now() }));
    } catch (e) {
      this.log.warn('[rollup] could not write the run log', e);
    }
  }
}
