import 'server-only';
import { memo } from '@/lib/cache';
import { topItemsWithSeries, volumeByDay, countSales, sumVolume, countDistinctItems, listItems, countItems, listShops, countShops, findItemIdentity, countItemSales, listItemSellers, countItemSellers, itemPriceByDay } from '@/lib/sql/market';
export const getMarketWindow = (days: number) => memo(`market-window:v1:${days}`, 60_000,
  () => Promise.all([volumeByDay(days), countDistinctItems(days)]));
// All-time scans are independent of the selected window and must not delay it.
export const getMarketTotals = () => memo('market-totals:v1', 60_000, async () => {
  const [sales, volume, items] = await Promise.all([countSales(), sumVolume(), countDistinctItems()]);
  return { sales, volume, items };
});
export const getPopularItems = (days: number) => memo(`market-popular:v1:${days}`, 60_000, () => topItemsWithSeries(days, 12));
export function getItemCatalogue(search: string | null, limit: number, offset: number) {
  const fetch = () => Promise.all([listItems({ search, limit, offset }), countItems(search)]);
  return search ? fetch() : memo(`item-catalogue:v2:${limit}:${offset}`, 60_000, fetch);
}

export function getShopOffers(args: Parameters<typeof listShops>[0]) {
  return Promise.all([listShops(args), countShops(args)]);
}

/** Only tenant-wide history is cached. Callers still check permissions before
 * exposing seller figures; live shop offers and sale feeds stay outside. */
export const getItemHistory = (itemKey: string) => memo(`item-history:v1:${itemKey}`, 60_000, async () => {
  const [identity, totalSales, sellers, totalSellers, byDay] = await Promise.all([
    findItemIdentity(itemKey), countItemSales(itemKey), listItemSellers(itemKey, 10, 0),
    countItemSellers(itemKey), itemPriceByDay(itemKey, 30),
  ]);
  return { identity, totalSales, sellers, totalSellers, byDay };
});
