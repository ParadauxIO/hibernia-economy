import 'server-only';
import { requireMarketViewer } from '@/lib/auth/market';
import { memo } from '@/lib/cache';
import { sum, toDbString } from '@/lib/money';
import { addDays, utcDay, type DayRange } from '@/lib/rollup/days';
import {
  countCatalogue, countItemsTraded, countMarketFirms, countShopDirectory, countShops, listMarketFirms, findItemIdentities, findShopItemsByPrefix, listCatalogue, listItemSales, listNewestShops,
  listShopDirectory, listShops, selectItemMovers, selectItemWindow, selectMarketDays,
  type ItemIdentityRow, type ItemWindowRow, type MarketDayRow, type MarketFirmRow, type MarketSaleRow, type MarketShopRow,
} from '@/lib/sql/market';
import { itemDays, rollupCoverage, type ItemDay, type RangeCoverage } from '@/lib/services/rollup';
import { itemsTable, type ItemsTableState } from '@/lib/tables/items';
import { shopsTable, type ShopsTableState } from '@/lib/tables/shops';
import { isQuickActive, quickFilters, serializeTableState, type EntityOption } from '@/lib/tables/core';

// Market pages (spec §7.4) on the item rollup (explorer_rollup_item_day): no
// request reads sale history except the staff-only recent-sales list, which is
// one item's newest rows on idx_cs_item.

const FIVE_MINUTES = 5 * 60_000;

async function itemCoverage(range: DayRange): Promise<RangeCoverage> {
  const cov = (await rollupCoverage()).item_day;
  if (!cov) return { job: 'item_day', covered: false, coveredFrom: null, coveredTo: null, historyComplete: false, updatedAt: null };
  return {
    job: 'item_day', coveredFrom: cov.from_day, coveredTo: cov.to_day, historyComplete: cov.history_complete === 1, updatedAt: cov.updated_at,
    covered: (cov.history_complete === 1 || cov.from_day <= range.from) && cov.to_day >= range.to,
  };
}

export type Identity = Omit<ItemIdentityRow, 'item_key'>;

async function identities(keys: readonly string[]): Promise<Map<string, Identity>> {
  const rows = await findItemIdentities([...new Set(keys)]);
  return new Map(rows.map(({ item_key, ...rest }) => [item_key, rest]));
}

/** "OAK_LOG" → "Oak Log" when no sale has named the item yet. */
export function itemLabel(itemKey: string, identity?: Identity | null): string {
  if (identity?.item_name) return identity.item_name;
  return itemKey.replace(/^minecraft:/, '').replace(/[_:]+/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
}

export interface Mover { itemKey: string; identity: Identity | null; before: string; after: string; change: number; trades: number; volume: string }

export interface MarketOverview {
  coverage: RangeCoverage;
  /** This week and the one before, per day. */
  days: MarketDayRow[];
  week: { trades: number; volume: string; items: number };
  prevWeek: { trades: number; volume: string; items: number } | null;
  gainers: Mover[];
  losers: Mover[];
  mostTraded: (ItemWindowRow & { identity: Identity | null })[];
  newest: MarketShopRow[];
}

function sumDays(days: MarketDayRow[], items: number): { trades: number; volume: string; items: number } {
  return { trades: days.reduce((n, d) => n + d.trades, 0), volume: toDbString(sum(days.map((d) => d.volume))), items };
}

/** The /market landing: movers, most traded and newest listings. Cached for five minutes. */
export function getMarketOverview(): Promise<MarketOverview> {
  return memo('market-overview:v1', FIVE_MINUTES, async () => {
    const today = utcDay();
    const from = addDays(today, -6);
    const prevFrom = addDays(today, -13);
    const prevTo = addDays(today, -7);
    const [coverage, days, movers, mostTraded, newest, weekItems, prevItems] = await Promise.all([
      itemCoverage({ from: prevFrom, to: today }),
      selectMarketDays(prevFrom, today),
      selectItemMovers(prevFrom, prevTo, from, today, 5),
      selectItemWindow(from, today, 10, 'trades'),
      listNewestShops(8),
      countItemsTraded(from, today),
      countItemsTraded(prevFrom, prevTo),
    ]);
    const ranked = movers.map((m) => {
      const before = Number(m.before_price);
      const after = Number(m.after_price);
      return { itemKey: m.item_key, before: m.before_price, after: m.after_price, change: before > 0 ? (after - before) / before : 0, trades: m.trades, volume: m.volume };
    });
    const gainers = ranked.filter((m) => m.change > 0.005).sort((a, b) => b.change - a.change).slice(0, 5);
    const losers = ranked.filter((m) => m.change < -0.005).sort((a, b) => a.change - b.change).slice(0, 5);
    const ids = await identities([...gainers, ...losers].map((m) => m.itemKey).concat(mostTraded.map((m) => m.item_key)));
    const thisWeek = days.filter((d) => d.day >= from);
    const lastWeek = days.filter((d) => d.day <= prevTo);
    return {
      coverage,
      days,
      week: sumDays(thisWeek, weekItems),
      prevWeek: coverage.historyComplete || (coverage.coveredFrom !== null && coverage.coveredFrom <= prevFrom) ? sumDays(lastWeek, prevItems) : null,
      gainers: gainers.map((m) => ({ ...m, identity: ids.get(m.itemKey) ?? null })),
      losers: losers.map((m) => ({ ...m, identity: ids.get(m.itemKey) ?? null })),
      mostTraded: mostTraded.map((m) => ({ ...m, identity: ids.get(m.item_key) ?? null })),
      newest,
    };
  });
}

/** Market-wide totals per day for a range (/market/activity). */
export function getMarketActivity(range: DayRange): Promise<{ days: MarketDayRow[]; coverage: RangeCoverage; top: (ItemWindowRow & { identity: Identity | null })[] }> {
  return memo(`market-activity:v1:${range.from}:${range.to}`, FIVE_MINUTES, async () => {
    const [coverage, days, top] = await Promise.all([
      itemCoverage(range), selectMarketDays(range.from, range.to), selectItemWindow(range.from, range.to, 10, 'volume'),
    ]);
    const ids = await identities(top.map((t) => t.item_key));
    return { coverage, days, top: top.map((t) => ({ ...t, identity: ids.get(t.item_key) ?? null })) };
  });
}

function cacheable<T extends ItemsTableState | ShopsTableState>(state: T, table: typeof itemsTable | typeof shopsTable): boolean {
  if (state.q !== '') return false;
  if (state.filters.length === 0) return true;
  return quickFilters(table).some((q) => q.filters.length === state.filters.length && isQuickActive(state, q));
}

export interface CatalogueRow extends ItemWindowRow { identity: Identity | null }

/** The item catalogue over the last 30 days of the item rollup. */
export async function getCatalogue(state: ItemsTableState): Promise<{ rows: CatalogueRow[]; total: number; coverage: RangeCoverage }> {
  const today = utcDay();
  const range = { from: addDays(today, -29), to: today };
  const offset = (state.page - 1) * state.limit;
  const fetch = async () => {
    const [coverage, rows, total] = await Promise.all([
      itemCoverage(range), listCatalogue(state, range.from, range.to, state.limit, offset), countCatalogue(state, range.from, range.to),
    ]);
    const ids = await identities(rows.map((r) => r.item_key));
    return { coverage, total, rows: rows.map((r) => ({ ...r, identity: ids.get(r.item_key) ?? null })) };
  };
  return cacheable(state, itemsTable) ? memo(`item-catalogue:v3:${serializeTableState(state, itemsTable)}`, 60_000, fetch) : fetch();
}

/** The shop finder page. Live shop data: cached for a minute only for link-reachable views. */
export async function getShopDirectory(state: ShopsTableState): Promise<{ rows: MarketShopRow[]; total: number }> {
  await requireMarketViewer('/chestshop/shops');
  const offset = (state.page - 1) * state.limit;
  const fetch = async () => {
    const [rows, total] = await Promise.all([listShopDirectory(state, state.limit, offset), countShopDirectory(state)]);
    return { rows, total };
  };
  return cacheable(state, shopsTable) ? memo(`shop-dir:v1:${serializeTableState(state, shopsTable)}`, 60_000, fetch) : fetch();
}

export interface ItemPage {
  identity: Identity | null;
  days: ItemDay[];
  coverage: RangeCoverage;
  compare: { itemKey: string; identity: Identity | null }[];
}

/** One item's (and up to three comparison items') daily candles for a range. Cached for five minutes. */
export async function getItemPage(itemKey: string, compareKeys: readonly string[], range: DayRange): Promise<ItemPage> {
  await requireMarketViewer(`/chestshop/items/${encodeURIComponent(itemKey)}`);
  const keys = [itemKey, ...compareKeys.filter((k) => k !== itemKey).slice(0, 3)];
  return memo(`item-page:v1:${keys.join('|')}:${range.from}:${range.to}`, FIVE_MINUTES, async () => {
    const [{ data, coverage }, ids] = await Promise.all([itemDays(keys, range), identities(keys)]);
    return {
      identity: ids.get(itemKey) ?? null,
      days: data,
      coverage,
      compare: keys.slice(1).map((k) => ({ itemKey: k, identity: ids.get(k) ?? null })),
    };
  });
}

/** Live offers for one item, both sides. Cached for a minute. */
export async function getItemOffers(itemKey: string): Promise<{ buy: MarketShopRow[]; sell: MarketShopRow[]; buyCount: number; sellCount: number }> {
  await requireMarketViewer(`/chestshop/items/${encodeURIComponent(itemKey)}`);
  return memo(`item-offers:v1:${itemKey}`, 60_000, async () => {
    const base = { itemKey, material: null, firmId: null, ownerUuid: null, buyable: false, inStock: false, search: null } as const;
    const [buy, sell, buyCount, sellCount] = await Promise.all([
      listShops({ ...base, direction: 'buy', limit: 10, offset: 0 }),
      listShops({ ...base, direction: 'sell', limit: 10, offset: 0 }),
      countShops({ ...base, direction: 'buy' }),
      countShops({ ...base, direction: 'sell' }),
    ]);
    return { buy, sell, buyCount, sellCount };
  });
}

/** The newest sales of one item. Staff only (market.figures.read): the caller checks. */
export function getRecentItemSales(itemKey: string, limit: number): Promise<MarketSaleRow[]> {
  return listItemSales(itemKey, limit, 0);
}

/**
 * Firms ranked by all-time ChestShop volume. One grouped pass over
 * chestshop_sale by shop_firm_id (idx_cs_firm); the rollups have no firm
 * dimension, so it is cached for ten minutes and served stale while it refreshes.
 */
export function getMarketFirms(limit: number, offset: number): Promise<{ firms: MarketFirmRow[]; total: number }> {
  return memo(`market-firms:v2:${limit}:${offset}`, 10 * 60_000, async () => {
    const [firms, total] = await Promise.all([listMarketFirms(limit, offset), countMarketFirms()]);
    return { firms, total };
  });
}

/** Typeahead for item filters and the comparison picker: item keys among live shops. */
export async function suggestItems(prefix: string): Promise<EntityOption[]> {
  const term = prefix.trim().slice(0, 64);
  if (!term) return [];
  const rows = await findShopItemsByPrefix(term, 8);
  return rows.map((r) => ({ value: r.item_key, label: r.item_name || itemLabel(r.item_key), detail: r.item_key }));
}
