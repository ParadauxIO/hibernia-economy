import { describe, it, expect } from 'vitest';
import { gini, topShare, periodDelta, synthesiseFromBuckets, bucketSamples } from '@/lib/derived';

describe('gini', () => {
  it('is 0 for perfect equality', () => {
    expect(gini([5, 5, 5, 5])).toBe(0);
    expect(gini([100])).toBe(0); // single account
  });

  it('matches the hand-computed value for a small distribution', () => {
    // sorted [1,2,3,4]: (2*30)/(4*10) - 5/4 = 1.5 - 1.25
    expect(gini([1, 2, 3, 4])).toBeCloseTo(0.25, 10);
  });

  it('approaches 1 as wealth concentrates', () => {
    expect(gini([1, 1, 1, 97])).toBeGreaterThan(0.6);
    expect(gini([1, 1, 1, 1, 9_999_999])).toBeGreaterThan(0.7);
  });

  it('ignores zero and negative balances (only positive wealth counts)', () => {
    expect(gini([-5, 0, 1, 2, 3, 4])).toBeCloseTo(gini([1, 2, 3, 4]), 10);
  });

  it('is 0 for empty / all-nonpositive input', () => {
    expect(gini([])).toBe(0);
    expect(gini([0, 0, -1])).toBe(0);
  });
});

describe('topShare', () => {
  it('computes the richest fraction of total wealth', () => {
    // desc [4,3,2,1], take ceil(4*0.5)=2 → (4+3)/10
    expect(topShare([1, 2, 3, 4], 0.5)).toBeCloseTo(0.7, 10);
  });

  it('always takes at least one account', () => {
    expect(topShare([10, 10, 10, 10], 0.01)).toBeCloseTo(0.25, 10);
  });

  it('is 1 when one account holds everything', () => {
    expect(topShare([0, 0, 100], 0.1)).toBe(1);
  });

  it('is 0 for empty input', () => {
    expect(topShare([], 0.1)).toBe(0);
  });
});

describe('periodDelta', () => {
  it('compares the last N to the prior N', () => {
    const d = periodDelta([1, 2, 3, 4, 5, 6], 3); // cur=15, prior=6
    expect(d.current).toBe(15);
    expect(d.prior).toBe(6);
    expect(d.deltaPct).toBeCloseTo(1.5, 10);
    expect(d.dir).toBe('up');
  });

  it('reports down/flat correctly', () => {
    expect(periodDelta([6, 5, 4, 3, 2, 1], 3).dir).toBe('down');
    expect(periodDelta([2, 2, 2, 2], 2).dir).toBe('flat');
  });

  it('treats a zero prior as +100% when current is positive', () => {
    const d = periodDelta([0, 0, 0, 5, 5, 5], 3);
    expect(d.prior).toBe(0);
    expect(d.deltaPct).toBe(1);
    expect(d.dir).toBe('up');
  });
});

describe('synthesiseFromBuckets', () => {
  it('expands a histogram into one value per account using the bucket midpoints', () => {
    const out = synthesiseFromBuckets([
      { bucket: '0_100', account_count: 2 },
      { bucket: '100k_plus', account_count: 1 },
    ]);
    expect(out).toHaveLength(3);
    expect(out.filter((v) => v === 50)).toHaveLength(2);
    expect(out.filter((v) => v === 200000)).toHaveLength(1);
  });

  it('skips unknown bucket keys (must stay in sync with getBalanceDistribution)', () => {
    expect(synthesiseFromBuckets([{ bucket: 'neg', account_count: 5 }])).toEqual([]);
    expect(synthesiseFromBuckets([{ bucket: 'zero', account_count: 5 }])).toEqual([]);
    // a renamed/dropped key must not silently contribute
    expect(synthesiseFromBuckets([{ bucket: '1k_10k', account_count: 5 }])).toEqual([]);
  });
});

describe('bucketSamples (OHLC)', () => {
  const mk = (iso: string, price: string, qty = 1) => ({ occurred_at: new Date(iso), unit_price: price, quantity: qty });

  it('derives open/high/low/close/volume within a bucket', () => {
    const candles = bucketSamples(
      [
        mk('2026-05-01T10:05:00Z', '10', 1),
        mk('2026-05-01T10:20:00Z', '12', 2),
        mk('2026-05-01T10:40:00Z', '8', 3),
        mk('2026-05-01T10:55:00Z', '11', 4),
      ],
      '1h',
    );
    expect(candles).toHaveLength(1);
    expect(candles[0]).toMatchObject({ open: 10, high: 12, low: 8, close: 11, volume: 10 });
  });

  it('splits across bucket boundaries and returns them time-ascending', () => {
    const candles = bucketSamples(
      [mk('2026-05-01T11:30:00Z', '5'), mk('2026-05-01T10:30:00Z', '7')],
      '1h',
    );
    expect(candles).toHaveLength(2);
    expect(candles[0]?.bucketStart.getTime()).toBeLessThan(candles[1]?.bucketStart.getTime() ?? 0);
    expect(candles[0]?.open).toBe(7); // the 10:30 bucket, even though it was last in input
  });

  it('skips non-finite prices', () => {
    const candles = bucketSamples([mk('2026-05-01T10:00:00Z', 'not-a-number')], '1h');
    expect(candles).toEqual([]);
  });
});
