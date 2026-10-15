import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchTreasuryTaxRates } from '@/lib/treasuryTaxRates';

const balanceTax = { enabled: false, updatedAt: '2026-06-15T12:00:00Z', brackets: [{ floor: '1000', weeklyRate: '0.01' }, { floor: '0', weeklyRate: '0' }] };
const sourceIncomeTax = { enabled: true, updatedAt: '2026-06-15T12:00:00Z', defaultRate: '0.02', pluginRates: [{ plugin: 'Jobs', rate: '0.05' }] };
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
describe('published Treasury tax configuration', () => {
  it.each(['http://treasury:8080', 'http://treasury:8080/api/v1/'])('reads the public API without credentials from %s', async base => {
    vi.stubEnv('TREASURY_API_BASE_URL', base);
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ balanceTax, sourceIncomeTax }) });
    vi.stubGlobal('fetch', fetch);
    const rates = await fetchTreasuryTaxRates();
    expect(fetch).toHaveBeenCalledWith('http://treasury:8080/api/v1/config/tax', expect.objectContaining({ cache: 'no-store', redirect: 'error' }));
    expect(fetch.mock.calls[0][1].headers).toBeUndefined();
    expect(rates.balanceTax?.enabled).toBe(false);
    expect(rates.balanceTax?.brackets[0].floor).toBe('0');
    expect(rates.sourceIncomeTax).toEqual(sourceIncomeTax);
  });
  it.each(['unconfigured', '404', 'timeout', 'malformed'])('degrades to unavailable for %s', async failure => {
    vi.stubEnv('TREASURY_API_BASE_URL', failure === 'unconfigured' ? '' : 'http://treasury:8080');
    const fetch = vi.fn(async () => {
      if (failure === 'timeout') throw new Error('timeout');
      return { ok: failure !== '404', json: async () => ({ balanceTax: { ...balanceTax, enabled: 'false' } }) };
    });
    vi.stubGlobal('fetch', fetch);
    expect(await fetchTreasuryTaxRates()).toEqual({ balanceTax: null, sourceIncomeTax: null });
    if (failure === 'unconfigured') expect(fetch).not.toHaveBeenCalled();
  });
  it('isolates an invalid section and rejects non-finite/out-of-range rates', async () => {
    vi.stubEnv('TREASURY_API_BASE_URL', 'http://treasury:8080');
    for (const rate of ['Infinity', '-0.1', '1.01']) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ balanceTax, sourceIncomeTax: { ...sourceIncomeTax, defaultRate: rate } }) }));
      const rates = await fetchTreasuryTaxRates();
      expect(rates.balanceTax).not.toBeNull();
      expect(rates.sourceIncomeTax).toBeNull();
    }
  });
});
