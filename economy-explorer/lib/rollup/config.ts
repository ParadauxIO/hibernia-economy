// Rollup worker cadences (PAR-532). Defaults are tuned for the shared
// production MariaDB: every tick recomputes only today; yesterday is
// recomputed each tick for the first hour after midnight UTC (late
// settlements and the tail of the day), then once more to finalise it, and
// after that only by the nightly 7-day pass.

export interface RollupConfig {
  /** How often the leader runs a round, and so how far rollups lag (ROLLUP_TICK_MS, default 5 min). */
  tickMs: number;
  /** Minimum time between wealth snapshots; today's row is refreshed in place (ROLLUP_WEALTH_EVERY_MS, default 15 min). */
  wealthEveryMs: number;
  /** How long after 00:00 UTC yesterday is still recomputed every tick (ROLLUP_YESTERDAY_WINDOW_MS, default 60 min). */
  yesterdayWindowMs: number;
  /** Pause between backfilled days (ROLLUP_BACKFILL_PAUSE_MS, default 250 ms). */
  backfillPauseMs: number;
}

export const DEFAULT_ROLLUP_CONFIG: RollupConfig = {
  tickMs: 5 * 60_000,
  wealthEveryMs: 15 * 60_000,
  yesterdayWindowMs: 60 * 60_000,
  backfillPauseMs: 250,
};

type Env = Record<string, string | undefined>;

function ms(value: string | undefined, fallback: number, min: number): number {
  if (value === undefined || value.trim() === '') return fallback;
  const n = Number(value);
  return Number.isInteger(n) && n >= min ? n : fallback;
}

/** Read the cadences from the environment; invalid values fall back to the defaults. */
export function rollupConfig(env: Env): RollupConfig {
  return {
    tickMs: ms(env.ROLLUP_TICK_MS, DEFAULT_ROLLUP_CONFIG.tickMs, 5_000),
    wealthEveryMs: ms(env.ROLLUP_WEALTH_EVERY_MS, DEFAULT_ROLLUP_CONFIG.wealthEveryMs, 0),
    yesterdayWindowMs: ms(env.ROLLUP_YESTERDAY_WINDOW_MS, DEFAULT_ROLLUP_CONFIG.yesterdayWindowMs, 0),
    backfillPauseMs: ms(env.ROLLUP_BACKFILL_PAUSE_MS, DEFAULT_ROLLUP_CONFIG.backfillPauseMs, 0),
  };
}
