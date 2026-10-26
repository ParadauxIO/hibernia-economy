import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resetCacheForTests } from '@/lib/cache';
import { findConfigByPrefix } from '@/lib/sql/economyConfig';
import { fetchTaxConfig } from '@/lib/treasuryTaxRates';
import { getTaxConfig, getTaxRates } from '@/lib/services/taxRates';
import { getTaxationRates } from '@/lib/services/taxationRates';

// One tax-rate source (spec §10.5): the REST endpoint first, economy_config only
// when it can't be reached, one 60 s cache for the taxation page and the docs.
vi.mock('@/lib/treasuryTaxRates', () => ({ fetchTaxConfig: vi.fn() }));
vi.mock('@/lib/sql/economyConfig', () => ({ findConfigByPrefix: vi.fn() }));

const api = {
  balanceTax: { enabled: true, updatedAt: '2026-10-20T00:00:00Z', brackets: [{ floor: '0', weeklyRate: '0' }, { floor: '50000', weeklyRate: '0.02' }] },
  sourceIncomeTax: null,
};
const row = (config_key: string, config_value: string) => ({ config_key, config_value, value_type: 'JSON' as const, updated_at: new Date('2026-10-01T10:00:00Z') });

beforeEach(() => {
  vi.resetAllMocks();
  resetCacheForTests();
});

describe('tax rate source', () => {
  it('uses the REST endpoint and never reads economy_config when it answers', async () => {
    vi.mocked(fetchTaxConfig).mockResolvedValue(api);
    const config = await getTaxConfig();
    expect(config).toEqual({ ...api, source: 'api' });
    expect(findConfigByPrefix).not.toHaveBeenCalled();
    expect((await getTaxRates()).balanceTax?.brackets).toEqual([
      { band: '$0.00 up to $50,000.00', rate: '0%' },
      { band: '$50,000.00 and above', rate: '2%' },
    ]);
    // Both consumers share the one cached read.
    expect(await getTaxationRates()).toEqual(config);
    expect(fetchTaxConfig).toHaveBeenCalledTimes(1);
  });

  it('falls back to economy_config, in the same shape, only when the endpoint is unreachable', async () => {
    vi.mocked(fetchTaxConfig).mockResolvedValue(null);
    vi.mocked(findConfigByPrefix).mockResolvedValue([
      row('tax.balance.enabled', 'true'),
      row('tax.balance.brackets', '[{"floor":"100000.00","rate":"0.01"},{"floor":"0.00","rate":"0"}]'),
    ]);
    const config = await getTaxConfig();
    expect(config.source).toBe('economy_config');
    expect(config.balanceTax?.brackets).toEqual([{ floor: '0.00', weeklyRate: '0' }, { floor: '100000.00', weeklyRate: '0.01' }]);
    expect(config.sourceIncomeTax).toBeNull();
  });
});
