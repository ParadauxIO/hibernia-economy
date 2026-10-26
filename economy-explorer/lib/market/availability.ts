// What a shop's last observation says about trading there now. Observations
// are evidence, never a promise that a trade will go through.

export interface AvailabilityInput {
  admin_shop?: number | boolean | null;
  current_stock: number | null;
  current_space?: number | null;
  stock_at?: Date | string | null;
  batch_qty: number;
}

export type AvailabilityTone = 'good' | 'warn' | 'neutral';

export interface Availability { label: string; tone: AvailabilityTone; count: number | null }

const DAY_MS = 24 * 60 * 60 * 1000;

/** `direction` is the customer's: buy reads the shop's stock, sell its free space. */
export function availability(shop: AvailabilityInput, direction: 'buy' | 'sell', now: number = Date.now()): Availability {
  if (shop.admin_shop) return { label: 'Unlimited', tone: 'good', count: null };
  const count = direction === 'buy' ? shop.current_stock : shop.current_space ?? null;
  const at = shop.stock_at ? new Date(shop.stock_at).getTime() : NaN;
  if (count === null || !Number.isFinite(at) || at > now || shop.batch_qty < 1) return { label: 'Unknown', tone: 'neutral', count: null };
  if (now - at > DAY_MS) return { label: 'Recheck', tone: 'warn', count };
  if (count < shop.batch_qty) return { label: direction === 'buy' ? 'Below one batch' : 'Limited space', tone: 'warn', count };
  return { label: direction === 'buy' ? 'In stock' : 'Has space', tone: 'good', count };
}
