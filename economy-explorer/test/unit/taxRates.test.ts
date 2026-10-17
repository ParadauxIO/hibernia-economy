import { describe, it, expect } from 'vitest';
import { fmtRate, parseTaxRates, type ConfigRow } from '@/lib/taxRates';

// The encoding these rows use is written by Treasury's ConfigPublishServiceImpl
// (PAR-80); these cases pin the reader side of that contract.

const at = (iso: string) => new Date(iso);
const row = (config_key: string, config_value: string, updated = '2026-10-01T10:00:00Z'): ConfigRow =>
  ({ config_key, config_value, updated_at: at(updated) });

const published: ConfigRow[] = [
  row('tax.balance.enabled', 'true'),
  row('tax.balance.brackets',
    '[{"floor":"0.00","rate":"0"},{"floor":"100000.00","rate":"0.01"},{"floor":"200000.00","rate":"0.012"}]',
    '2026-10-05T08:00:00Z'),
  row('tax.source-income-tax.enabled', 'false'),
  row('tax.source-income-tax.default-rate', '0.05', '2026-10-03T00:00:00Z'),
  row('tax.source-income-tax.plugin-rates', '{"ChestShop":"0.02","Jobs":"0.1"}'),
];

describe('fmtRate', () => {
  it('renders fractions as percentages without float noise', () => {
    expect(fmtRate('0')).toBe('0%');
    expect(fmtRate('0.012')).toBe('1.2%');
    expect(fmtRate('0.0125')).toBe('1.25%');
    expect(fmtRate('1')).toBe('100%');
  });

  it('shows a dash for an unreadable rate', () => {
    expect(fmtRate('abc')).toBe('—');
  });
});

describe('parseTaxRates', () => {
  it('builds balance bands from consecutive floors, the last open-ended', () => {
    const { balanceTax } = parseTaxRates(published);
    expect(balanceTax).toEqual({
      enabled: true,
      brackets: [
        { band: '$0.00 up to $100,000.00', rate: '0%' },
        { band: '$100,000.00 up to $200,000.00', rate: '1%' },
        { band: '$200,000.00 and above', rate: '1.2%' },
      ],
      updatedAt: '2026-10-05T08:00:00.000Z',
    });
  });

  it('keeps per-plugin income rates in published order and the newest change time', () => {
    const { incomeTax } = parseTaxRates(published);
    expect(incomeTax).toEqual({
      enabled: false,
      defaultRate: '5%',
      sources: [{ plugin: 'ChestShop', rate: '2%' }, { plugin: 'Jobs', rate: '10%' }],
      updatedAt: '2026-10-03T00:00:00.000Z',
    });
  });

  it('treats a section with a missing key as unpublished', () => {
    const rates = parseTaxRates(published.filter((r) => r.config_key !== 'tax.source-income-tax.default-rate'));
    expect(rates.incomeTax).toBeNull();
    expect(rates.balanceTax).not.toBeNull();
    expect(parseTaxRates([])).toEqual({ balanceTax: null, incomeTax: null });
  });

  it('treats unreadable JSON as unpublished rather than throwing', () => {
    const broken = published.map((r) =>
      r.config_key === 'tax.balance.brackets' ? { ...r, config_value: '{"floor":1}' }
        : r.config_key === 'tax.source-income-tax.plugin-rates' ? { ...r, config_value: 'nope' } : r);
    expect(parseTaxRates(broken)).toEqual({ balanceTax: null, incomeTax: null });
  });
});
