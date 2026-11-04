import { describe, expect, it, vi, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { FeatureFlagSnapshot, FeatureFlagCache } from '@/lib/featureFlagSnapshot';

const cases = readFileSync('../common/src/test/resources/feature-flags.tsv', 'utf8').trim().split('\n').filter(line => !line.startsWith('#')).map(line => line.split('\t'));
afterEach(() => vi.useRealTimers());
describe('shared Java/TypeScript flag contract', () => {
  it.each(cases)('%s value %s age %s → %s', (key, enabled, age, expected) => {
    const snapshot = new FeatureFlagSnapshot(() => Number(age));
    snapshot.publish([{ name: key!, enabled: Number(enabled) }], 0);
    expect(snapshot.isEnabled(key!)).toBe(expected === 'true');
  });
  it('defaults off, replaces deleted rows and copies published state', () => {
    const snapshot = new FeatureFlagSnapshot(() => 0);
    expect(snapshot.isEnabled('business.preview')).toBe(false);
    const rows = [{ name: 'business.preview', enabled: 1 }];
    snapshot.publish(rows, 0); rows.length = 0;
    expect(snapshot.isEnabled('business.preview')).toBe(true);
    snapshot.publish([], 0);
    expect(snapshot.isEnabled('business.preview')).toBe(false);
  });
  it('coalesces reads, preserves bounded state after failure, expires and recovers', async () => {
    let now = 0;
    const load = vi.fn().mockResolvedValue([{ name: 'business.preview', enabled: 1 }]);
    const log = vi.fn();
    const flags = new FeatureFlagCache(load, () => now, log);
    expect(await Promise.all([flags.isEnabled('business.preview'), flags.isEnabled('business.preview')])).toEqual([true, true]);
    expect(load).toHaveBeenCalledTimes(1);
    now = 5_000; load.mockRejectedValue(new Error('offline'));
    expect(await flags.isEnabled('business.preview')).toBe(true);
    now = 30_000;
    expect(await flags.isEnabled('business.preview')).toBe(false);
    expect(log).toHaveBeenCalledTimes(1);
    now = 35_000; load.mockResolvedValue([{ name: 'business.preview', enabled: 1 }]);
    expect(await flags.isEnabled('business.preview')).toBe(true);
    expect(log).toHaveBeenCalledTimes(2);
    now = 40_000; load.mockResolvedValue([]);
    expect(await flags.isEnabled('business.preview')).toBe(false);
  });
  it('returns disabled on startup failure and never invokes a guarded effect', async () => {
    const flags = new FeatureFlagCache(async () => { throw new Error('offline'); }, () => 0, () => {});
    const effect = vi.fn();
    if (await flags.isEnabled('business.preview')) effect();
    expect(effect).not.toHaveBeenCalled();
  });
  it('bounds waits and expires enabled state even if a refresh never settles', async () => {
    vi.useFakeTimers();
    let now = 0;
    const load = vi.fn().mockResolvedValue([{ name: 'business.preview', enabled: 1 }]);
    const flags = new FeatureFlagCache(load, () => now);
    expect(await flags.isEnabled('business.preview')).toBe(true);
    now = 30_000; load.mockImplementation(() => new Promise(() => {}));
    const result = flags.isEnabled('business.preview');
    await vi.advanceTimersByTimeAsync(3_000);
    expect(await result).toBe(false);
    const again = flags.isEnabled('business.preview');
    await vi.advanceTimersByTimeAsync(3_000);
    expect(await again).toBe(false);
    expect(load).toHaveBeenCalledTimes(2);
  });
});
