import 'server-only';
import superjson from 'superjson';
import { redis, tenantKey } from '@/lib/redis';

// Shared-Redis cache for expensive tenant-global aggregates (the dashboard /
// market / health full-table scans over ledger_txns + ledger_postings).
//
// Three properties the pages depend on:
//
//  1. Epoch-aligned windows. The cache "resets" on fixed wall-clock boundaries
//     (bucket = floor(now / windowMs)), NOT relative to first write. So every
//     pod — and the client's AutoRefresh, which aligns to the same boundaries —
//     agree on when fresh data appears. A 30s window resets at :00 and :30.
//
//  2. Stale-while-revalidate over a PERSISTENT last-known-good. The cached value
//     is kept until a fresh compute REPLACES it — it is never dropped on a timer.
//     A request in a new window is served the previous value immediately and
//     triggers a background refresh; no user request ever blocks on the heavy
//     query. Crucially this holds even on a low-traffic page: if nobody visits
//     for many windows the value is still there, so the next visitor gets it
//     instantly (and kicks the refresh) rather than hitting a cold compute. The
//     only expiry is a long hygiene TTL so abandoned per-entity keys eventually
//     clear; a key visited at least once within that window never goes cold.
//
//  3. Cross-pod single-flight. The refresh runs under a Redis lock, so exactly
//     ONE pod recomputes per window for the whole fleet (not one per pod, and
//     never one per concurrent request — the stampede that took prod down).
//
// An L1 in-process map absorbs the synchronized client burst (all tabs refresh
// at boundary+lag together): once a pod has the current window's value it serves
// it without touching Redis. When Redis is absent (local dev) the whole thing
// degrades to a per-pod in-memory SWR cache.

type Entry<T> = { bucket: number; value: T; at: number };

// The stored value is last-known-good: it persists until a fresh compute
// overwrites it. This TTL is hygiene only — long enough that any key visited
// within it never goes cold, short enough that abandoned per-entity keys (e.g. a
// one-off account id) eventually clear from Redis. It is NOT a freshness window;
// freshness is the epoch bucket, refreshed in the background on access.
const PERSIST_MS = 7 * 24 * 60 * 60 * 1000; // 7 days

const l1 = new Map<string, Entry<unknown>>();
const inflight = new Map<string, Promise<unknown>>();

/** Per-pod single-flight: concurrent callers for the same key share one run. */
function once<T>(key: string, fn: () => Promise<T>): Promise<T> {
  const existing = inflight.get(key) as Promise<T> | undefined;
  if (existing) return existing;
  const p = Promise.resolve()
    .then(fn)
    .finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

export async function memo<T>(key: string, windowMs: number, producer: () => Promise<T>): Promise<T> {
  const bucket = Math.floor(Date.now() / windowMs);

  // L1: this pod already has the current window — serve without hitting Redis.
  const cached = l1.get(key) as Entry<T> | undefined;
  if (cached && cached.bucket === bucket) return cached.value;

  const r = redis();
  if (!r) return memoLocal(key, windowMs, bucket, producer);

  const ckey = tenantKey(`cache:${key}`);
  let raw: string | null;
  try {
    raw = await r.get(ckey);
  } catch {
    return memoLocal(key, windowMs, bucket, producer);
  }

  if (raw) {
    let parsed: Entry<T> | null = null;
    try {
      // superjson (not JSON) so Date/BigInt etc. in cached rows round-trip
      // faithfully — a plain-JSON Date would come back as a string and break
      // callers that expect a Date (e.g. .toISOString() in CSV exports).
      const obj = superjson.parse(raw) as { bucket: number; value: T };
      parsed = { bucket: obj.bucket, value: obj.value, at: Date.now() };
    } catch {
      parsed = null;
    }
    if (parsed) {
      l1.set(key, parsed);
      if (parsed.bucket < bucket) {
        // Stale window → serve stale now, recompute once across the fleet.
        revalidate(key, ckey, bucket, windowMs, producer).catch(() => {});
      }
      return parsed.value;
    }
  }

  // Cold (no value at all — first ever, or after the 7-day hygiene TTL). This is
  // the ONLY blocking path; once a value exists it persists, so subsequent gaps
  // serve the last value instead of recomputing. Compute once per pod, publish.
  return once(key, async () => {
    const value = await producer();
    await store(ckey, bucket, value);
    l1.set(key, { bucket, value, at: Date.now() });
    return value;
  });
}

/** Background recompute for a stale window, guarded by a fleet-wide Redis lock. */
async function revalidate<T>(
  key: string,
  ckey: string,
  bucket: number,
  windowMs: number,
  producer: () => Promise<T>,
): Promise<void> {
  const r = redis();
  if (!r) return;
  const lockKey = tenantKey(`lock:${key}`);
  let acquired = false;
  try {
    // Token = bucket: one refresh per window. Lock self-expires so a dead holder
    // can't wedge the next window.
    const res = await r.set(lockKey, String(bucket), 'PX', Math.min(windowMs, 15_000), 'NX');
    acquired = res === 'OK';
  } catch {
    return;
  }
  if (!acquired) return; // another pod is already refreshing this window
  try {
    const value = await producer();
    await store(ckey, bucket, value);
    l1.set(key, { bucket, value, at: Date.now() });
  } catch (e) {
    // Leave the stale value in place; the next window retries. Log it: a recompute
    // that keeps failing (e.g. a query past max_statement_time) otherwise serves
    // the last good value indefinitely with nothing to show why (PAR-407).
    console.warn(`[cache] recompute of ${key} failed; serving the previous value:`, e);
  } finally {
    try {
      await r.del(lockKey);
    } catch {
      /* lock will expire on its own */
    }
  }
}

/** Persist the value as last-known-good (replaced only by the next successful compute). */
async function store<T>(ckey: string, bucket: number, value: T): Promise<void> {
  const r = redis();
  if (!r) return;
  try {
    await r.set(ckey, superjson.stringify({ bucket, value }), 'PX', PERSIST_MS);
  } catch {
    /* best effort — a failed write just means the next request recomputes */
  }
}

/** Per-pod in-memory SWR fallback when Redis is unavailable. */
async function memoLocal<T>(
  key: string,
  windowMs: number,
  bucket: number,
  producer: () => Promise<T>,
): Promise<T> {
  const cached = l1.get(key) as Entry<T> | undefined;
  if (cached) {
    if (cached.bucket < bucket) {
      once(key, async () => {
        const value = await producer();
        l1.set(key, { bucket, value, at: Date.now() });
        return value;
      }).catch((e) => console.warn(`[cache] recompute of ${key} failed; serving the previous value:`, e));
    }
    return cached.value;
  }
  return once(key, async () => {
    const value = await producer();
    l1.set(key, { bucket, value, at: Date.now() });
    return value;
  });
}
