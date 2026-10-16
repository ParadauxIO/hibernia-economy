import { describe, it, expect } from 'vitest';
import { summarizeTaxRevenue, taxationDays } from '@/lib/taxation';
import type { TaxRevenueRow } from '@/lib/sql/taxation';

const now = new Date('2026-06-15T12:00:00Z');
const row = (overrides: Partial<TaxRevenueRow> = {}): TaxRevenueRow => ({
  date: '2026-06-15', kind: 'personal', account_id: 5, display_name: 'Treasury', collected: '0.10', refunded: '0.00', ...overrides,
});
describe('tax revenue summaries', () => {
  it('sums exact cents across dates, types and accounts and preserves refund-only periods', () => {
    const data = summarizeTaxRevenue([
      row(), row({ collected: '0.20' }),
      row({ kind: 'fines', account_id: 6, display_name: null, collected: '0.00', refunded: '1.00' }),
    ], 7, now);
    expect(data.totals).toEqual({ collected: '0.30', refunded: '1.00', net: '-0.70' });
    expect(data.byType.find(r => r.kind === 'personal')?.net).toBe('0.30');
    expect(data.accounts[1]).toEqual({ id: 6, name: 'Government account #6', collected: '0.00', refunded: '1.00', net: '-1.00' });
    expect(data.daily).toHaveLength(8);
    expect(data.daily[0]).toEqual({ date: '2026-06-08', personal: 0, corporate: 0, income: 0, sales: 0, fines: 0 });
    expect(data.daily.at(-1)?.fines).toBe(-1);
  });
  it('does not lose cents above the safe integer limit', () => {
    expect(summarizeTaxRevenue([row({ collected: '90071992547409.91' }), row()], 7, now).totals.net).toBe('90071992547410.01');
  });
  it('returns zero summaries and continuous dates for an empty window crossing a month', () => {
    const data = summarizeTaxRevenue([], 30, now);
    expect(data.totals.net).toBe('0.00');
    expect(data.accounts).toEqual([]);
    expect(data.byType).toHaveLength(5);
    expect(data.daily).toHaveLength(31);
    expect(data.daily[0]?.date).toBe('2026-05-16');
  });
  it('bounds all user input to the two cache windows', () => {
    expect(taxationDays('7')).toBe(7);
    for (const input of [undefined, '30', '-1', '0', '100000', 'NaN', ['7', '30']]) expect(taxationDays(input)).toBe(30);
  });
});
