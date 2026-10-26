import { describe, it, expect } from 'vitest';
import { LeaderLease, LEASE_TTL_MS, type LeaseRedis } from '@/lib/rollup/lease';

/** In-memory Redis with SET NX PX and the lease's two compare-and-act scripts. */
class FakeRedis implements LeaseRedis {
  now = 0;
  down = false;
  private store = new Map<string, { value: string; expires: number }>();

  private live(key: string) {
    const entry = this.store.get(key);
    if (entry && entry.expires <= this.now) this.store.delete(key);
    return this.store.get(key);
  }

  get(key: string): string | null {
    return this.live(key)?.value ?? null;
  }

  async set(key: string, value: string, ...[, ttlMs]: [px: 'PX', ttlMs: number, nx: 'NX']): Promise<'OK' | null> {
    if (this.down) throw new Error('connection refused');
    if (this.live(key)) return null;
    this.store.set(key, { value, expires: this.now + ttlMs });
    return 'OK';
  }

  async eval(script: string, _numKeys: number, ...args: (string | number)[]): Promise<unknown> {
    if (this.down) throw new Error('connection refused');
    const [key, holder, ttl] = args.map(String) as [string, string, string?];
    const entry = this.live(key);
    if (!entry || entry.value !== holder) return 0;
    if (script.includes("'pexpire'")) { entry.expires = this.now + Number(ttl); return 1; }
    if (script.includes("'del'")) { this.store.delete(key); return 1; }
    throw new Error('unexpected script');
  }
}

const KEY = 'ee:test:rollup:leader';

describe('LeaderLease', () => {
  it('lets one holder lead; the other waits until the lease expires', async () => {
    const redis = new FakeRedis();
    const a = new LeaderLease(redis, KEY, 'pod-a');
    const b = new LeaderLease(redis, KEY, 'pod-b');
    expect(await a.acquire()).toBe(true);
    expect(await b.acquire()).toBe(false);
    expect(redis.get(KEY)).toBe('pod-a');

    redis.now += 10_000;
    expect(await a.acquire()).toBe(true); // re-acquiring while held renews
    redis.now += LEASE_TTL_MS - 1;
    expect(await b.acquire()).toBe(false); // renewed, so still a's

    redis.now += 1; // a stalls past the TTL without renewing
    expect(await b.acquire()).toBe(true);
    expect(await a.renew()).toBe(false);
    expect(a.held).toBe(false);
    expect(redis.get(KEY)).toBe('pod-b');
  });

  it('never releases a successor’s lease', async () => {
    const redis = new FakeRedis();
    const a = new LeaderLease(redis, KEY, 'pod-a');
    const b = new LeaderLease(redis, KEY, 'pod-b');
    await a.acquire();
    redis.now += LEASE_TTL_MS;
    await b.acquire();
    await a.release();
    expect(redis.get(KEY)).toBe('pod-b');
    await b.release();
    expect(redis.get(KEY)).toBeNull();
    expect(b.held).toBe(false);
  });

  it('does not lead while Redis is unreachable', async () => {
    const redis = new FakeRedis();
    const a = new LeaderLease(redis, KEY, 'pod-a');
    redis.down = true;
    expect(await a.acquire()).toBe(false);
    redis.down = false;
    expect(await a.acquire()).toBe(true);
    redis.down = true;
    expect(await a.renew()).toBe(false);
    await expect(a.release()).resolves.toBeUndefined();
  });

  it('always leads without Redis (a single process)', async () => {
    const lease = new LeaderLease(null, KEY, 'only');
    expect(await lease.acquire()).toBe(true);
    expect(await lease.renew()).toBe(true);
    await lease.release();
    expect(lease.held).toBe(false);
  });
});
