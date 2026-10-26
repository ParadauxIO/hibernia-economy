import { describe, expect, it, vi } from 'vitest';
import {
  RELATIONSHIP_READ_WINDOW_MS,
  memoryThrottleStore,
  redisThrottleStore,
  relationshipReadKey,
  type RedisLike,
} from '@/lib/auditThrottle';

const HOUR = RELATIONSHIP_READ_WINDOW_MS;

describe('relationshipReadKey', () => {
  it('is stable within a clock hour and changes at the next one', () => {
    const at = (iso: string) => relationshipReadKey('sub-1', 'account', '5', new Date(iso));
    expect(at('2026-10-26T10:00:00Z')).toBe(at('2026-10-26T10:59:59Z'));
    expect(at('2026-10-26T10:59:59Z')).not.toBe(at('2026-10-26T11:00:00Z'));
  });

  it('separates viewers and targets', () => {
    const now = new Date('2026-10-26T10:00:00Z');
    const keys = new Set([
      relationshipReadKey('sub-1', 'account', '5', now),
      relationshipReadKey('sub-2', 'account', '5', now),
      relationshipReadKey('sub-1', 'account', '6', now),
    ]);
    expect(keys.size).toBe(3);
  });
});

describe('memoryThrottleStore', () => {
  it('lets the first claim through and refuses repeats until the window expires', async () => {
    let now = 1_000;
    const store = memoryThrottleStore(100, () => now);
    expect(await store.claim('k', HOUR)).toBe(true);
    expect(await store.claim('k', HOUR)).toBe(false);
    expect(await store.claim('other', HOUR)).toBe(true);
    now += HOUR;
    expect(await store.claim('k', HOUR)).toBe(true);
  });

  it('stays bounded, evicting expired entries first and then the oldest', async () => {
    let now = 0;
    const store = memoryThrottleStore(2, () => now);
    await store.claim('a', 10);
    await store.claim('b', HOUR);
    now = 20; // a has expired
    expect(await store.claim('c', HOUR)).toBe(true);
    expect(await store.claim('b', HOUR)).toBe(false); // b survived
    expect(await store.claim('d', HOUR)).toBe(true); // evicts the oldest live entry (b)
    expect(await store.claim('b', HOUR)).toBe(true);
  });
});

describe('redisThrottleStore', () => {
  function fakeRedis(): RedisLike & { keys: Map<string, number> } {
    const keys = new Map<string, number>();
    return {
      keys,
      set: vi.fn(async (key: string, _v: string, _px: 'PX', ttl: number) => {
        if (keys.has(key)) return null;
        keys.set(key, ttl);
        return 'OK' as const;
      }),
    };
  }

  it('claims with SET NX PX on the prefixed key, shared by every pod', async () => {
    const redis = fakeRedis();
    const podA = redisThrottleStore(() => redis, memoryThrottleStore(), (k) => `ee:t:${k}`);
    const podB = redisThrottleStore(() => redis, memoryThrottleStore(), (k) => `ee:t:${k}`);
    expect(await podA.claim('k', HOUR)).toBe(true);
    expect(await podB.claim('k', HOUR)).toBe(false);
    expect(redis.set).toHaveBeenCalledWith('ee:t:k', '1', 'PX', HOUR, 'NX');
  });

  it('falls back to the in-memory store when Redis is absent or failing, without throwing', async () => {
    const absent = redisThrottleStore(() => null, memoryThrottleStore());
    expect(await absent.claim('k', HOUR)).toBe(true);
    expect(await absent.claim('k', HOUR)).toBe(false);

    const failing: RedisLike = { set: vi.fn(async () => { throw new Error('ECONNREFUSED'); }) };
    const store = redisThrottleStore(() => failing, memoryThrottleStore());
    expect(await store.claim('k', HOUR)).toBe(true);
    expect(await store.claim('k', HOUR)).toBe(false);
  });
});
