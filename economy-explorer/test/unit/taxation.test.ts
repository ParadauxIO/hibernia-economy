import { describe, it, expect } from 'vitest';
import { summarizeTaxRevenue } from '@/lib/taxation';
import type { TaxRevenueRow } from '@/lib/sql/taxation';

const week = { from: '2026-06-09', to: '2026-06-15' };
const row = (overrides: Partial<TaxRevenueRow> = {}): TaxRevenueRow => ({
  date: '2026-06-15', kind: 'personal', account_id: 5, display_name: 'Treasury', collected: '0.10', refunded: '0.00', ...overrides,
});
describe('tax revenue summaries', () => {
  it('sums exact cents across dates, types and accounts and preserves refund-only periods', () => {
    const data = summarizeTaxRevenue([
      row(), row({ collected: '0.20' }),
      row({ kind: 'fines', account_id: 6, display_name: null, collected: '0.00', refunded: '1.00' }),
    ], week);
    expect(data.totals).toEqual({ collected: '0.30', refunded: '1.00', net: '-0.70' });
    expect(data.byType.find(r => r.kind === 'personal')?.net).toBe('0.30');
    expect(data.accounts[1]).toEqual({ id: 6, name: 'Government account #6', collected: '0.00', refunded: '1.00', net: '-1.00' });
    expect(data.daily).toHaveLength(7);
    expect(data.daily[0]).toEqual({ date: '2026-06-09', personal: 0, corporate: 0, income: 0, sales: 0, fines: 0 });
    expect(data.daily.at(-1)?.fines).toBe(-1);
  });
  it('does not lose cents above the safe integer limit', () => {
    expect(summarizeTaxRevenue([row({ collected: '90071992547409.91' }), row()], week).totals.net).toBe('90071992547410.01');
  });
  it('returns zero summaries and continuous dates for an empty range crossing a month', () => {
    const data = summarizeTaxRevenue([], { from: '2026-05-16', to: '2026-06-15' });
    expect(data.totals.net).toBe('0.00');
    expect(data.accounts).toEqual([]);
    expect(data.byType).toHaveLength(5);
    expect(data.daily).toHaveLength(31);
    expect(data.daily[0]?.date).toBe('2026-05-16');
  });
  it('has no daily points before the rollups cover any day, and ignores rows outside the days', () => {
    expect(summarizeTaxRevenue([row()], null).daily).toEqual([]);
    expect(summarizeTaxRevenue([row({ date: '2026-01-01' })], week).daily.every((d) => d.personal === 0)).toBe(true);
  });
});
