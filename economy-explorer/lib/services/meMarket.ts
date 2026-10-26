import 'server-only';
import { memo } from '@/lib/cache';
import { parseMoney, toDbString } from '@/lib/money';
import {
  selectMarketSummary, selectMarketTopItems, selectMarketTopPartners, type MarketItemRow, type MarketPartnerRow,
} from '@/lib/sql/me';
import { ME_CACHE_MS } from './me';

// /me/market (spec §8.5): the player's ChestShop trades on both sides of the
// book, aggregated in SQL over a time-bounded window (two index ranges on
// chestshop_sale, see lib/sql/me.ts). No row is summed in JavaScript; the
// per-side totals come back as DECIMAL strings. Three queries, cached 30 s.

export const ITEM_LIMIT = 15;
export const PARTNER_LIMIT = 10;

export interface MyMarket {
  spent: string;
  earned: string;
  net: string;
  buys: number;
  sales: number;
  boughtItems: MarketItemRow[];
  soldItems: MarketItemRow[];
  boughtFrom: MarketPartnerRow[];
  soldTo: MarketPartnerRow[];
}

export async function getMyMarket(uuid: string, opts: { since: Date | null }): Promise<MyMarket> {
  const key = `me-market:${uuid}:${opts.since?.toISOString().slice(0, 10) ?? 'all'}`;
  return memo(key, ME_CACHE_MS, async () => {
    const [summary, items, partners] = await Promise.all([
      selectMarketSummary(uuid, opts.since),
      selectMarketTopItems(uuid, opts.since, ITEM_LIMIT),
      selectMarketTopPartners(uuid, opts.since, PARTNER_LIMIT),
    ]);
    const bought = summary.find((s) => s.bought === 1);
    const sold = summary.find((s) => s.bought === 0);
    const spent = bought?.amount ?? '0.00';
    const earned = sold?.amount ?? '0.00';
    return {
      spent, earned, net: toDbString(parseMoney(earned) - parseMoney(spent)),
      buys: bought?.trades ?? 0, sales: sold?.trades ?? 0,
      boughtItems: items.filter((i) => i.bought === 1), soldItems: items.filter((i) => i.bought === 0),
      boughtFrom: partners.filter((p) => p.bought === 1), soldTo: partners.filter((p) => p.bought === 0),
    };
  }, { maxStaleMs: 60_000 });
}
