import { describe, expect, it, vi } from 'vitest';
import { ACCOUNT_WINDOW_SCRIPT, checkAccountRateLimit } from '@/lib/requestRateLimit';

describe('shared account request limit', () => {
  it('admits the budget and rejects the next request with the remaining TTL', async () => {
    let count = 0;
    const store = { eval: vi.fn(async () => [++count, 12_001]) };
    expect(await checkAccountRateLimit('signed-subject', { limit: '2', store })).toBeNull();
    expect(await checkAccountRateLimit('signed-subject', { limit: '2', store })).toBeNull();
    expect(await checkAccountRateLimit('signed-subject', { limit: '2', store })).toEqual({ status: 429, retryAfter: 13 });
    const keys = store.eval.mock.calls as unknown as unknown[][];
    expect(keys[0]![0]).toBe(ACCOUNT_WINDOW_SCRIPT);
    expect(keys[0]![2]).toBe(keys[2]![2]);
    expect(keys[0]![2]).not.toContain('signed-subject');
  });
  it('isolates account budgets', async () => {
    const store = { eval: vi.fn(async () => [1, 60_000]) };
    await checkAccountRateLimit('alice', { limit: '60', store });
    await checkAccountRateLimit('bob', { limit: '60', store });
    const calls = store.eval.mock.calls as unknown as unknown[][];
    expect(calls[0]![2]).not.toBe(calls[1]![2]);
  });
  it('fails closed for a missing or unavailable shared store', async () => {
    expect(await checkAccountRateLimit('a', { limit: '60', store: null })).toEqual({ status: 503, retryAfter: 60 });
    const store = { eval: vi.fn().mockRejectedValue(new Error('offline')) };
    expect(await checkAccountRateLimit('a', { limit: '60', store })).toEqual({ status: 503, retryAfter: 60 });
  });
  it.each(['0', '-1', 'NaN', '1.5', ''])('fails closed for invalid configuration %s', async limit => {
    expect(await checkAccountRateLimit('a', { limit, store: null })).toEqual({ status: 503, retryAfter: 60 });
  });
  it('fails closed when the shared store hangs', async () => {
    vi.useFakeTimers();
    try {
      const store = { eval: vi.fn(() => new Promise<unknown>(() => {})) };
      const pending = checkAccountRateLimit('a', { limit: '60', store });
      await vi.advanceTimersByTimeAsync(2000);
      expect(await pending).toEqual({ status: 503, retryAfter: 60 });
    } finally {
      vi.useRealTimers();
    }
  });
  it('fails closed for corrupt store replies', async () => {
    const store = { eval: vi.fn(async () => [1, -1]) };
    expect(await checkAccountRateLimit('a', { limit: '60', store })).toEqual({ status: 503, retryAfter: 60 });
  });
});
