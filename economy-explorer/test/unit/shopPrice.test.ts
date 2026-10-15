import { expect, it } from 'vitest';
import { shopUnitPrice, formatShopPrice } from '@/lib/shopPrice';
it('normalises sign batches without treating missing or invalid prices as free', () => {
  expect(shopUnitPrice('16.00', 64)).toBe(.25);
  expect(shopUnitPrice('0.00', 1)).toBe(0);
  expect(shopUnitPrice(null, 64)).toBeNull();
  expect(shopUnitPrice('4.00', 0)).toBeNull();
  expect(formatShopPrice(.0025)).toBe('$0.0025');
});
