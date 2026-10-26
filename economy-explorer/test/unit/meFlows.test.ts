import { describe, expect, it } from 'vitest';
import { addMonths, changeFraction, monthLastDay, summariseMonths } from '@/lib/services/meFlows';
import { parseMonth } from '@/lib/services/meStatements';
import type { MonthFlowGroupRow } from '@/lib/sql/me';

const row = (o: Partial<MonthFlowGroupRow>): MonthFlowGroupRow => ({
  month: '2026-10', plugin_key: '', tax_kind: '', counterparty_type: 'PERSONAL', is_internal: 0, inflow: '0.00', outflow: '0.00', ...o,
});

describe('summariseMonths', () => {
  it('leaves internal moves out of income and spending but keeps them in the net', () => {
    const m = summariseMonths([
      row({ plugin_key: 'treasury-salary', counterparty_type: 'GOVERNMENT', inflow: '100.00' }),
      row({ plugin_key: 'treasury', is_internal: 1, inflow: '25.00', outflow: '40.00' }),
      row({ plugin_key: 'BusinessPlugin', counterparty_type: 'BUSINESS', inflow: '15.00' }),
      row({ plugin_key: 'ChestShop', counterparty_type: 'BUSINESS', outflow: '9.99' }),
      row({ plugin_key: 'treasury', tax_kind: 'personal', counterparty_type: 'GOVERNMENT', outflow: '1.01' }),
    ]).get('2026-10')!;
    expect(m.income).toBe(11500n);
    expect(m.spending).toBe(1100n);
    expect(m.internalIn).toBe(2500n);
    expect(m.internalOut).toBe(4000n);
    expect(m.net).toBe(11500n - 1100n + 2500n - 4000n);
    expect(m.taxPaid).toBe(101n);
    expect(m.taxByKind).toEqual({ personal: 101n });
    expect(m.categories.map((c) => c.category)).toEqual(['wages', 'shop_purchases', 'taxes', 'business', 'internal']);
    expect(m.categories.find((c) => c.category === 'taxes')!.label).toBe('Balance tax');
  });

  it('sums exactly (no double drift)', () => {
    const m = summariseMonths(['0.10', '0.20', '0.30'].map((v) => row({ plugin_key: '', inflow: v }))).get('2026-10')!;
    expect(m.income).toBe(60n);
  });
});

describe('month helpers', () => {
  it('steps months and finds their last day', () => {
    expect(addMonths('2026-01', -1)).toBe('2025-12');
    expect(addMonths('2026-12', 1)).toBe('2027-01');
    expect(monthLastDay('2028-02')).toBe('2028-02-29');
  });

  it('parses a statement month, rejecting the future and junk', () => {
    const now = new Date('2026-10-26T12:00:00Z');
    expect(parseMonth('2026-10', now)).toBe('2026-10');
    expect(parseMonth('2026-11', now)).toBeNull();
    expect(parseMonth('2026-13', now)).toBeNull();
    expect(parseMonth('../x', now)).toBeNull();
  });

  it('reports change as a fraction of the previous value', () => {
    expect(changeFraction(150n, 100n)).toBe(0.5);
    expect(changeFraction(50n, -100n)).toBe(1.5);
    expect(changeFraction(5n, 0n)).toBeNull();
  });
});
