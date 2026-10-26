import type { TaxRevenueRow } from '@/lib/sql/taxation';
import { addDays, type DayRange } from '@/lib/range';
import { parseMoney, toDbString, toNumber } from '@/lib/money';

export const TAX_KINDS = ['personal', 'corporate', 'income', 'sales', 'fines'] as const;
export type TaxKind = typeof TAX_KINDS[number];
export const TAX_LABELS: Record<TaxKind, string> = {
  personal: 'Personal balance tax', corporate: 'Corporate balance tax',
  income: 'Source / income tax', sales: 'ChestShop sales tax', fines: 'Fines',
};

// Keep every aggregate in integer cents (lib/money); only chart coordinates become doubles.
const empty = () => ({ collected: 0n, refunded: 0n });
const amounts = (value: ReturnType<typeof empty>) => ({
  collected: toDbString(value.collected), refunded: toDbString(value.refunded),
  net: toDbString(value.collected - value.refunded),
});

/**
 * Totals by kind and by receiving account, and a daily series with one point
 * per day of `days` (the covered part of the range: quiet days are zero).
 */
export function summarizeTaxRevenue(rows: TaxRevenueRow[], days: DayRange | null) {
  const totals = empty();
  const types = new Map(TAX_KINDS.map((kind) => [kind, empty()]));
  const accounts = new Map<number, ReturnType<typeof empty> & { name: string }>();
  const daily = new Map<string, Record<TaxKind, number>>();
  for (let day = days?.from; days && day && day <= days.to; day = addDays(day, 1)) {
    daily.set(day, Object.fromEntries(TAX_KINDS.map((k) => [k, 0])) as Record<TaxKind, number>);
  }
  for (const row of rows) {
    const collected = parseMoney(row.collected), refunded = parseMoney(row.refunded);
    const type = types.get(row.kind);
    if (!type) continue;
    const account = accounts.get(row.account_id) ?? { ...empty(), name: row.display_name || `Government account #${row.account_id}` };
    for (const target of [totals, type, account]) {
      target.collected += collected;
      target.refunded += refunded;
    }
    accounts.set(row.account_id, account);
    const day = daily.get(row.date);
    if (day) day[row.kind] += toNumber(collected - refunded);
  }
  return {
    totals: amounts(totals),
    byType: TAX_KINDS.map((kind) => ({ kind, label: TAX_LABELS[kind], ...amounts(types.get(kind)!) })),
    accounts: [...accounts].sort((a, b) => {
      const diff = (b[1].collected - b[1].refunded) - (a[1].collected - a[1].refunded);
      return diff > 0n ? 1 : diff < 0n ? -1 : a[0] - b[0];
    }).map(([id, value]) => ({ id, name: value.name, ...amounts(value) })),
    daily: [...daily].map(([date, values]) => ({ date, ...values })),
  };
}

export type TaxationSummary = ReturnType<typeof summarizeTaxRevenue>;
