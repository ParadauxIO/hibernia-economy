import Redis from 'ioredis';
import { expect, it } from 'vitest';
import { ACCOUNT_WINDOW_SCRIPT, checkAccountRateLimit } from '@/lib/requestRateLimit';

// Run against an isolated Redis instance, never the production cache.
it.skipIf(!process.env.RATE_LIMIT_TEST_REDIS_URL)('shares an atomic budget across clients and expires the window', async () => {
  const first = new Redis(process.env.RATE_LIMIT_TEST_REDIS_URL!);
  const second = new Redis(process.env.RATE_LIMIT_TEST_REDIS_URL!);
  const subject = `test-${crypto.randomUUID()}`;
  const expiryKey = `test-expiry-${crypto.randomUUID()}`;
  try {
    const results = await Promise.all(Array.from({ length: 40 }, (_, i) =>
      checkAccountRateLimit(subject, { limit: '10', store: i % 2 ? first : second }),
    ));
    expect(results.filter(result => result === null)).toHaveLength(10);
    expect(results.filter(result => result?.status === 429)).toHaveLength(30);
    const initial = await first.eval(ACCOUNT_WINDOW_SCRIPT, 1, expiryKey, 50) as number[];
    expect(initial[0]).toBe(1);
    await new Promise(resolve => setTimeout(resolve, 100));
    const renewed = await second.eval(ACCOUNT_WINDOW_SCRIPT, 1, expiryKey, 50) as number[];
    expect(renewed[0]).toBe(1);
  } finally {
    await first.del(expiryKey);
    first.disconnect();
    second.disconnect();
  }
});
