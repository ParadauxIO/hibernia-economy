// Server-safe derived metrics (no React, no browser APIs). Mirrors the
// helpers in treasury-ui/src/explorer/lib.tsx.

export type DeltaDirection = 'up' | 'down' | 'flat';

/** Gini coefficient. 0 = equality, 1 = full concentration. */
export function gini(values: number[]): number {
  const v = values.filter((x) => x > 0).slice().sort((a, b) => a - b);
  const n = v.length;
  if (n === 0) return 0;
  let cumulative = 0;
  let weighted = 0;
  for (let i = 0; i < n; i++) {
    cumulative += v[i];
    weighted += v[i] * (i + 1);
  }
  if (cumulative === 0) return 0;
  return (2 * weighted) / (n * cumulative) - (n + 1) / n;
}

/** Top-X% share. */
export function topShare(values: number[], topFraction: number): number {
  const v = values.filter((x) => x > 0).slice().sort((a, b) => b - a);
  const total = v.reduce((s, x) => s + x, 0);
  if (total === 0) return 0;
  const take = Math.max(1, Math.ceil(v.length * topFraction));
  const head = v.slice(0, take).reduce((s, x) => s + x, 0);
  return head / total;
}

/** Compare last-N to prior-N. */
export function periodDelta(values: number[], n: number): { current: number; prior: number; deltaPct: number; dir: DeltaDirection } {
  const cur = values.slice(-n).reduce((a, b) => a + b, 0);
  const prior = values.slice(-2 * n, -n).reduce((a, b) => a + b, 0);
  const dir: DeltaDirection = prior === 0 ? (cur > 0 ? 'up' : 'flat') : cur > prior ? 'up' : cur < prior ? 'down' : 'flat';
  const deltaPct = prior === 0 ? (cur > 0 ? 1 : 0) : (cur - prior) / prior;
  return { current: cur, prior, deltaPct, dir };
}

/**
 * Synthesise per-account values from a bucket histogram so we can compute Gini /
 * top-share without a per-account scan. Geometric midpoints; aligns with the
 * SPA's approach in DashboardPage.tsx.
 */
export function synthesiseFromBuckets(buckets: { bucket: string; account_count: number }[]): number[] {
  // Representative balance per bucket (must stay in sync with the bucket keys
  // in lib/sql/stats.ts getBalanceDistribution). neg / zero contribute no
  // wealth and are excluded. Mismatched keys here silently zero out gini.
  const mid: Record<string, number> = {
    '0_100': 50,
    '100_500': 300,
    '500_1k': 750,
    '1k_2k5': 1750,
    '2k5_5k': 3750,
    '5k_10k': 7500,
    '10k_25k': 17500,
    '25k_50k': 37500,
    '50k_100k': 75000,
    '100k_plus': 200000,
  };
  // 'neg' (balance < 0) is the one key getBalanceDistribution emits that carries
  // no positive wealth and is deliberately not in `mid`. Anything else missing
  // from `mid` means the SQL bucket keys drifted from this map — which would
  // silently skew Gini/top-share — so surface it rather than dropping it (ADT-160).
  const EXCLUDED = new Set(['neg']);
  const out: number[] = [];
  for (const b of buckets) {
    const m = mid[b.bucket];
    if (m == null) {
      if (!EXCLUDED.has(b.bucket)) {
        console.warn(
          `[derived] synthesiseFromBuckets: unknown balance bucket '${b.bucket}' — ` +
            `bucket keys drifted from lib/sql/stats.ts getBalanceDistribution; excluded from Gini.`,
        );
      }
      continue;
    }
    for (let i = 0; i < b.account_count; i++) out.push(m);
  }
  return out;
}

// ── OHLC bucketing for ChestShop sample feeds ─────────────────────────────

export interface Candle {
  bucketStart: Date;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export type CandleInterval = '1h' | '4h' | '1d' | '1w';

const INTERVAL_MS: Record<CandleInterval, number> = {
  '1h': 60 * 60 * 1000,
  '4h': 4 * 60 * 60 * 1000,
  '1d': 24 * 60 * 60 * 1000,
  '1w': 7 * 24 * 60 * 60 * 1000,
};

/**
 * Buckets per-trade samples into OHLC candles. Each sample is
 * { occurredAt, unitPrice, quantity }. Bucket size determined by interval;
 * empty intervals are omitted (the chart fills its own gaps).
 */
export function bucketSamples(
  samples: { occurred_at: Date; unit_price: string; quantity: number }[],
  interval: CandleInterval,
): Candle[] {
  const step = INTERVAL_MS[interval];
  const buckets = new Map<number, Candle>();
  for (const s of samples) {
    const t = s.occurred_at.getTime();
    const start = Math.floor(t / step) * step;
    const price = parseFloat(s.unit_price);
    if (!Number.isFinite(price)) continue;
    const existing = buckets.get(start);
    if (!existing) {
      buckets.set(start, {
        bucketStart: new Date(start),
        open: price,
        high: price,
        low: price,
        close: price,
        volume: s.quantity,
      });
    } else {
      existing.close = price;
      if (price > existing.high) existing.high = price;
      if (price < existing.low) existing.low = price;
      existing.volume += s.quantity;
    }
  }
  return Array.from(buckets.values()).sort((a, b) => a.bucketStart.getTime() - b.bucketStart.getTime());
}
