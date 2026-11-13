import 'server-only';
import Redis from 'ioredis';

// Shared prod Redis (redis.production.svc.cluster.local) used as the explorer's
// cross-pod cache. Lazy singleton: created on first use, never at build time
// (all cached pages are force-dynamic / Node runtime). Returns null when
// REDIS_HOST is unset (local dev / any env without Redis) so lib/cache can fall
// back to a per-pod in-memory cache instead of failing.

let client: Redis | null | undefined;

export function redis(): Redis | null {
  if (client !== undefined) return client;
  const host = process.env.REDIS_HOST?.trim();
  if (!host) {
    client = null;
    return client;
  }
  client = new Redis({
    host,
    port: Number(process.env.REDIS_PORT ?? 6379),
    password: process.env.REDIS_PASSWORD || undefined,
    // Fail fast rather than queueing while disconnected — the caller falls back
    // to computing/serving locally, so a Redis blip must never hang a request.
    enableOfflineQueue: false,
    maxRetriesPerRequest: 2,
    connectTimeout: 3000,
  });
  // Swallow connection errors (logged once-ish) so a transient Redis outage
  // doesn't crash the process; commands reject and the caller handles it.
  client.on('error', (e: Error) => console.warn('[redis]', e.message));
  return client;
}

// One Redis instance is shared by both tenants (democracycraft / statecraft), so
// every key is namespaced by TENANT to keep their caches isolated.
const PREFIX = `ee:${(process.env.TENANT ?? 'default').trim().toLowerCase()}:`;

export function tenantKey(suffix: string): string {
  return PREFIX + suffix;
}

/** Wait for connection readiness without issuing or retrying a command. */
export async function waitForRedisReady(): Promise<void> {
  const store = redis();
  if (!store) throw new Error('Redis unavailable');
  if (store.status === 'ready') return;
  await new Promise<void>((resolve, reject) => {
    const cleanup = () => { clearTimeout(timer); store.off('ready', ready); store.off('error', failed); };
    const ready = () => { cleanup(); resolve(); };
    const failed = () => { cleanup(); reject(new Error('Redis unavailable')); };
    const timer = setTimeout(failed, 2000);
    store.once('ready', ready);
    store.once('error', failed);
  });
}
