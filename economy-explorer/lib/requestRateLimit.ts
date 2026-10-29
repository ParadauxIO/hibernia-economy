import { createHash } from 'node:crypto';
import { redis, tenantKey } from '@/lib/redis';

// Atomic, server-timed window shared by all application replicas. Rejected
// requests do not extend the window. No local fallback: Redis failure closes
// protected routes before identity or market queries can run.
export const ACCOUNT_WINDOW_SCRIPT = `
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
return {count, redis.call('PTTL', KEYS[1])}
`;

type Store = { eval(script: string, keys: number, key: string, window: number): Promise<unknown> };
export type LimitDecision = { status: 429 | 503; retryAfter: number } | null;

export async function checkAccountRateLimit(
  subject: string,
  options: { limit?: string; store?: Store | null } = {},
): Promise<LimitDecision> {
  const configured = options.limit ?? process.env.ACCOUNT_REQUESTS_PER_MINUTE;
  if (configured === undefined) return null;
  const limit = Number(configured);
  if (!Number.isSafeInteger(limit) || limit < 1) return { status: 503, retryAfter: 60 };
  try {
    const store = options.store === undefined ? redis() : options.store;
    if (!store) return { status: 503, retryAfter: 60 };
    const key = tenantKey(`account-request-limit:${createHash('sha256').update(subject).digest('hex')}`);
    let timer: ReturnType<typeof setTimeout> | undefined;
    let result: unknown;
    try {
      result = await Promise.race([
        store.eval(ACCOUNT_WINDOW_SCRIPT, 1, key, 60_000),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error('Rate-limit store timeout')), 2000);
        }),
      ]);
    } finally {
      clearTimeout(timer);
    }
    if (!Array.isArray(result) || result.length !== 2) throw new Error('Invalid rate-limit response');
    const count = Number(result[0]);
    const ttl = Number(result[1]);
    if (!Number.isFinite(count) || count < 1 || !Number.isFinite(ttl) || ttl < 0) throw new Error('Invalid rate-limit window');
    return count > limit ? { status: 429, retryAfter: Math.max(1, Math.ceil(ttl / 1000)) } : null;
  } catch {
    return { status: 503, retryAfter: 60 };
  }
}
