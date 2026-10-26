import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import superjson from 'superjson';
import type * as CacheModuleType from '@/lib/cache';

// A minimal in-memory Redis with the commands lib/cache uses, shared by every
// "pod" (a fresh import of lib/cache) so cross-pod behaviour can be exercised.
class FakeRedis {
  store = new Map<string, { value: string; expiresAt: number | null }>();
  calls: string[] = [];
  private live(key: string) {
    const e = this.store.get(key);
    if (e && e.expiresAt !== null && e.expiresAt <= Date.now()) {
      this.store.delete(key);
      return undefined;
    }
    return e;
  }
  async get(key: string) {
    this.calls.push(`get ${key}`);
    return this.live(key)?.value ?? null;
  }
  async set(key: string, value: string, px: 'PX', ms: number, nx?: 'NX') {
    this.calls.push(`set ${key}${nx ? ' NX' : ''}`);
    if (nx && this.live(key)) return null;
    this.store.set(key, { value, expiresAt: Date.now() + ms });
    return 'OK';
  }
  async del(key: string) {
    return this.store.delete(key) ? 1 : 0;
  }
  async exists(key: string) {
    return this.live(key) ? 1 : 0;
  }
  async pttl(key: string) {
    const e = this.live(key);
    if (!e) return -2;
    return e.expiresAt === null ? -1 : e.expiresAt - Date.now();
  }
}

const shared = vi.hoisted(() => ({ client: null as unknown }));
vi.mock('@/lib/redis', () => ({ redis: () => shared.client, tenantKey: (s: string) => `t:${s}` }));

type CacheModule = typeof CacheModuleType;
/** A fresh module instance: its own L1 and in-flight map, like a separate pod. */
async function pod(): Promise<CacheModule> {
  vi.resetModules();
  return import('@/lib/cache');
}

const deferred = <T,>() => {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};
const tick = () => new Promise((r) => setTimeout(r, 0));

const WINDOW = 60_000;
const T0 = Date.UTC(2026, 9, 26, 12, 0, 0);

let redis: FakeRedis;
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(T0);
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  redis = new FakeRedis();
  shared.client = redis;
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('L1 LRU (no Redis)', () => {
  beforeEach(() => {
    shared.client = null;
  });

  it('evicts the least recently used entry past the entry cap', async () => {
    const cache = await pod();
    cache.resetCacheForTests({ maxEntries: 3 });
    for (const k of ['a', 'b', 'c']) await cache.memo(k, WINDOW, async () => k);
    await cache.memo('a', WINDOW, async () => 'unused'); // touch a: b is now oldest
    await cache.memo('d', WINDOW, async () => 'd');
    expect(cache.cacheStatsForTests().keys).toEqual(['c', 'a', 'd']);
    const producer = vi.fn(async () => 'b again');
    expect(await cache.memo('b', WINDOW, producer)).toBe('b again');
    expect(producer).toHaveBeenCalledTimes(1);
  });

  it('evicts by approximate byte size and never keeps an entry bigger than the cap', async () => {
    const cache = await pod();
    cache.resetCacheForTests({ maxBytes: 2_000 });
    await cache.memo('x', WINDOW, async () => 'x'.repeat(800));
    await cache.memo('y', WINDOW, async () => 'y'.repeat(800));
    await cache.memo('z', WINDOW, async () => 'z'.repeat(800));
    const stats = cache.cacheStatsForTests();
    expect(stats.keys).toEqual(['y', 'z']);
    expect(stats.bytes).toBeLessThanOrEqual(2_000);
    await cache.memo('huge', WINDOW, async () => 'h'.repeat(5_000));
    expect(cache.cacheStatsForTests().keys).not.toContain('huge');
  });

  it('single-flights concurrent misses within a pod', async () => {
    const cache = await pod();
    const gate = deferred<string>();
    const producer = vi.fn(() => gate.promise);
    const all = Promise.all([1, 2, 3].map(() => cache.memo('k', WINDOW, producer)));
    await tick();
    gate.resolve('v');
    expect(await all).toEqual(['v', 'v', 'v']);
    expect(producer).toHaveBeenCalledTimes(1);
  });

  it('serves stale within maxStaleMs and refreshes in the background', async () => {
    const cache = await pod();
    await cache.memo('k', WINDOW, async () => 'old');
    vi.setSystemTime(T0 + 5 * WINDOW);
    const gate = deferred<string>();
    expect(await cache.memo('k', WINDOW, () => gate.promise)).toBe('old');
    gate.resolve('new');
    await tick();
    expect(await cache.memo('k', WINDOW, async () => 'unused')).toBe('new');
  });

  it('blocks on a recompute once a value is older than maxStaleMs', async () => {
    const cache = await pod();
    await cache.memo('k', WINDOW, async () => 'old', { maxStaleMs: 10 * WINDOW });
    vi.setSystemTime(T0 + 11 * WINDOW);
    expect(await cache.memo('k', WINDOW, async () => 'new', { maxStaleMs: 10 * WINDOW })).toBe('new');
  });

  it('defaults maxStaleMs to 60 windows (1 h for 60 s)', async () => {
    const cache = await pod();
    await cache.memo('k', WINDOW, async () => 'old');
    vi.setSystemTime(T0 + 59 * WINDOW);
    expect(await cache.memo('k', WINDOW, async () => 'bg')).toBe('old');
    await tick();
    const cache2 = await pod();
    await cache2.memo('q', WINDOW, async () => 'old');
    vi.setSystemTime(T0 + 59 * WINDOW + 61 * WINDOW);
    expect(await cache2.memo('q', WINDOW, async () => 'fresh')).toBe('fresh');
  });

  it('serves the old value when a too-stale recompute fails, and does not cache the failure', async () => {
    const cache = await pod();
    await cache.memo('k', WINDOW, async () => 'old', { maxStaleMs: WINDOW });
    vi.setSystemTime(T0 + 3 * WINDOW);
    const failing = vi.fn(async () => {
      throw new Error('max_statement_time exceeded');
    });
    expect(await cache.memo('k', WINDOW, failing, { maxStaleMs: WINDOW })).toBe('old');
    expect(await cache.memo('k', WINDOW, failing, { maxStaleMs: WINDOW })).toBe('old');
    expect(failing).toHaveBeenCalledTimes(2); // retried, not remembered
    expect(await cache.memo('k', WINDOW, async () => 'new', { maxStaleMs: WINDOW })).toBe('new');
  });

  it('throws on a cold failure with nothing to fall back to, and retries next time', async () => {
    const cache = await pod();
    await expect(cache.memo('k', WINDOW, async () => Promise.reject(new Error('boom')))).rejects.toThrow('boom');
    expect(await cache.memo('k', WINDOW, async () => 'ok')).toBe('ok');
  });
});

describe('with Redis', () => {
  it('publishes to Redis with a computed-at timestamp and serves other pods from it', async () => {
    const a = await pod();
    expect(await a.memo('k', WINDOW, async () => ({ when: new Date(T0), n: 1n }))).toEqual({ when: new Date(T0), n: 1n });
    const stored = superjson.parse(redis.store.get('t:cache:k')!.value) as { bucket: number; at: number };
    expect(stored).toMatchObject({ bucket: Math.floor(T0 / WINDOW), at: T0 });
    const b = await pod();
    const producer = vi.fn(async () => ({ when: new Date(0), n: 2n }));
    expect(await b.memo('k', WINDOW, producer)).toEqual({ when: new Date(T0), n: 1n });
    expect(producer).not.toHaveBeenCalled();
  });

  it('cold miss: one pod computes while the others wait for its value', async () => {
    const a = await pod();
    const b = await pod();
    const c = await pod();
    const gate = deferred<string>();
    const pa = vi.fn(() => gate.promise);
    const pb = vi.fn(async () => 'from b');
    const pc = vi.fn(async () => 'from c');
    const ra = a.memo('cold', WINDOW, pa, { lockMs: 2_000 });
    await tick();
    await tick();
    const rb = b.memo('cold', WINDOW, pb, { lockMs: 2_000 });
    const rc = c.memo('cold', WINDOW, pc, { lockMs: 2_000 });
    await new Promise((r) => setTimeout(r, 120)); // b and c are polling
    gate.resolve('from a');
    expect(await Promise.all([ra, rb, rc])).toEqual(['from a', 'from a', 'from a']);
    expect(pa).toHaveBeenCalledTimes(1);
    expect(pb).not.toHaveBeenCalled();
    expect(pc).not.toHaveBeenCalled();
    expect(redis.store.has('t:lock:cold')).toBe(false); // released
  });

  it('a waiting pod computes itself when the holder fails without publishing', async () => {
    const a = await pod();
    const b = await pod();
    const gate = deferred<string>();
    const ra = a.memo('k', WINDOW, () => gate.promise, { lockMs: 2_000 }).catch((e: Error) => e.message);
    await tick();
    await tick();
    const rb = b.memo('k', WINDOW, async () => 'from b', { lockMs: 2_000 });
    await new Promise((r) => setTimeout(r, 60));
    gate.reject(new Error('timeout'));
    expect(await ra).toBe('timeout');
    expect(await rb).toBe('from b');
  });

  it('a waiting pod computes itself once the lock TTL runs out', async () => {
    vi.useRealTimers(); // the lock has to actually expire
    const a = await pod();
    const b = await pod();
    void a.memo('hung', WINDOW, () => new Promise<string>(() => {}), { lockMs: 300 }); // hung holder
    await tick();
    await tick();
    const started = performance.now();
    expect(await b.memo('hung', WINDOW, async () => 'from b', { lockMs: 300 })).toBe('from b');
    expect(performance.now() - started).toBeGreaterThanOrEqual(200);
  });

  it('a stale window refreshes once across the fleet and serves stale meanwhile', async () => {
    const a = await pod();
    await a.memo('k', WINDOW, async () => 'v1');
    vi.setSystemTime(T0 + 2 * WINDOW);
    const b = await pod();
    const c = await pod();
    const gate = deferred<string>();
    const pb = vi.fn(() => gate.promise);
    const pc = vi.fn(async () => 'from c');
    expect(await b.memo('k', WINDOW, pb)).toBe('v1');
    await tick();
    await tick();
    expect(await c.memo('k', WINDOW, pc)).toBe('v1');
    gate.resolve('v2');
    await tick();
    await tick();
    expect(pb).toHaveBeenCalledTimes(1);
    expect(pc).not.toHaveBeenCalled();
    const d = await pod();
    expect(await d.memo('k', WINDOW, async () => 'unused')).toBe('v2');
  });

  it('a failed background refresh keeps the stale value, logs, and is retried next window', async () => {
    const a = await pod();
    await a.memo('k', WINDOW, async () => 'v1');
    vi.setSystemTime(T0 + 2 * WINDOW);
    const failing = vi.fn(async () => {
      throw new Error('boom');
    });
    expect(await a.memo('k', WINDOW, failing)).toBe('v1');
    await tick();
    await tick();
    expect(failing).toHaveBeenCalledTimes(1);
    expect(console.warn).toHaveBeenCalled();
    expect(redis.store.has('t:lock:k')).toBe(false);
    vi.setSystemTime(T0 + 3 * WINDOW);
    expect(await a.memo('k', WINDOW, async () => 'v3')).toBe('v1');
    await tick();
    await tick();
    expect(await a.memo('k', WINDOW, async () => 'unused')).toBe('v3');
  });

  it('a too-stale value forces a blocking recompute, falling back to it if that fails', async () => {
    const a = await pod();
    await a.memo('k', WINDOW, async () => 'ancient', { maxStaleMs: 5 * WINDOW });
    vi.setSystemTime(T0 + 10 * WINDOW);
    const b = await pod();
    expect(await b.memo('k', WINDOW, async () => Promise.reject(new Error('timeout')), { maxStaleMs: 5 * WINDOW })).toBe('ancient');
    expect(await b.memo('k', WINDOW, async () => 'fresh', { maxStaleMs: 5 * WINDOW })).toBe('fresh');
  });

  it('reads values written before computed-at was recorded', async () => {
    const bucket = Math.floor(T0 / WINDOW);
    redis.store.set('t:cache:k', { value: superjson.stringify({ bucket: bucket - 1, value: 'legacy' }), expiresAt: null });
    const a = await pod();
    expect(await a.memo('k', WINDOW, async () => 'bg')).toBe('legacy'); // one window old: stale, not too stale
  });

  it('falls back to local caching when Redis errors', async () => {
    const a = await pod();
    vi.spyOn(redis, 'get').mockRejectedValue(new Error('ECONNRESET'));
    const producer = vi.fn(async () => 'local');
    expect(await a.memo('k', WINDOW, producer)).toBe('local');
    expect(await a.memo('k', WINDOW, producer)).toBe('local');
    expect(producer).toHaveBeenCalledTimes(1);
  });
});
