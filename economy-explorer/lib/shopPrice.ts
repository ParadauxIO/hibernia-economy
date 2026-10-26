import { CURRENCY_SYMBOL } from '@/lib/currency';

// Shop sign prices for display. A unit price is a quote (it can be a fraction
// of a cent, e.g. 16.00 for 64 items), never summed, so a number is fine here;
// money that is added up goes through lib/money.ts.

/** Registry prices are per sign batch; history prices are already per unit. */
export function shopUnitPrice(price: string | null, batch: number): number | null {
  if (price === null || !Number.isFinite(Number(price)) || batch < 1) return null;
  return Number(price) / batch;
}

/**
 * A DECIMAL unit price ("1.2350") rounded half away from zero to cents
 * ("1.24"), exactly, for display with <Money>.
 */
export function priceToCents(value: string | null | undefined): string | null {
  const m = value ? /^(-?)(\d+)(?:\.(\d+))?$/.exec(value.trim()) : null;
  if (!m) return null;
  const [, sign = '', whole = '0', frac = ''] = m;
  const scaled = BigInt(whole + (frac + '000').slice(0, 3));
  const cents = (scaled + 5n) / 10n;
  return `${sign && cents !== 0n ? '-' : ''}${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`;
}

export function formatShopPrice(price: number | null, currency: string = CURRENCY_SYMBOL): string {
  return price === null ? 'Unavailable' : currency + price.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}
