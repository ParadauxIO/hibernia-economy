// Throttle for relationship-read audit rows: one row per viewer, target and
// clock hour, so refreshing a page doesn't write a row per request. Redis makes
// the window shared across pods; the in-memory fallback keeps it per pod when
// Redis is missing or failing. Pure apart from the store it is handed.

export const RELATIONSHIP_READ_WINDOW_MS = 60 * 60 * 1000;

/** `SET key NX PX ttl`: resolves true when the key was new (this caller records). */
export interface ThrottleStore {
  claim(key: string, ttlMs: number): Promise<boolean>;
}

/** The throttle key for one viewer/target pair in the hour containing `now`. */
export function relationshipReadKey(viewerSub: string, targetType: string, targetId: string, now: Date): string {
  const hour = Math.floor(now.getTime() / RELATIONSHIP_READ_WINDOW_MS);
  return `audit:rel:${hour}:${targetType}:${targetId}:${viewerSub}`;
}

/**
 * An in-memory store with expiry. Bounded: when full it drops expired entries,
 * then the oldest, so a long-lived pod can't grow without limit.
 */
export function memoryThrottleStore(maxEntries = 10_000, clock: () => number = Date.now): ThrottleStore {
  const seen = new Map<string, number>();
  return {
    async claim(key, ttlMs) {
      const now = clock();
      const expires = seen.get(key);
      if (expires !== undefined && expires > now) return false;
      if (seen.size >= maxEntries) {
        for (const [k, e] of seen) if (e <= now) seen.delete(k);
        while (seen.size >= maxEntries) {
          const oldest = seen.keys().next().value;
          if (oldest === undefined) break;
          seen.delete(oldest);
        }
      }
      seen.delete(key);
      seen.set(key, now + ttlMs);
      return true;
    },
  };
}

/** Minimal slice of an ioredis client: `set(key, value, 'PX', ttl, 'NX')`. */
export interface RedisLike {
  set(key: string, value: string, px: 'PX', ttl: number, nx: 'NX'): Promise<'OK' | null>;
}

/**
 * Redis first, the fallback when Redis is absent or errors. A Redis failure
 * never throws: the read is still audited at most once per pod and hour.
 */
export function redisThrottleStore(client: () => RedisLike | null, fallback: ThrottleStore, prefix: (key: string) => string = (k) => k): ThrottleStore {
  return {
    async claim(key, ttlMs) {
      const r = client();
      if (!r) return fallback.claim(key, ttlMs);
      try {
        return (await r.set(prefix(key), '1', 'PX', ttlMs, 'NX')) === 'OK';
      } catch {
        return fallback.claim(key, ttlMs);
      }
    },
  };
}
