import 'server-only';
import { memo } from '@/lib/cache';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { inPageOrder, type KeysetPage } from '@/lib/sql/keyset';
import { countSalesPage, findFirstSaleAtOrAfter, listSalesPage, type MarketSaleRow, type SalesFeedQuery } from '@/lib/sql/market';
import { findItemNames, findPlayerNames, findPlayerUuidsByName } from '@/lib/sql/search';
import { findFirmNames } from '@/lib/sql/tableLookups';
import type { FilterState } from '@/lib/tables/core';
import type { FeedNav, FeedPosition } from '@/lib/tables/feed';
import { SALES_SCAN_WINDOW_DAYS, type SalesTableState } from '@/lib/tables/sales';
import { scanWindow, type ScanWindow } from '@/lib/tables/scanWindow';
import { COUNT_CAP, moneyBounds } from './transactions';

// The ChestShop sales feed (`/chestshop/sales`): table state → index-driven
// keyset query (lib/sql/market.ts, "Sales feed"), plus the export batches.

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function filter(state: SalesTableState, field: string): FilterState | undefined {
  return state.filters.find((f) => f.field === field);
}

async function resolvePlayers(values: readonly string[] | undefined): Promise<string[] | null> {
  if (!values) return null;
  const names = values.filter((v) => !UUID_RE.test(v));
  const found = await findPlayerUuidsByName(names);
  return [...values.filter((v) => UUID_RE.test(v)), ...names.map((n) => found.get(n.toLowerCase())).filter((u): u is string => !!u)];
}

export interface ResolvedSalesQuery {
  query: SalesFeedQuery;
  window: ScanWindow;
  empty: boolean;
}

export async function resolveSalesQuery(state: SalesTableState, now: Date = new Date()): Promise<ResolvedSalesQuery> {
  const price = moneyBounds(filter(state, 'price'));
  const itemName = state.q.trim() || null;
  const window = scanWindow(filter(state, 'occurred'), price.length > 0 || itemName !== null, SALES_SCAN_WINDOW_DAYS, now);
  const directions = filter(state, 'direction')?.values ?? [];
  const [customers, owners] = await Promise.all([
    resolvePlayers(filter(state, 'customer')?.values),
    resolvePlayers(filter(state, 'owner')?.values),
  ]);
  return {
    query: {
      customers,
      owners,
      firms: filter(state, 'firm')?.values.map(Number) ?? null,
      items: filter(state, 'item')?.values ?? null,
      // Both directions selected is no filter at all.
      direction: directions.length === 1 ? (directions[0] === 'buy' ? 'BUY' : 'SELL') : null,
      price,
      from: window.from,
      to: window.to,
      itemName,
    },
    window,
    empty: (customers !== null && customers.length === 0) || (owners !== null && owners.length === 0),
  };
}

const cursorOf = (r: MarketSaleRow): KeysetCursor => ({ at: r.occurred_at ?? new Date(0), id: r.sale_id });

export interface SalesPage {
  rows: MarketSaleRow[];
  position: FeedPosition;
  count: { count: number; capped: boolean };
  window: ScanWindow;
  entityLabels: Record<string, string>;
}

async function chipLabels(state: SalesTableState): Promise<Record<string, string>> {
  const uuids = ['customer', 'owner'].flatMap((f) => (filter(state, f)?.values ?? []).filter((v) => UUID_RE.test(v)));
  const firmIds = filter(state, 'firm')?.values.map(Number) ?? [];
  const items = filter(state, 'item')?.values ?? [];
  const [players, firms, itemNames] = await Promise.all([findPlayerNames(uuids), findFirmNames(firmIds), findItemNames(items)]);
  const out: Record<string, string> = {};
  for (const field of ['customer', 'owner']) {
    for (const v of filter(state, field)?.values ?? []) {
      const name = players.get(v);
      if (name) out[`${field}:${v}`] = name;
    }
  }
  for (const [id, name] of firms) out[`firm:${id}`] = name;
  for (const key of items) {
    const name = [...itemNames].find(([k]) => k.toLowerCase() === key.toLowerCase())?.[1];
    if (name) out[`item:${key}`] = name;
  }
  return out;
}

export async function getSalesPage(state: SalesTableState, nav: FeedNav, cursor: KeysetCursor | null, now: Date = new Date()): Promise<SalesPage> {
  const resolved = await resolveSalesQuery(state, now);
  const labels = chipLabels(state);
  if (resolved.empty) {
    return { rows: [], position: { first: null, last: null, hasPrev: false, hasNext: false }, count: { count: 0, capped: false }, window: resolved.window, entityLabels: await labels };
  }
  const dir = state.sort.dir;
  let start = cursor;
  if (nav.jump) {
    const after = dir === 'desc' ? await findFirstSaleAtOrAfter(nav.jump.end) : null;
    start = dir === 'desc'
      ? (after ? { at: after.occurred_at, id: after.sale_id } : null)
      : { at: new Date(nav.jump.start.getTime() - 1000), id: Number.MAX_SAFE_INTEGER };
  }
  const fetch = (page: KeysetPage) => listSalesPage(resolved.query, page).then((rows) => inPageOrder(rows, page));
  // `back` only means something with a cursor: a filter change drops the cursor and keeps the param.
  let direction = nav.jump || !start ? 'next' as const : nav.direction;
  let result = await fetch({ cursor: start, direction, dir, limit: state.limit });
  if (direction === 'prev' && !result.more) {
    direction = 'next';
    result = await fetch({ cursor: null, direction, dir, limit: state.limit });
  }
  const unfiltered = state.filters.length === 0 && !state.q;
  const [count, entityLabels] = await Promise.all([
    direction === 'next' && start === null && !result.more
      ? Promise.resolve({ count: result.rows.length, capped: false })
      : unfiltered ? memo('sales-feed:count-all', 60_000, () => countSalesPage(resolved.query, COUNT_CAP)) : countSalesPage(resolved.query, COUNT_CAP),
    labels,
  ]);
  const firstRow = result.rows[0];
  const lastRow = result.rows[result.rows.length - 1];
  const atStart = direction === 'next' && start === null;
  return {
    rows: result.rows,
    position: {
      first: firstRow ? cursorOf(firstRow) : null,
      last: lastRow ? cursorOf(lastRow) : null,
      hasPrev: direction === 'prev' ? result.more : !atStart,
      hasNext: direction === 'next' ? result.more : true,
    },
    count,
    window: resolved.window,
    entityLabels,
  };
}

export async function exportSalesBatch(state: SalesTableState, cursor: KeysetCursor | null, limit: number): Promise<{ rows: MarketSaleRow[]; next: KeysetCursor | null }> {
  const resolved = await resolveSalesQuery(state);
  if (resolved.empty) return { rows: [], next: null };
  const page: KeysetPage = { cursor, direction: 'next', dir: state.sort.dir, limit };
  const { rows, more } = inPageOrder(await listSalesPage(resolved.query, page), page);
  const last = rows[rows.length - 1];
  return { rows, next: more && last ? cursorOf(last) : null };
}
