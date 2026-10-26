import { describe, it, expect } from 'vitest';
import { addDays, assertRange, daysBetween, eachDay, isDay, monthEnd, monthStart, utcDay } from '@/lib/rollup/days';
import { pairDayPruneRange } from '@/lib/rollup/steps';
import { rollupWorkerEnabled } from '@/lib/rollup/start';

describe('UTC days', () => {
  it('does calendar arithmetic across month, year and leap boundaries', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
    expect(addDays('2027-01-01', -1)).toBe('2026-12-31');
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
    expect(daysBetween('2026-10-01', '2026-10-26')).toBe(25);
    expect(monthStart('2026-10-26')).toBe('2026-10-01');
    expect(monthEnd('2028-02-10')).toBe('2028-02-29');
    expect(monthEnd('2026-12-05')).toBe('2026-12-31');
    expect(eachDay({ from: '2026-10-30', to: '2026-11-02' })).toEqual(['2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02']);
    expect(eachDay({ from: '2026-10-02', to: '2026-10-01' })).toEqual([]);
    expect(utcDay(new Date('2026-10-26T23:59:59.999-01:00'))).toBe('2026-10-27');
  });

  it('rejects malformed days and empty ranges', () => {
    expect(isDay('2026-02-30')).toBe(false);
    expect(isDay('2026-1-01')).toBe(false);
    expect(() => addDays('yesterday', 1)).toThrow(RangeError);
    expect(() => assertRange({ from: '2026-10-02', to: '2026-10-01' })).toThrow(RangeError);
    expect(() => assertRange({ from: '2026-10-01', to: '2026-10-01' })).not.toThrow();
  });
});

describe('pair_day pruning', () => {
  it('only prunes whole months older than the previous month that coverage has completed', () => {
    expect(pairDayPruneRange('2026-10-26', null)).toBeNull();
    // Backfill is mid-June: June is kept until its first day is done.
    expect(pairDayPruneRange('2026-10-26', '2026-06-14')).toEqual({ from: '2026-07-01', before: '2026-09-01' });
    expect(pairDayPruneRange('2026-10-26', '2026-06-01')).toEqual({ from: '2026-06-01', before: '2026-09-01' });
    // Nothing older than September yet: the previous month is always kept.
    expect(pairDayPruneRange('2026-10-26', '2026-09-01')).toBeNull();
    expect(pairDayPruneRange('2026-10-02', '2026-08-20')).toBeNull();
  });
});

describe('rollupWorkerEnabled', () => {
  it('runs by default in a server, and is off under Vitest, e2e, build and edge unless forced on', () => {
    expect(rollupWorkerEnabled({})).toBe(true);
    expect(rollupWorkerEnabled({ ROLLUP_WORKER: 'off' })).toBe(false);
    expect(rollupWorkerEnabled({ ROLLUP_WORKER: ' OFF ' })).toBe(false);
    expect(rollupWorkerEnabled({ VITEST: 'true' })).toBe(false);
    expect(rollupWorkerEnabled({ E2E_TEST_AUTH: '1' })).toBe(false);
    expect(rollupWorkerEnabled({ VITEST: 'true', ROLLUP_WORKER: 'on' })).toBe(true);
    expect(rollupWorkerEnabled({ E2E_TEST_AUTH: '1', ROLLUP_WORKER: 'on' })).toBe(true);
    expect(rollupWorkerEnabled({ NEXT_PHASE: 'phase-production-build', ROLLUP_WORKER: 'on' })).toBe(false);
    expect(rollupWorkerEnabled({ NEXT_RUNTIME: 'edge', ROLLUP_WORKER: 'on' })).toBe(false);
    expect(rollupWorkerEnabled(process.env)).toBe(false);
  });
});
