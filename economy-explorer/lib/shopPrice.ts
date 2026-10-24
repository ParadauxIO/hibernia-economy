/** Registry prices are per sign batch; history prices are already per unit. */
export function shopUnitPrice(price: string | null, batch: number): number | null {
  if (price === null || !Number.isFinite(Number(price)) || batch < 1) return null;
  return Number(price) / batch;
}
export function formatShopPrice(price: number | null): string {
  return price === null ? 'Unavailable' : '$' + price.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}
