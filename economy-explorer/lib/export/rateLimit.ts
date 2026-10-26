import 'server-only';
import { redis, tenantKey } from '@/lib/redis';

// Per-viewer export rate limit: a fixed window counted in Redis, so the limit
// holds across pods. Without Redis (local dev) or when Redis errors, a per-pod
// in-memory counter applies the same limit: an export is never refused because
// Redis is down, and never unlimited either.

export const EXPORT_LIMIT = 10;
export const EXPORT_WINDOW_MS = 60_000;

/** Increments a counter that expires with its window and returns the new value. */
export interface CounterStore {
  incr(key: string, ttlMs: number): Promise<number>;
}

export function memoryStore(now: () => number = Date.now): CounterStore & { size(): number } {
  const counts = new Map<string, { n: number; expires: number }>();
  return {
    async incr(key, ttlMs) {
      const t = now();
      for (const [k, v] of counts) if (v.expires <= t) counts.delete(k);
      const e = counts.get(key);
      const next = e && e.expires > t ? { n: e.n + 1, expires: e.expires } : { n: 1, expires: t + ttlMs };
      counts.set(key, next);
      return next.n;
    },
    size: () => counts.size,
  };
}

export function redisStore(): CounterStore | null {
  const r = redis();
  if (!r) return null;
  return {
    async incr(key, ttlMs) {
      const res = await r.multi().incr(key).pexpire(key, ttlMs).exec();
      const n = res?.[0]?.[1];
      if (typeof n !== 'number') throw new Error('rate limit: unexpected INCR reply');
      return n;
    },
  };
}

const fallback = memoryStore();

export interface RateDecision {
  allowed: boolean;
  remaining: number;
  /** Seconds until the window resets. */
  retryAfterS: number;
}

/**
 * Count one export for `viewerKey` and decide. Windows are aligned to the
 * clock (floor(now / window)), so the key is the same on every pod.
 */
export async function takeExportSlot(
  viewerKey: string,
  opts: { limit?: number; windowMs?: number; now?: number; store?: CounterStore | null } = {},
): Promise<RateDecision> {
  const limit = opts.limit ?? EXPORT_LIMIT;
  const windowMs = opts.windowMs ?? EXPORT_WINDOW_MS;
  const now = opts.now ?? Date.now();
  const bucket = Math.floor(now / windowMs);
  const key = tenantKey(`ratelimit:export:${viewerKey}:${bucket}`);
  const store = opts.store === undefined ? redisStore() : opts.store;
  let n: number;
  try {
    n = await (store ?? fallback).incr(key, windowMs);
  } catch {
    n = await fallback.incr(key, windowMs);
  }
  const retryAfterS = Math.max(1, Math.ceil(((bucket + 1) * windowMs - now) / 1000));
  return { allowed: n <= limit, remaining: Math.max(0, limit - n), retryAfterS };
}
