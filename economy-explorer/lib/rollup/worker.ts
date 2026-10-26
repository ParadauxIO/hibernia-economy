import 'server-only';
import type { Kysely } from 'kysely';
import { withQueryLabel } from '@/lib/metrics';
import { addDays, daysBetween, eachDay, utcDay } from './days';
import type { LeaderLease } from './lease';
import { LEASE_RENEW_MS } from './lease';
import { rollupMetrics } from './metrics';
import {
  ROLLUP_JOBS, type DayJob, earliestDay, lastNightly, logRun, markHistoryComplete, pairDayPruneRange,
  prunePairDays, pruneRuns, readCoverage, recomputeDay, snapshotWealth,
} from './steps';

// The in-process scheduler (spec §4.2). Every minute the leader:
//   1. recomputes today and yesterday (UTC), plus any days missed while no
//      worker ran, oldest first so coverage stays contiguous;
//   2. snapshots today's wealth;
//   3. once a night (first tick after 00:15 UTC), recomputes the 7 days before
//      yesterday, absorbing late settlement_time updates, and prunes staging
//      and run-log rows;
//   4. spends the rest of the minute backfilling history newest-first, one day
//      per transaction, yielding between days.
// Every step is idempotent (delete + insert of whole days), so a crash, a
// restart or a brief overlap between two leaders only repeats work.

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Executor = Kysely<any>;

/** Which part of a round a step belongs to, for metrics. */
type Pass = 'fresh' | 'nightly' | 'backfill';

export const TICK_MS = 60_000;
const NIGHTLY_AFTER_MINUTES = 15;
const NIGHTLY_DAYS = 7;
const RUN_LOG_KEEP_DAYS = 7;
/** Leave this much of each minute for the next tick's fresh-day pass. */
const BACKFILL_MARGIN_MS = 10_000;

export interface WorkerDeps {
  db: Executor;
  lease: LeaderLease;
  now?: () => Date;
  /** Pause between backfilled days so request traffic keeps the DB's attention. */
  backfillPauseMs?: number;
  log?: Pick<Console, 'info' | 'warn'>;
}

export interface TickResult {
  leader: boolean;
  freshDays: string[];
  nightly: boolean;
  backfilled: string[];
  errors: number;
}

export class RollupWorker {
  private readonly db: Executor;
  private readonly lease: LeaderLease;
  private readonly now: () => Date;
  private readonly pauseMs: number;
  private readonly log: Pick<Console, 'info' | 'warn'>;
  private tickTimer: NodeJS.Timeout | null = null;
  private renewTimer: NodeJS.Timeout | null = null;
  private running: Promise<TickResult> | null = null;
  private stopping = false;

  constructor(deps: WorkerDeps) {
    this.db = deps.db;
    this.lease = deps.lease;
    this.now = deps.now ?? (() => new Date());
    this.pauseMs = deps.backfillPauseMs ?? 50;
    this.log = deps.log ?? console;
  }

  start(): void {
    this.stopping = false;
    const tick = () => { void this.tick().catch((e: unknown) => this.log.warn('[rollup] tick failed', e)); };
    this.tickTimer = setInterval(tick, TICK_MS);
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
      this.running = this.round(deadline ?? this.now().getTime() + TICK_MS - BACKFILL_MARGIN_MS)
        .finally(() => { this.running = null; });
    }
    return this.running;
  }

  private async round(deadline: number): Promise<TickResult> {
    const result: TickResult = { leader: false, freshDays: [], nightly: false, backfilled: [], errors: 0 };
    const leading = this.active() && await this.lease.acquire();
    rollupMetrics().leader.set(leading ? 1 : 0);
    if (!leading) return result;
    result.leader = true;
    const today = utcDay(this.now());

    result.freshDays = await this.freshDays(today);
    for (const day of result.freshDays) result.errors += await this.runDay(day, ROLLUP_JOBS, 'fresh');
    result.errors += await this.step('wealth', today, 'fresh', () => snapshotWealth(this.db, today));

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
      await new Promise((resolve) => setTimeout(resolve, this.pauseMs));
    }
    return result;
  }

  /** Yesterday and today, preceded by any days after the oldest job's coverage. */
  private async freshDays(today: string): Promise<string[]> {
    const yesterday = addDays(today, -1);
    const coverage = await readCoverage(this.db);
    let from = yesterday;
    for (const job of ROLLUP_JOBS) {
      const to = coverage.get(job)?.to_day;
      if (to && addDays(to, 1) < from) from = addDays(to, 1);
    }
    return eachDay({ from, to: today });
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
