import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DEFAULT_ROLLUP_CONFIG, rollupConfig } from '@/lib/rollup/config';
import type * as Steps from '@/lib/rollup/steps';

// The worker's scheduling decisions on a fake clock, with the step layer
// replaced by an in-memory fake: which days each tick recomputes, how often
// wealth is snapshotted, and what the nightly pass covers.

interface Run { job: string; day: string | null; startedAt: Date; error: string | null }
const state = vi.hoisted(() => ({
  coverage: new Map<string, { job: string; from_day: string; to_day: string; history_complete: number }>(),
  runs: [] as { job: string; day: string | null; startedAt: Date; error: string | null }[],
  recomputed: [] as { job: string; day: string; at: Date }[],
  wealth: [] as { day: string; at: Date }[],
  clock: new Date(0),
}));

vi.mock('@/lib/rollup/steps', async (importOriginal) => {
  const actual = await importOriginal<typeof Steps>();
  const { addDays } = await import('@/lib/rollup/days');
  const cover = (job: string, day: string) => {
    const row = state.coverage.get(job);
    if (!row) state.coverage.set(job, { job, from_day: day, to_day: day, history_complete: 1 });
    else if (day === addDays(row.to_day, 1)) row.to_day = day;
    else if (day === addDays(row.from_day, -1)) row.from_day = day;
  };
  return {
    ROLLUP_JOBS: actual.ROLLUP_JOBS,
    pairDayPruneRange: actual.pairDayPruneRange,
    readCoverage: async () => new Map(state.coverage),
    recomputeDay: async (_db: unknown, job: string, day: string) => {
      state.recomputed.push({ job, day, at: state.clock });
      cover(job, day);
      return 1;
    },
    snapshotWealth: async (_db: unknown, day: string) => { state.wealth.push({ day, at: state.clock }); return 1; },
    logRun: async (_db: unknown, entry: Run) => { state.runs.push(entry); },
    jobsRecomputedSince: async (_db: unknown, day: string, since: Date) =>
      new Set(state.runs.filter((r) => r.day === day && r.error === null && r.startedAt >= since).map((r) => r.job)),
    lastNightly: async () => state.runs.filter((r) => r.job === 'nightly' && r.error === null).map((r) => r.day!).sort().at(-1) ?? null,
    earliestDay: async () => null,
    markHistoryComplete: async () => {},
    prunePairDays: async () => 0,
    pruneRuns: async () => 0,
  };
});

import { RollupWorker } from '@/lib/rollup/worker';
import { LeaderLease } from '@/lib/rollup/lease';
import { ROLLUP_JOBS } from '@/lib/rollup/steps';

const at = (iso: string) => { state.clock = new Date(iso); };
const worker = () => new RollupWorker({
  db: {} as never, lease: new LeaderLease(null, 'k', 'h'), now: () => state.clock,
  log: { info: () => {}, warn: () => {} },
});
/** One tick with no backfill time; returns the days it recomputed. */
async function tick(w: RollupWorker, iso: string) {
  at(iso);
  return (await w.tick(0)).freshDays;
}

beforeEach(() => {
  state.coverage.clear();
  state.runs.length = 0;
  state.recomputed.length = 0;
  state.wealth.length = 0;
});

describe('rollup schedule', () => {
  it('recomputes yesterday only in the first hour after midnight UTC, then once to finalise it', async () => {
    const w = worker();
    expect(await tick(w, '2026-10-26T03:00:00Z')).toEqual(['2026-10-25', '2026-10-26']); // first run starts at yesterday
    expect(await tick(w, '2026-10-26T03:01:00Z')).toEqual(['2026-10-26']); // yesterday was finalised at 03:00
    expect(await tick(w, '2026-10-26T23:59:00Z')).toEqual(['2026-10-26']);
    expect(await tick(w, '2026-10-27T00:00:00Z')).toEqual(['2026-10-26', '2026-10-27']);
    expect(await tick(w, '2026-10-27T00:59:00Z')).toEqual(['2026-10-26', '2026-10-27']);
    // The window has closed; one more pass picks up the window's last minute.
    expect(await tick(w, '2026-10-27T01:00:00Z')).toEqual(['2026-10-26', '2026-10-27']);
    expect(await tick(w, '2026-10-27T01:01:00Z')).toEqual(['2026-10-27']);
    expect(await tick(w, '2026-10-27T15:00:00Z')).toEqual(['2026-10-27']);
  });

  it('a late settlement_time change to a finalised day is picked up by the nightly pass two days later', async () => {
    const w = worker();
    await tick(w, '2026-10-26T03:00:00Z');
    await tick(w, '2026-10-27T01:00:00Z'); // 26 is finalised in this tick
    state.recomputed.length = 0;
    await tick(w, '2026-10-27T12:00:00Z'); // a late change to the 26th now waits for the nightly pass
    expect(state.recomputed.some((r) => r.day === '2026-10-26')).toBe(false);
    at('2026-10-28T00:20:00Z');
    expect((await w.tick(0)).nightly).toBe(true);
    const days = [...new Set(state.recomputed.filter((r) => r.at === state.clock).map((r) => r.day))].sort();
    // Nightly today-8..today-2 (20..26, so the 26th), plus yesterday and today in the first-hour window.
    expect(days).toEqual(['2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25',
      '2026-10-26', '2026-10-27', '2026-10-28']);
  });

  it('catches up every day missed while no worker ran, oldest first', async () => {
    const w = worker();
    await tick(w, '2026-10-22T03:00:00Z');
    expect(await tick(w, '2026-10-26T05:00:00Z')).toEqual(['2026-10-23', '2026-10-24', '2026-10-25', '2026-10-26']);
    expect(state.coverage.get('account_day')?.to_day).toBe('2026-10-26');
    expect(await tick(w, '2026-10-26T05:01:00Z')).toEqual(['2026-10-26']);
  });

  it('finalises yesterday after an outage that spanned its window', async () => {
    const w = worker();
    await tick(w, '2026-10-26T23:50:00Z'); // last pass before the outage
    expect(await tick(w, '2026-10-27T02:30:00Z')).toEqual(['2026-10-26', '2026-10-27']);
    expect(await tick(w, '2026-10-27T02:31:00Z')).toEqual(['2026-10-27']);
  });

  it('snapshots wealth at most every 15 minutes, refreshing today', async () => {
    const w = worker();
    await tick(w, '2026-10-26T03:00:00Z');
    await tick(w, '2026-10-26T03:01:00Z');
    await tick(w, '2026-10-26T03:14:59Z');
    await tick(w, '2026-10-26T03:15:00Z');
    expect(state.wealth.map((s) => s.at.toISOString())).toEqual(['2026-10-26T03:00:00.000Z', '2026-10-26T03:15:00.000Z']);
    expect(state.wealth.every((s) => s.day === '2026-10-26')).toBe(true);
  });

  it('a simulated day of one-minute ticks: today 1,440 times, yesterday 61, wealth 96, one nightly pass', async () => {
    const w = worker();
    await tick(w, '2026-10-26T23:59:00Z');
    state.recomputed.length = 0;
    state.wealth.length = 0;
    const start = Date.parse('2026-10-27T00:00:00Z');
    for (let minute = 0; minute < 1440; minute++) await tick(w, new Date(start + minute * 60_000).toISOString());
    const perDay = (day: string) => state.recomputed.filter((r) => r.day === day && r.job === 'account_day').length;
    expect(perDay('2026-10-27')).toBe(1440);
    // 60 window ticks plus the finalising pass; the nightly pass covers 19..25.
    expect(perDay('2026-10-26')).toBe(61);
    expect(perDay('2026-10-25')).toBe(1);
    expect(state.wealth).toHaveLength(96);
    expect(state.recomputed).toHaveLength((1440 + 61 + 7) * ROLLUP_JOBS.length);
    // Before this change: today and yesterday every tick and wealth every tick.
    expect((1440 + 1440 + 7) * ROLLUP_JOBS.length).toBeGreaterThan(state.recomputed.length);
  });
});

describe('rollupConfig', () => {
  it('reads the tunables with defaults and rejects nonsense', () => {
    expect(rollupConfig({})).toEqual(DEFAULT_ROLLUP_CONFIG);
    expect(DEFAULT_ROLLUP_CONFIG).toEqual({ tickMs: 60_000, wealthEveryMs: 900_000, yesterdayWindowMs: 3_600_000, backfillPauseMs: 250 });
    expect(rollupConfig({ ROLLUP_TICK_MS: '120000', ROLLUP_WEALTH_EVERY_MS: '0', ROLLUP_YESTERDAY_WINDOW_MS: '1800000', ROLLUP_BACKFILL_PAUSE_MS: '1000' }))
      .toEqual({ tickMs: 120_000, wealthEveryMs: 0, yesterdayWindowMs: 1_800_000, backfillPauseMs: 1000 });
    expect(rollupConfig({ ROLLUP_TICK_MS: '100', ROLLUP_WEALTH_EVERY_MS: 'soon', ROLLUP_BACKFILL_PAUSE_MS: '-1' }))
      .toEqual(DEFAULT_ROLLUP_CONFIG);
  });
});
