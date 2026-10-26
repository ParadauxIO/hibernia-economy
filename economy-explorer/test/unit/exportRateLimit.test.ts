import { describe, expect, it } from 'vitest';
import { EXPORT_LIMIT, memoryStore, takeExportSlot, type CounterStore } from '@/lib/export/rateLimit';

describe('takeExportSlot', () => {
  const T = 1_800_000_000_000; // aligned to a minute

  it('allows EXPORT_LIMIT exports per viewer per minute, then refuses with Retry-After', async () => {
    const store = memoryStore(() => T);
    expect(EXPORT_LIMIT).toBe(10);
    for (let i = 0; i < 10; i++) {
      const d = await takeExportSlot('alice', { store, now: T + 15_000 });
      expect(d).toMatchObject({ allowed: true, remaining: 9 - i });
    }
    expect(await takeExportSlot('alice', { store, now: T + 15_000 })).toEqual({ allowed: false, remaining: 0, retryAfterS: 45 });
    // Another viewer has their own budget.
    expect((await takeExportSlot('bob', { store, now: T + 15_000 })).allowed).toBe(true);
  });

  it('starts a fresh budget in the next clock-aligned window', async () => {
    let now = T;
    const store = memoryStore(() => now);
    for (let i = 0; i < 3; i++) await takeExportSlot('carol', { store, now, limit: 3 });
    expect((await takeExportSlot('carol', { store, now, limit: 3 })).allowed).toBe(false);
    now = T + 60_000;
    expect(await takeExportSlot('carol', { store, now, limit: 3 })).toMatchObject({ allowed: true, remaining: 2 });
    expect(store.size()).toBe(1); // the expired window was dropped
  });

  it('keys on the tenant, the viewer and the window', async () => {
    const keys: string[] = [];
    const store: CounterStore = { incr: async (key) => { keys.push(key); return 1; } };
    await takeExportSlot('sub-1', { store, now: T + 61_000 });
    expect(keys).toEqual([`ee:default:ratelimit:export:sub-1:${T / 60_000 + 1}`]);
  });

  it('falls back to a local counter when Redis fails, so it never fails open', async () => {
    const broken: CounterStore = { incr: async () => { throw new Error('redis down'); } };
    const decisions = [];
    for (let i = 0; i < 3; i++) decisions.push(await takeExportSlot('dave-fallback', { store: broken, now: T, limit: 2 }));
    expect(decisions.map((d) => d.allowed)).toEqual([true, true, false]);
  });
});
