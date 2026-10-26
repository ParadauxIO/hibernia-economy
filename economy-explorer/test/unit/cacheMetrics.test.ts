import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { metricsText, resetMetricsForTests } from '@/lib/metrics';

// explorer_cache_* counters on memo()'s Redis paths (PAR-531). Each fresh import
// of lib/cache is a separate "pod" with its own L1, sharing one fake Redis.
class FakeRedis {
  store = new Map<string, { value: string; expiresAt: number }>();
  private live(key: string) {
    const e = this.store.get(key);
    if (e && e.expiresAt <= Date.now()) this.store.delete(key);
    return this.store.get(key);
  }
  get(key: string) {
    return Promise.resolve(this.live(key)?.value ?? null);
  }
  set(key: string, value: string, _px: 'PX', ms: number, nx?: 'NX') {
    if (nx && this.live(key)) return Promise.resolve(null);
    this.store.set(key, { value, expiresAt: Date.now() + ms });
    return Promise.resolve('OK');
  }
  del(key: string) {
    return Promise.resolve(this.store.delete(key) ? 1 : 0);
  }
  exists(key: string) {
    return Promise.resolve(this.live(key) ? 1 : 0);
  }
  pttl(key: string) {
    const e = this.live(key);
    return Promise.resolve(e ? e.expiresAt - Date.now() : -2);
  }
}

const shared = vi.hoisted(() => ({ client: null as unknown }));
vi.mock('@/lib/redis', () => ({ redis: () => shared.client, tenantKey: (s: string) => `t:${s}` }));

async function pod() {
  vi.resetModules();
  return import('@/lib/cache');
}

async function count(result: string): Promise<number> {
  const line = (await metricsText()).body
    .split('\n')
    .find((l) => l.startsWith(`explorer_cache_requests_total{key_prefix="econ",result="${result}"}`));
  return line ? Number(line.split(' ').pop()) : 0;
}

const WINDOW = 60_000;
const T0 = Date.UTC(2026, 9, 26, 12, 0, 0);

beforeEach(() => {
  vi.stubEnv('TENANT', '');
  resetMetricsForTests();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(T0);
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  shared.client = new FakeRedis();
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

describe('memo() cache metrics with Redis', () => {
  it('counts miss, hit_l1, hit_redis, stale and refresh_fail where each happens', async () => {
    const a = await pod();
    await a.memo('econ:30', WINDOW, () => Promise.resolve(1)); // cold: computes and publishes
    await a.memo('econ:30', WINDOW, () => Promise.resolve(2)); // same pod, same window
    const b = await pod();
    expect(await b.memo('econ:30', WINDOW, () => Promise.resolve(3))).toBe(1); // another pod reads Redis
    expect([await count('miss'), await count('hit_l1'), await count('hit_redis')]).toEqual([1, 1, 1]);

    vi.setSystemTime(T0 + WINDOW);
    const c = await pod();
    expect(await c.memo('econ:30', WINDOW, () => Promise.reject(new Error('timeout')))).toBe(1);
    await vi.waitFor(async () => expect(await count('refresh_fail')).toBe(1));
    expect(await count('stale')).toBe(1);

    const refreshes = (await metricsText()).body
      .split('\n')
      .find((l) => l.startsWith('explorer_cache_refresh_seconds_count{key_prefix="econ"}'));
    expect(refreshes).toMatch(/ 2$/); // the cold compute and the failed background refresh
  });
});
