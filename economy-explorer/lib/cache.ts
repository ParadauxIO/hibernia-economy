import 'server-only';
import superjson from 'superjson';
import { redis, tenantKey } from '@/lib/redis';
import { recordCacheResult, timedProducer } from '@/lib/metrics';

// Shared-Redis cache for expensive tenant-global aggregates (the dashboard /
// market / health full-table scans over ledger_txns + ledger_postings).
//
// Properties the pages depend on:
//
//  1. Epoch-aligned windows. The cache "resets" on fixed wall-clock boundaries
//     (bucket = floor(now / windowMs)), NOT relative to first write. So every
//     pod — and the client's AutoRefresh, which aligns to the same boundaries —
//     agree on when fresh data appears. A 30s window resets at :00 and :30.
//
//  2. Stale-while-revalidate over a last-known-good, bounded by `maxStaleMs`.
//     A request in a new window is served the previous value immediately and
//     triggers a background refresh, so a busy key never blocks on the heavy
//     query. A value older than `maxStaleMs` (default 60 windows: 1 h for a
//     60 s window) is no longer served as-is: a quiet key gets one blocking
//     recompute instead of showing data from days ago. If that recompute fails
//     the old value is still served, because a stale page beats an error page.
//     Redis keeps values for a 7-day hygiene TTL so abandoned per-entity keys
//     eventually clear.
//
//  3. Cross-pod single-flight. Background refreshes AND cold misses run under a
//     Redis lock, so ONE pod recomputes a key for the whole fleet. On a cold
//     miss the other pods poll Redis for that pod's result until the lock
//     expires, then compute themselves. Within a pod, concurrent requests share
//     one run. Before this, every pod that saw a cold key ran the query at once,
//     the queries slowed each other past the 15 s statement timeout, and the
//     first request to `/` on a cold dev cache took ~18 s and then failed.
//
//  4. Failures are never cached. A producer that throws stores nothing; the
//     next request (or window) tries again.
//
// An L1 in-process LRU absorbs the synchronized client burst (all tabs refresh
// at boundary+lag together): once a pod has the current window's value it serves
// it without touching Redis. It is bounded by entry count and an approximate
// byte size (the serialized length), so per-entity keys cannot grow it without
// limit. When Redis is absent (local dev) the whole thing degrades to a per-pod
// in-memory SWR cache with the same staleness rules.

export interface MemoOptions {
  /** Never serve a value computed longer ago than this. Default: 60 windows. */
  maxStaleMs?: number;
  /**
   * Cross-pod lock TTL, which is also how long other pods wait for a cold
   * compute before computing themselves. Default 20 s: past the 15 s DB
   * statement timeout, so a healthy compute finishes inside it.
   */
  lockMs?: number;
}

type Entry<T> = { bucket: number; value: T; at: number; size: number };

const PERSIST_MS = 7 * 24 * 60 * 60 * 1000; // Redis hygiene TTL, not a freshness window
const DEFAULT_LOCK_MS = 20_000;
const DEFAULT_STALE_WINDOWS = 60;
const L1_MAX_ENTRIES = 5_000;
const L1_MAX_BYTES = 64 * 1024 * 1024;

/** Map-order LRU: re-inserting on read moves a key to the young end. */
class Lru {
  private readonly map = new Map<string, Entry<unknown>>();
  private bytes = 0;
  constructor(
    private maxEntries: number,
    private maxBytes: number,
  ) {}

  get<T>(key: string): Entry<T> | undefined {
    const e = this.map.get(key);
    if (!e) return undefined;
    this.map.delete(key);
    this.map.set(key, e);
    return e as Entry<T>;
  }

  set(key: string, e: Entry<unknown>): void {
    const old = this.map.get(key);
    if (old) {
      this.bytes -= old.size;
      this.map.delete(key);
    }
    if (e.size > this.maxBytes) return; // too big for L1; Redis still has it
    this.map.set(key, e);
    this.bytes += e.size;
    while (this.map.size > this.maxEntries || this.bytes > this.maxBytes) {
      const oldest = this.map.keys().next().value as string;
      this.bytes -= this.map.get(oldest)!.size;
      this.map.delete(oldest);
    }
  }

  reset(maxEntries: number, maxBytes: number): void {
    this.map.clear();
    this.bytes = 0;
    this.maxEntries = maxEntries;
    this.maxBytes = maxBytes;
  }

  stats(): { entries: number; bytes: number; keys: string[] } {
    return { entries: this.map.size, bytes: this.bytes, keys: [...this.map.keys()] };
  }
}

const l1 = new Lru(L1_MAX_ENTRIES, L1_MAX_BYTES);
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

interface Ctx<T> {
  key: string;
  ckey: string;
  lockKey: string;
  windowMs: number;
  bucket: number;
  maxStaleMs: number;
  lockMs: number;
  producer: () => Promise<T>;
}

export async function memo<T>(
  key: string,
  windowMs: number,
  producer: () => Promise<T>,
  options: MemoOptions = {},
): Promise<T> {
  const ctx: Ctx<T> = {
    key,
    ckey: tenantKey(`cache:${key}`),
    lockKey: tenantKey(`lock:${key}`),
    windowMs,
    bucket: Math.floor(Date.now() / windowMs),
    maxStaleMs: options.maxStaleMs ?? windowMs * DEFAULT_STALE_WINDOWS,
    lockMs: options.lockMs ?? DEFAULT_LOCK_MS,
    producer: timedProducer(key, producer), // explorer_cache_refresh_seconds (PAR-531)
  };

  // L1: this pod already has the current window — serve without hitting Redis.
  const cached = l1.get<T>(key);
  if (cached && cached.bucket >= ctx.bucket) {
    recordCacheResult(key, 'hit_l1');
    return cached.value;
  }

  const r = redis();
  if (!r) return memoLocal(ctx, cached);

  let raw: string | null;
  try {
    raw = await r.get(ctx.ckey);
  } catch {
    return memoLocal(ctx, cached);
  }

  const stored = raw ? parseStored<T>(raw, windowMs) : null;
  if (stored) l1.set(key, stored);
  // L1 can outlive the Redis copy (eviction, flush); keep whichever is newer.
  const entry = stored && (!cached || stored.at >= cached.at) ? stored : cached;
  if (entry) {
    if (entry.bucket >= ctx.bucket) {
      recordCacheResult(key, 'hit_redis');
      return entry.value;
    }
    if (Date.now() - entry.at <= ctx.maxStaleMs) {
      // Stale window → serve stale now, recompute once across the fleet.
      recordCacheResult(key, 'stale');
      once(`refresh:${key}`, () => revalidate(ctx)).catch(() => {});
      return entry.value;
    }
  }

  // Cold (never computed, past the hygiene TTL, or older than maxStaleMs). The
  // only blocking path.
  return coldCompute(ctx, entry ?? null);
}

function parseStored<T>(raw: string, windowMs: number): Entry<T> | null {
  try {
    // superjson (not JSON) so Date/BigInt etc. in cached rows round-trip
    // faithfully — a plain-JSON Date would come back as a string and break
    // callers that expect a Date (e.g. .toISOString() in CSV exports).
    const obj = superjson.parse(raw) as { bucket?: unknown; value: T; at?: unknown } | null;
    if (typeof obj?.bucket !== 'number') return null;
    // Values written before `at` existed: assume computed at the start of their window.
    const at = typeof obj.at === 'number' ? obj.at : obj.bucket * windowMs;
    return { bucket: obj.bucket, value: obj.value, at, size: raw.length };
  } catch {
    return null;
  }
}

/** A refresh or cold recompute failed and the previous value is being served. */
function reportRefreshFailure(key: string, err: unknown): void {
  recordCacheResult(key, 'refresh_fail');
  // A recompute that keeps failing (e.g. a query past max_statement_time)
  // otherwise serves the last good value with nothing to show why (PAR-407).
  // The key is a constant format string's argument, never the format itself: keys
  // can carry user text (search prefixes), and console treats % sequences in the
  // first argument as directives.
  console.warn('[cache] recompute of %s failed; serving the previous value:', key, err);
}

type Lock = { token: string } | 'held' | 'unavailable';

async function acquireLock(lockKey: string, lockMs: number): Promise<Lock> {
  const r = redis();
  if (!r) return 'unavailable';
  const token = `${process.pid}:${Math.random().toString(36).slice(2)}`;
  try {
    // Self-expiring, so a dead holder can't wedge the key.
    return (await r.set(lockKey, token, 'PX', lockMs, 'NX')) === 'OK' ? { token } : 'held';
  } catch {
    return 'unavailable';
  }
}

async function releaseLock(lockKey: string, lock: Lock): Promise<void> {
  if (typeof lock === 'string') return;
  const r = redis();
  if (!r) return;
  try {
    // Only delete our own lock: if we overran the TTL another pod may hold it now.
    if ((await r.get(lockKey)) === lock.token) await r.del(lockKey);
  } catch {
    /* lock will expire on its own */
  }
}

/** Persist as last-known-good and keep it in L1. Storage is best effort. */
async function publish<T>(ctx: Ctx<T>, value: T): Promise<void> {
  const at = Date.now();
  let serialized: string | null = null;
  try {
    serialized = superjson.stringify({ bucket: ctx.bucket, value, at });
  } catch {
    serialized = null;
  }
  l1.set(ctx.key, { bucket: ctx.bucket, value, at, size: serialized?.length ?? 1024 });
  const r = redis();
  if (!r || serialized === null) return;
  try {
    await r.set(ctx.ckey, serialized, 'PX', PERSIST_MS);
  } catch {
    /* a failed write just means the next request recomputes */
  }
}

/** Background recompute for a stale window, guarded by a fleet-wide Redis lock. */
async function revalidate<T>(ctx: Ctx<T>): Promise<void> {
  const lock = await acquireLock(ctx.lockKey, ctx.lockMs);
  if (typeof lock === 'string') return; // another pod is refreshing, or Redis is down
  try {
    await publish(ctx, await ctx.producer());
  } catch (e) {
    reportRefreshFailure(ctx.key, e); // the stale value stays in place; the next window retries
  } finally {
    await releaseLock(ctx.lockKey, lock);
  }
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Another pod holds the compute lock: poll Redis for its result until the lock
 * goes away or expires. Returns the published entry, or null to compute here.
 */
async function waitForPeer<T>(ctx: Ctx<T>, stale: Entry<T> | null): Promise<Entry<T> | null> {
  const r = redis();
  if (!r) return null;
  const pollMs = Math.max(25, Math.min(150, ctx.lockMs / 10));
  let deadline = Date.now() + ctx.lockMs;
  try {
    const ttl = await r.pttl(ctx.lockKey);
    if (ttl > 0) deadline = Date.now() + Math.min(ttl, ctx.lockMs);
  } catch {
    return null;
  }
  // A value newer than the one that sent us here, and still young enough to serve.
  const readPublished = async (): Promise<Entry<T> | null> => {
    const raw = await r.get(ctx.ckey);
    const entry = raw ? parseStored<T>(raw, ctx.windowMs) : null;
    if (!entry || (stale && entry.at <= stale.at) || Date.now() - entry.at > ctx.maxStaleMs) return null;
    l1.set(ctx.key, entry);
    return entry;
  };
  try {
    for (;;) {
      const entry = await readPublished();
      if (entry) return entry;
      // Lock gone: the holder either just published (re-read once to close the
      // race between its write and its release) or failed without publishing.
      if ((await r.exists(ctx.lockKey)) === 0) return await readPublished();
      if (Date.now() >= deadline) return null;
      await sleep(pollMs);
    }
  } catch {
    return null;
  }
}

function coldCompute<T>(ctx: Ctx<T>, stale: Entry<T> | null): Promise<T> {
  recordCacheResult(ctx.key, 'miss');
  return once(ctx.key, async () => {
    let lock = await acquireLock(ctx.lockKey, ctx.lockMs);
    if (lock === 'held') {
      const peer = await waitForPeer(ctx, stale);
      if (peer) return peer.value;
      // Waited out the holder: compute here, taking the lock if it is free now
      // so pods arriving later wait on us.
      lock = await acquireLock(ctx.lockKey, ctx.lockMs);
    }
    try {
      const value = await ctx.producer();
      await publish(ctx, value);
      return value;
    } catch (e) {
      if (!stale) throw e;
      reportRefreshFailure(ctx.key, e);
      return stale.value;
    } finally {
      await releaseLock(ctx.lockKey, lock);
    }
  });
}

/** Per-pod in-memory SWR fallback when Redis is unavailable. */
async function memoLocal<T>(ctx: Ctx<T>, cached: Entry<T> | undefined): Promise<T> {
  if (cached) {
    if (cached.bucket >= ctx.bucket) return cached.value;
    if (Date.now() - cached.at <= ctx.maxStaleMs) {
      recordCacheResult(ctx.key, 'stale');
      once(`refresh:${ctx.key}`, async () => publish(ctx, await ctx.producer())).catch((e) =>
        reportRefreshFailure(ctx.key, e),
      );
      return cached.value;
    }
  }
  recordCacheResult(ctx.key, 'miss');
  return once(ctx.key, async () => {
    try {
      const value = await ctx.producer();
      await publish(ctx, value);
      return value;
    } catch (e) {
      if (!cached) throw e;
      reportRefreshFailure(ctx.key, e);
      return cached.value;
    }
  });
}

/** Test seam: empty this pod's L1 and in-flight map, optionally with smaller bounds. */
export function resetCacheForTests(limits: { maxEntries?: number; maxBytes?: number } = {}): void {
  l1.reset(limits.maxEntries ?? L1_MAX_ENTRIES, limits.maxBytes ?? L1_MAX_BYTES);
  inflight.clear();
}

/** Test seam: L1 occupancy, oldest key first. */
export function cacheStatsForTests(): { entries: number; bytes: number; keys: string[] } {
  return l1.stats();
}
