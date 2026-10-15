import 'server-only';
import { memo } from '@/lib/cache';
import { topItemsWithSeries, volumeByDay, countSales, sumVolume, countDistinctItems, listItems, countItems, listShops, countShops } from '@/lib/sql/market';
export const getMarketActivity = (days: number) => memo(`market-activity:v1:${days}`, 60_000, () => Promise.all([
  volumeByDay(days), countSales(), sumVolume(), countDistinctItems(), countDistinctItems(days),
]));
export const getPopularItems = (days: number) => memo(`market-popular:v1:${days}`, 60_000, () => topItemsWithSeries(days, 12));
export function getItemCatalogue(search: string | null, limit: number, offset: number) {
  const fetch = () => Promise.all([listItems({ search, limit, offset }), countItems(search)]);
  return search ? fetch() : memo(`item-catalogue:v2:${limit}:${offset}`, 60_000, fetch);
}

export function getShopOffers(args: Parameters<typeof listShops>[0]) {
  return Promise.all([listShops(args), countShops(args)]);
}
