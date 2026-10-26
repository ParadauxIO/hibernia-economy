import 'server-only';
import { sql, type RawBuilder, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import { afterCursor, orderByTime, readDir, type KeysetPage } from '@/lib/sql/keyset';
import { compileTableState, defineColumnMap, likeContains } from '@/lib/sql/tableState';
import { itemsTable, type ItemsTableState } from '@/lib/tables/items';
import { shopsTable, type ShopsTableState } from '@/lib/tables/shops';

// Ports of MarketReadMapper (~290 LOC of @Select). All public unless the
// query selects customer_uuid_bin — those callers gate access in the page.

export interface MarketItemRow {
  item_key: string;
  material: string | null;
  item_name: string | null;
  item_custom: number;
  trade_count: number;
  total_quantity: number;
  total_volume: string;
}

export interface MarketSaleRow {
  sale_id: number;
  txn_id: number;
  occurred_at: Date | null;
  direction: 'BUY' | 'SELL' | string;
  quantity: number;
  unit_price: string;
  total_price: string;
  tax_amount: string;
  material: string | null;
  item_key: string;
  item_name: string | null;
  item_custom: number;
  admin_shop: number;
  shop_account_type: string | null;
  shop_firm_id: number | null;
  shop_owner_uuid: string | null;
  owner_name: string | null;
  world: string | null;
  sign_x: number | null;
  sign_y: number | null;
  sign_z: number | null;
  shop_stock: number | null;
  customer_uuid?: string | null;
  customer_name?: string | null;
}

/**
 * A sale as seen from one player's perspective (/me/market). `player_role` says
 * which side of the trade the viewer was on, so the page can flip the buy/sell
 * sense and choose the right counterparty (the shop, or the customer).
 */
export interface PlayerSaleRow extends MarketSaleRow {
  player_role: 'customer' | 'owner';
}

export interface MarketSellerRow {
  admin_shop: number;
  shop_account_type: string | null;
  shop_firm_id: number | null;
  shop_owner_uuid: string | null;
  shop_account_id: number | null;
  owner_name: string | null;
  sale_count: number;
  total_quantity: number;
  total_volume: string;
}

export interface MarketCustomerRow {
  customer_uuid: string | null;
  customer_name: string | null;
  sale_count: number;
  total_quantity: number;
  total_volume: string;
}

export interface MarketFirmRow {
  firm_id: number;
  display_name: string | null;
  sale_count: number;
  total_quantity: number;
  total_volume: string;
}

export interface MarketDayPriceRow {
  day: string;
  sales: number;
  total_quantity: number;
  total_volume: string;
  avg_unit_price: string;
}

export interface VolumeDayPoint { date: string; sales: number; total_volume: string }

export interface MarketShopRow {
  shop_id: number;
  world: string | null;
  sign_x: number;
  sign_y: number;
  sign_z: number;
  admin_shop: number;
  shop_account_type: string | null;
  shop_firm_id: number | null;
  shop_owner_uuid: string | null;
  owner_name: string | null;
  material: string | null;
  item_key: string;
  item_name: string | null;
  item_custom: number;
  buy_price: string | null;
  sell_price: string | null;
  batch_qty: number;
  current_stock: number | null;
  current_space: number | null;
  stock_at: Date | null;
  last_seen: Date | null;
  active: number;
}

// ── Global stats ────────────────────────────────────────────────────────────

export async function countSales(): Promise<number> {
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM chestshop_sale`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}
export async function sumVolume(): Promise<string> {
  const r = await sql<{ v: string }>`SELECT COALESCE(SUM(total_price),0.00) AS v FROM chestshop_sale`.execute(db);
  return r.rows[0]?.v ?? '0.00';
}
export async function countDistinctItems(days?: number): Promise<number> {
  // The window needs the time range, not a scan of the entire item index.
  const r = await sql<{ c: string | number }>`SELECT COUNT(DISTINCT item_key) AS c FROM chestshop_sale ${days == null ? sql`` : sql`FORCE INDEX (idx_cs_time) WHERE occurred_at >= NOW() - INTERVAL ${days} DAY`}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

export async function volumeByDay(days: number): Promise<VolumeDayPoint[]> {
  const r = await sql<{ txn_date: string; txn_count: string | number; total_volume: string }>`
    SELECT DATE_FORMAT(DATE(occurred_at),'%Y-%m-%d') AS txn_date,
           COUNT(*) AS txn_count,
           COALESCE(SUM(total_price),0.00) AS total_volume
    FROM chestshop_sale FORCE INDEX (idx_cs_time)
    WHERE occurred_at >= NOW() - INTERVAL ${days} DAY
    GROUP BY DATE(occurred_at)
    ORDER BY txn_date ASC
  `.execute(db);
  return r.rows.map((row) => ({
    date: row.txn_date,
    sales: Number(row.txn_count),
    total_volume: row.total_volume,
  }));
}

// ── Items ────────────────────────────────────────────────────────────────

export async function topItems(days: number, limit: number): Promise<MarketItemRow[]> {
  const r = await sql<RawMarketItem>`
    SELECT item_key, MAX(material) AS material, MAX(item_name) AS item_name,
           MAX(item_custom) AS item_custom, COUNT(*) AS trade_count,
           COALESCE(SUM(quantity),0) AS total_quantity, COALESCE(SUM(total_price),0.00) AS total_volume
    FROM chestshop_sale
    WHERE occurred_at >= NOW() - INTERVAL ${days} DAY
    GROUP BY item_key ORDER BY trade_count DESC LIMIT ${limit}
  `.execute(db);
  return r.rows.map(toMarketItemRow);
}

/** Search one representative metadata row per key, rather than every trade.
 * idx_cs_item supplies distinct keys and the latest observation for each key.
 * This bounds name/material matching to the catalogue size, not sale history. */
function catalogueKeys(search: string | null) {
  if (!search) return sql`SELECT DISTINCT item_key FROM chestshop_sale`;
  return sql`
    SELECT keys_by_item.item_key
    FROM (SELECT DISTINCT item_key FROM chestshop_sale) keys_by_item
    JOIN chestshop_sale sample ON sample.sale_id = (
      SELECT recent.sale_id FROM chestshop_sale recent
      WHERE recent.item_key = keys_by_item.item_key
      ORDER BY recent.occurred_at DESC, recent.sale_id DESC LIMIT 1
    )
    WHERE sample.item_name LIKE ${'%' + search + '%'}
       OR sample.material LIKE ${'%' + search + '%'}
       OR keys_by_item.item_key LIKE ${'%' + search + '%'}
  `;
}

export async function listItems(args: { search: string | null; limit: number; offset: number }): Promise<MarketItemRow[]> {
  const r = await sql<RawMarketItem>`
    SELECT cs.item_key, MAX(cs.material) AS material, MAX(cs.item_name) AS item_name,
           MAX(cs.item_custom) AS item_custom, COUNT(*) AS trade_count,
           COALESCE(SUM(cs.quantity),0) AS total_quantity,
           COALESCE(SUM(cs.total_price),0.00) AS total_volume
    FROM (
      ${catalogueKeys(args.search)} ORDER BY item_key LIMIT ${args.limit} OFFSET ${args.offset}
    ) page
    JOIN chestshop_sale cs ON cs.item_key = page.item_key
    GROUP BY cs.item_key ORDER BY cs.item_key
  `.execute(db);
  return r.rows.map(toMarketItemRow);
}

export async function countItems(search: string | null): Promise<number> {
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM (${catalogueKeys(search)}) catalogue`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

export interface ItemWithSeries extends MarketItemRow {
  series: { day: string; price: number }[];
  current_price: number | null;
  price_change_pct: number | null;
}

/**
 * Top items by trade count in the window, each with its daily quantity-weighted
 * average unit-price series — for the market overview sparkline cards. Two
 * queries: the ranked items, then one grouped pass over their daily prices.
 */
export async function topItemsWithSeries(days: number, limit: number): Promise<ItemWithSeries[]> {
  const items = await topItems(days, limit);
  if (items.length === 0) return [];
  const keys = items.map((i) => i.item_key);

  const r = await sql<{ item_key: string; day: string; qty: string | number; vol: string }>`
    SELECT item_key, DATE_FORMAT(DATE(occurred_at), '%Y-%m-%d') AS day,
           COALESCE(SUM(quantity), 0) AS qty, COALESCE(SUM(total_price), 0.00) AS vol
    FROM chestshop_sale
    WHERE occurred_at >= NOW() - INTERVAL ${days} DAY
      AND item_key IN (${sql.join(keys)})
    GROUP BY item_key, DATE(occurred_at)
    ORDER BY item_key, day ASC
  `.execute(db);

  const byKey = new Map<string, { day: string; price: number }[]>();
  for (const row of r.rows) {
    const q = Number(row.qty);
    const v = parseFloat(row.vol);
    const price = q > 0 ? v / q : 0;
    const arr = byKey.get(row.item_key) ?? [];
    arr.push({ day: row.day, price });
    byKey.set(row.item_key, arr);
  }

  return items.map((i) => {
    const series = byKey.get(i.item_key) ?? [];
    const current = series.at(-1)?.price ?? null;
    const first = series.at(0)?.price ?? null;
    const change = first && first > 0 && current != null ? (current - first) / first : null;
    return { ...i, series, current_price: current, price_change_pct: change };
  });
}

// ── Per-item ────────────────────────────────────────────────────────────

/** Header identity without loading the permission-gated sale feed and joins. */
export async function findItemIdentity(itemKey: string) {
  const r = await sql<{ item_name: string | null; material: string | null; item_custom: number }>`
    SELECT item_name, material, item_custom FROM chestshop_sale
    WHERE item_key = ${itemKey}
    ORDER BY occurred_at DESC, sale_id DESC LIMIT 1
  `.execute(db);
  return r.rows[0] ?? null;
}

export async function listItemSales(itemKey: string, limit: number, offset: number): Promise<MarketSaleRow[]> {
  const r = await sql<RawSale>`
    SELECT cs.sale_id, cs.txn_id, cs.occurred_at, cs.direction, cs.quantity, cs.unit_price,
           cs.total_price, cs.tax_amount, cs.material, cs.item_key, cs.item_name, cs.item_custom,
           cs.is_admin_shop AS admin_shop, cs.shop_account_type, cs.shop_firm_id, cs.shop_owner_uuid_bin,
           COALESCE(f.display_name, fp.current_name, a.display_name) AS owner_name,
           cs.world, cs.sign_x, cs.sign_y, cs.sign_z, cs.shop_stock
    FROM chestshop_sale cs
    LEFT JOIN firm f ON f.firm_id = cs.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = cs.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = cs.shop_owner_uuid_bin
    WHERE cs.item_key = ${itemKey}
    ORDER BY cs.occurred_at DESC, cs.sale_id DESC LIMIT ${limit} OFFSET ${offset}
  `.execute(db);
  return r.rows.map(toSaleRow);
}

export async function countItemSales(itemKey: string): Promise<number> {
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM chestshop_sale WHERE item_key = ${itemKey}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

export async function itemPriceByDay(itemKey: string, days: number): Promise<MarketDayPriceRow[]> {
  const r = await sql<{
    day: string;
    sales: string | number;
    total_quantity: string | number;
    total_volume: string;
    avg_unit_price: string;
  }>`
    SELECT DATE_FORMAT(DATE(occurred_at),'%Y-%m-%d') AS day, COUNT(*) AS sales,
           COALESCE(SUM(quantity),0) AS total_quantity,
           COALESCE(SUM(total_price),0.00) AS total_volume,
           CASE WHEN SUM(quantity) > 0 THEN SUM(total_price)/SUM(quantity) ELSE 0 END AS avg_unit_price
    FROM chestshop_sale
    WHERE item_key = ${itemKey} AND occurred_at >= NOW() - INTERVAL ${days} DAY
    GROUP BY DATE(occurred_at) ORDER BY day ASC
  `.execute(db);
  return r.rows.map((row) => ({
    day: row.day,
    sales: Number(row.sales),
    total_quantity: Number(row.total_quantity),
    total_volume: row.total_volume,
    avg_unit_price: row.avg_unit_price,
  }));
}

export async function listItemSellers(itemKey: string, limit: number, offset: number): Promise<MarketSellerRow[]> {
  const r = await sql<RawSeller>`
    SELECT cs.is_admin_shop AS admin_shop, cs.shop_account_type, cs.shop_firm_id,
           cs.shop_owner_uuid_bin, cs.shop_account_id, MAX(COALESCE(f.display_name, fp.current_name, a.display_name)) AS owner_name,
           COUNT(*) AS sale_count, COALESCE(SUM(cs.quantity),0) AS total_quantity,
           COALESCE(SUM(cs.total_price),0.00) AS total_volume
    FROM chestshop_sale cs
    LEFT JOIN firm f ON f.firm_id = cs.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = cs.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = cs.shop_owner_uuid_bin
    WHERE cs.item_key = ${itemKey}
    GROUP BY cs.is_admin_shop, cs.shop_account_type, cs.shop_firm_id, cs.shop_owner_uuid_bin, cs.shop_account_id
    ORDER BY sale_count DESC LIMIT ${limit} OFFSET ${offset}
  `.execute(db);
  return r.rows.map(toSellerRow);
}

export async function countItemSellers(itemKey: string): Promise<number> {
  const r = await sql<{ c: string | number }>`
    SELECT COUNT(*) AS c FROM (
      SELECT 1 FROM chestshop_sale cs WHERE cs.item_key = ${itemKey}
      GROUP BY cs.is_admin_shop, cs.shop_account_type, cs.shop_firm_id, cs.shop_owner_uuid_bin, cs.shop_account_id
    ) t
  `.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

// NOTE: topSellers/topBuyers expose per-seller money + quantity. They are
// currently unused. Do NOT wire either into a public page without the appropriate market permission
// gate (see the financial-privacy model) — per-entity figures are staff-only.
export async function topSellers(days: number, limit: number): Promise<MarketSellerRow[]> {
  const r = await sql<RawSeller>`
    SELECT cs.is_admin_shop AS admin_shop, cs.shop_account_type, cs.shop_firm_id,
           cs.shop_owner_uuid_bin, cs.shop_account_id, MAX(COALESCE(f.display_name, fp.current_name, a.display_name)) AS owner_name,
           COUNT(*) AS sale_count, COALESCE(SUM(cs.quantity),0) AS total_quantity,
           COALESCE(SUM(cs.total_price),0.00) AS total_volume
    FROM chestshop_sale cs
    LEFT JOIN firm f ON f.firm_id = cs.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = cs.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = cs.shop_owner_uuid_bin
    WHERE cs.occurred_at >= NOW() - INTERVAL ${days} DAY
    GROUP BY cs.is_admin_shop, cs.shop_account_type, cs.shop_firm_id, cs.shop_owner_uuid_bin, cs.shop_account_id
    ORDER BY total_volume DESC LIMIT ${limit}
  `.execute(db);
  return r.rows.map(toSellerRow);
}

export async function topBuyers(days: number, limit: number): Promise<MarketCustomerRow[]> {
  const r = await sql<{
    customer_uuid_bin: Buffer | null;
    customer_name: string | null;
    sale_count: string | number;
    total_quantity: string | number;
    total_volume: string;
  }>`
    SELECT cs.customer_uuid_bin, MAX(COALESCE(ca.display_name, cfp.current_name)) AS customer_name,
           COUNT(*) AS sale_count, COALESCE(SUM(cs.quantity),0) AS total_quantity,
           COALESCE(SUM(cs.total_price),0.00) AS total_volume
    FROM chestshop_sale cs
    LEFT JOIN accounts ca ON ca.owner_uuid_bin = cs.customer_uuid_bin AND ca.account_type='PERSONAL'
    LEFT JOIN economy_players cfp ON cfp.player_uuid_bin = cs.customer_uuid_bin
    WHERE cs.occurred_at >= NOW() - INTERVAL ${days} DAY
    GROUP BY cs.customer_uuid_bin ORDER BY total_volume DESC LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({
    customer_uuid: row.customer_uuid_bin ? binToUuid(row.customer_uuid_bin) : null,
    customer_name: row.customer_name,
    sale_count: Number(row.sale_count),
    total_quantity: Number(row.total_quantity),
    total_volume: row.total_volume,
  }));
}

// ── Market firms ─────────────────────────────────────────────────────────

export async function listMarketFirms(limit: number, offset: number): Promise<MarketFirmRow[]> {
  const r = await sql<{
    firm_id: number;
    display_name: string | null;
    sale_count: string | number;
    total_quantity: string | number;
    total_volume: string;
  }>`
    SELECT cs.shop_firm_id AS firm_id, MAX(f.display_name) AS display_name, COUNT(*) AS sale_count,
           COALESCE(SUM(cs.quantity),0) AS total_quantity, COALESCE(SUM(cs.total_price),0.00) AS total_volume
    FROM chestshop_sale cs JOIN firm f ON f.firm_id = cs.shop_firm_id
    WHERE cs.shop_firm_id IS NOT NULL GROUP BY cs.shop_firm_id
    ORDER BY total_volume DESC LIMIT ${limit} OFFSET ${offset}
  `.execute(db);
  return r.rows.map((row) => ({
    firm_id: row.firm_id,
    display_name: row.display_name,
    sale_count: Number(row.sale_count),
    total_quantity: Number(row.total_quantity),
    total_volume: row.total_volume,
  }));
}

export async function countMarketFirms(): Promise<number> {
  const r = await sql<{ c: string | number }>`SELECT COUNT(DISTINCT shop_firm_id) AS c FROM chestshop_sale WHERE shop_firm_id IS NOT NULL`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

/**
 * One firm's ChestShop totals. `days = null` means all-time; otherwise the
 * trailing N-day window (the `/firm sales export <firm> <days>` deep link).
 *
 * The window only narrows the FIGURES, never the firm's existence: a firm that
 * has ever sold still resolves (with zero totals) when nothing falls inside the
 * window, so a windowed link can't 404 a real firm. Returns null only for a firm
 * with no ChestShop sales at all.
 */
export async function getMarketFirm(firmId: number, days: number | null = null): Promise<MarketFirmRow | null> {
  const inWindow = days != null ? sql`cs.occurred_at >= NOW() - INTERVAL ${days} DAY` : sql`TRUE`;
  const r = await sql<{
    firm_id: number;
    display_name: string | null;
    sale_count: string | number;
    total_quantity: string | number;
    total_volume: string;
  }>`
    SELECT cs.shop_firm_id AS firm_id, MAX(f.display_name) AS display_name,
           COALESCE(SUM(CASE WHEN ${inWindow} THEN 1 ELSE 0 END),0) AS sale_count,
           COALESCE(SUM(CASE WHEN ${inWindow} THEN cs.quantity END),0) AS total_quantity,
           COALESCE(SUM(CASE WHEN ${inWindow} THEN cs.total_price END),0.00) AS total_volume
    FROM chestshop_sale cs JOIN firm f ON f.firm_id = cs.shop_firm_id
    WHERE cs.shop_firm_id = ${firmId} GROUP BY cs.shop_firm_id
  `.execute(db);
  const row = r.rows[0];
  if (!row) return null;
  return {
    firm_id: row.firm_id,
    display_name: row.display_name,
    sale_count: Number(row.sale_count),
    total_quantity: Number(row.total_quantity),
    total_volume: row.total_volume,
  };
}

/** A firm's per-item sales. `days = null` means all-time; otherwise the trailing N-day window. */
export async function listFirmMarketItems(
  firmId: number,
  limit: number,
  offset: number,
  days: number | null = null,
): Promise<MarketItemRow[]> {
  const windowCond = days != null ? sql`AND occurred_at >= NOW() - INTERVAL ${days} DAY` : sql``;
  const r = await sql<RawMarketItem>`
    SELECT item_key, MAX(material) AS material, MAX(item_name) AS item_name,
           MAX(item_custom) AS item_custom, COUNT(*) AS trade_count,
           COALESCE(SUM(quantity),0) AS total_quantity, COALESCE(SUM(total_price),0.00) AS total_volume
    FROM chestshop_sale WHERE shop_firm_id = ${firmId} ${windowCond}
    GROUP BY item_key ORDER BY trade_count DESC, item_name ASC LIMIT ${limit} OFFSET ${offset}
  `.execute(db);
  return r.rows.map(toMarketItemRow);
}

// ── Per-trade samples (for OHLC bucketing) ───────────────────────────────

export interface MarketSampleRow {
  occurred_at: Date;
  item_key: string;
  item_name: string | null;
  material: string | null;
  unit_price: string;
  quantity: number;
  total_price: string;
}

/** Mirrors MarketReadMapper.listSamplesInRange (line 132-145). */
export async function listSamplesInRange(args: {
  itemKey: string | null;
  from: Date;
  to: Date;
  maxRows: number;
}): Promise<MarketSampleRow[]> {
  const itemFilter = args.itemKey ? sql`AND item_key = ${args.itemKey}` : sql``;
  const r = await sql<{
    occurred_at: Date;
    item_key: string;
    item_name: string | null;
    material: string | null;
    unit_price: string;
    quantity: string | number;
    total_price: string;
  }>`
    SELECT occurred_at, item_key, item_name, material, unit_price, quantity, total_price
    FROM chestshop_sale
    WHERE occurred_at BETWEEN ${args.from} AND ${args.to}
    ${itemFilter}
    ORDER BY occurred_at DESC, sale_id DESC LIMIT ${args.maxRows}
  `.execute(db);
  // Fetched newest-first so the cap keeps the most RECENT trades (not the
  // oldest), then reversed to ascending — bucketSamples derives open/close from
  // first/last sample and needs time-ascending order.
  return r.rows.reverse().map((row) => ({
    occurred_at: row.occurred_at,
    item_key: row.item_key,
    item_name: row.item_name,
    material: row.material,
    unit_price: row.unit_price,
    quantity: Number(row.quantity),
    total_price: row.total_price,
  }));
}

// ── Sales feed (/chestshop/sales, market.sales.read) ─────────────────────
//
// Keyset pages on (occurred_at, sale_id). Every chestshop_sale index is
// (entity, occurred_at) with the primary key appended, so whichever entity
// filter is present drives the page straight off its own index in feed order:
//   customer → idx_cs_customer, owner → idx_cs_owner, firm → idx_cs_firm,
//   item → idx_cs_item, otherwise idx_cs_time.
// A multi-value chip is one ordered, LIMITed branch per value under UNION ALL
// (a sale has one value per column, so branches never overlap). The other
// filters are residual checks. Price and item-name text have no index, so the
// service only sends them inside a bounded date window (SALES_SCAN_WINDOW_DAYS).

export interface SalesFeedQuery {
  customers?: readonly string[] | null;
  owners?: readonly string[] | null;
  firms?: readonly number[] | null;
  items?: readonly string[] | null;
  direction?: 'BUY' | 'SELL' | null;
  /** Total price bounds, exact DECIMAL strings. */
  price?: readonly { op: 'gt' | 'gte' | 'lt' | 'lte' | 'eq'; value: string }[] | null;
  from?: Date | null;
  to?: Date | null;
  /** Literal substring of the item name. */
  itemName?: string | null;
}

type SalesDriver = { column: RawBuilder<unknown>; values: readonly unknown[] } | null;

const PRICE_OPS = { gt: '>', gte: '>=', lt: '<', lte: '<=', eq: '=' } as const;

function salesDriver(q: SalesFeedQuery): { driver: SalesDriver; used: keyof SalesFeedQuery | null } {
  if (q.customers?.length) return { driver: { column: sql`cs.customer_uuid_bin`, values: q.customers.map(uuidToBin) }, used: 'customers' };
  if (q.owners?.length) return { driver: { column: sql`cs.shop_owner_uuid_bin`, values: q.owners.map(uuidToBin) }, used: 'owners' };
  if (q.firms?.length) return { driver: { column: sql`cs.shop_firm_id`, values: q.firms }, used: 'firms' };
  if (q.items?.length) return { driver: { column: sql`cs.item_key`, values: q.items }, used: 'items' };
  return { driver: null, used: null };
}

function salesResiduals(q: SalesFeedQuery, used: keyof SalesFeedQuery | null): RawBuilder<SqlBool>[] {
  const out: RawBuilder<SqlBool>[] = [];
  if (used !== 'customers' && q.customers?.length) out.push(sql<SqlBool>`cs.customer_uuid_bin IN (${sql.join(q.customers.map(uuidToBin))})`);
  if (used !== 'owners' && q.owners?.length) out.push(sql<SqlBool>`cs.shop_owner_uuid_bin IN (${sql.join(q.owners.map(uuidToBin))})`);
  if (used !== 'firms' && q.firms?.length) out.push(sql<SqlBool>`cs.shop_firm_id IN (${sql.join([...q.firms])})`);
  if (used !== 'items' && q.items?.length) out.push(sql<SqlBool>`cs.item_key IN (${sql.join([...q.items])})`);
  if (q.direction) out.push(sql<SqlBool>`cs.direction = ${q.direction}`);
  for (const b of q.price ?? []) out.push(sql<SqlBool>`cs.total_price ${sql.raw(PRICE_OPS[b.op])} CAST(${b.value} AS DECIMAL(19,2))`);
  if (q.from) out.push(sql<SqlBool>`cs.occurred_at >= ${q.from}`);
  if (q.to) out.push(sql<SqlBool>`cs.occurred_at < ${q.to}`);
  if (q.itemName) out.push(sql<SqlBool>`cs.item_name LIKE ${'%' + q.itemName.replace(/[\\%_]/g, (c) => `\\${c}`) + '%'}`);
  return out;
}

function salesBranches(q: SalesFeedQuery, page: KeysetPage | null, limit: number): RawBuilder<unknown> {
  const dir = page ? readDir(page) : 'desc';
  const { driver, used } = salesDriver(q);
  const base = salesResiduals(q, used);
  if (page?.cursor) base.push(afterCursor(sql`cs.occurred_at`, sql`cs.sale_id`, page.cursor, dir));
  const branch = (extra: RawBuilder<SqlBool> | null) => {
    const conds = extra ? [extra, ...base] : base;
    return sql`SELECT cs.sale_id, cs.occurred_at FROM chestshop_sale cs
      ${conds.length ? sql`WHERE ${sql.join(conds, sql` AND `)}` : sql``}
      ${orderByTime(sql`cs.occurred_at`, sql`cs.sale_id`, dir)} LIMIT ${limit}`;
  };
  if (!driver) return branch(null);
  const branches = driver.values.map((v) => branch(sql<SqlBool>`${driver.column} = ${v}`));
  if (branches.length === 1) return branches[0] as RawBuilder<unknown>;
  return sql.join(branches.map((b) => sql`(${b})`), sql` UNION ALL `);
}

/**
 * One keyset page of the sales feed with customer attribution, in read order
 * (`inPageOrder` flips a prev page). Fetches `limit + 1` rows as a probe.
 */
export async function listSalesPage(q: SalesFeedQuery, page: KeysetPage): Promise<MarketSaleRow[]> {
  const probe = page.limit + 1;
  const dir = readDir(page);
  const r = await sql<RawSale & { customer_uuid_bin: Buffer | null; customer_name: string | null }>`
    SELECT cs.sale_id, cs.txn_id, cs.occurred_at, cs.direction, cs.quantity, cs.unit_price,
           cs.total_price, cs.tax_amount, cs.material, cs.item_key, cs.item_name, cs.item_custom,
           cs.is_admin_shop AS admin_shop, cs.shop_account_type, cs.shop_firm_id, cs.shop_owner_uuid_bin,
           COALESCE(f.display_name, fp.current_name, a.display_name) AS owner_name,
           cs.world, cs.sign_x, cs.sign_y, cs.sign_z, cs.shop_stock,
           cs.customer_uuid_bin, cfp.current_name AS customer_name
    FROM (${salesBranches(q, page, probe)}) u
    JOIN chestshop_sale cs ON cs.sale_id = u.sale_id
    LEFT JOIN firm f ON f.firm_id = cs.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = cs.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = cs.shop_owner_uuid_bin
    LEFT JOIN economy_players cfp ON cfp.player_uuid_bin = cs.customer_uuid_bin
    ${orderByTime(sql`u.occurred_at`, sql`u.sale_id`, dir)}
    LIMIT ${probe}
  `.execute(db);
  return r.rows.map((row) => ({
    ...toSaleRow(row),
    customer_uuid: row.customer_uuid_bin ? binToUuid(row.customer_uuid_bin) : null,
    customer_name: row.customer_name,
  }));
}

/** Matching sales, counted no further than `cap + 1` ("1,000+"). */
export async function countSalesPage(q: SalesFeedQuery, cap: number): Promise<{ count: number; capped: boolean }> {
  const r = await sql<{ c: number | string }>`
    SELECT COUNT(*) AS c FROM (SELECT u.sale_id FROM (${salesBranches(q, null, cap + 1)}) u LIMIT ${cap + 1}) capped
  `.execute(db);
  const n = Number(r.rows[0]?.c ?? 0);
  return { count: Math.min(n, cap), capped: n > cap };
}

/** The first sale at or after `at` (idx_cs_time seek), for jump-to-date. */
export async function findFirstSaleAtOrAfter(at: Date): Promise<{ sale_id: number; occurred_at: Date } | null> {
  const r = await sql<{ sale_id: number | string; occurred_at: Date }>`
    SELECT cs.sale_id, cs.occurred_at FROM chestshop_sale cs
    WHERE cs.occurred_at >= ${at} ORDER BY cs.occurred_at, cs.sale_id LIMIT 1
  `.execute(db);
  const row = r.rows[0];
  return row ? { sale_id: Number(row.sale_id), occurred_at: row.occurred_at } : null;
}

// ── Player market activity ───────────────────────────────────────────────

/**
 * The viewer's own ChestShop trades for /me/market, BOTH sides of the book:
 *   • rows where they were the customer (clicked someone else's sign), and
 *   • rows where a customer traded at a PERSONAL shop they own.
 * Without the owner side, a player who runs shops sees none of their sales —
 * those rows carry the player as shop_owner, not customer. `player_role` lets
 * the page flip the buy/sell perspective and pick the counterparty.
 *
 * Firm/business shops are intentionally excluded — the owner side is gated on
 * shop_account_type = 'PERSONAL', so a firm's books stay on the access-gated
 * firm pages, not a personal dashboard (even if the plugin happens to stamp an
 * owner UUID on a business shop row).
 *
 * `days = null` means all-time; otherwise the trailing N-day window.
 */
export async function listPlayerSales(playerUuid: string, days: number | null): Promise<PlayerSaleRow[]> {
  const bin = uuidToBin(playerUuid);
  const windowCond = days != null ? sql`AND cs.occurred_at >= NOW() - INTERVAL ${days} DAY` : sql``;
  const r = await sql<RawSale & { customer_uuid_bin: Buffer | null; customer_name: string | null; player_role: 'customer' | 'owner' }>`
    SELECT cs.sale_id, cs.txn_id, cs.occurred_at, cs.direction, cs.quantity, cs.unit_price,
           cs.total_price, cs.tax_amount, cs.material, cs.item_key, cs.item_name, cs.item_custom,
           cs.is_admin_shop AS admin_shop, cs.shop_account_type, cs.shop_firm_id, cs.shop_owner_uuid_bin,
           COALESCE(f.display_name, fp.current_name, a.display_name) AS owner_name,
           cs.world, cs.sign_x, cs.sign_y, cs.sign_z, cs.shop_stock,
           cs.customer_uuid_bin, cfp.current_name AS customer_name,
           CASE WHEN cs.customer_uuid_bin = ${bin} THEN 'customer' ELSE 'owner' END AS player_role
    FROM chestshop_sale cs
    LEFT JOIN firm f ON f.firm_id = cs.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = cs.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = cs.shop_owner_uuid_bin
    LEFT JOIN economy_players cfp ON cfp.player_uuid_bin = cs.customer_uuid_bin
    WHERE (cs.customer_uuid_bin = ${bin}
           OR (cs.shop_owner_uuid_bin = ${bin} AND cs.shop_account_type = 'PERSONAL'))
      ${windowCond}
    ORDER BY cs.occurred_at ASC
  `.execute(db);
  return r.rows.map((row) => ({
    ...toSaleRow(row),
    customer_uuid: row.customer_uuid_bin ? binToUuid(row.customer_uuid_bin) : null,
    customer_name: row.customer_name,
    player_role: row.player_role,
  }));
}

// ── Live shops ────────────────────────────────────────────────────────────

export async function listShops(args: {
  itemKey: string | null;
  material: string | null;
  firmId: number | null;
  ownerUuid: string | null;
  buyable: boolean;
  direction?: 'buy' | 'sell';
  inStock: boolean;
  search: string | null;
  limit: number;
  offset: number;
}): Promise<MarketShopRow[]> {
  const conds: ReturnType<typeof sql>[] = [sql`s.active = 1`];
  if (args.itemKey) conds.push(sql`s.item_key = ${args.itemKey}`);
  if (args.material) conds.push(sql`s.material = ${args.material}`);
  if (args.firmId !== null) conds.push(sql`s.shop_firm_id = ${args.firmId}`);
  if (args.ownerUuid) conds.push(sql`s.shop_owner_uuid_bin = ${uuidToBin(args.ownerUuid)}`);
  if (args.direction === 'sell') conds.push(sql`s.sell_price IS NOT NULL`);
  else if (args.buyable || args.direction === 'buy') conds.push(sql`s.buy_price IS NOT NULL`);
  if (args.inStock) conds.push(sql`(s.is_admin_shop = 1 OR s.current_stock >= GREATEST(s.batch_qty, 1))`);
  if (args.search) {
    conds.push(sql`(s.item_name LIKE CONCAT('%', ${args.search}, '%') OR s.material LIKE CONCAT('%', ${args.search}, '%'))`);
  }
  const where = sql`WHERE ${sql.join(conds, sql` AND `)}`;

  const r = await sql<RawShop>`
    SELECT s.shop_id, s.world, s.sign_x, s.sign_y, s.sign_z, s.is_admin_shop AS admin_shop,
      s.shop_account_type, s.shop_firm_id, s.shop_owner_uuid_bin,
      COALESCE(f.display_name, fp.current_name, a.display_name) AS owner_name,
      s.material, s.item_key, s.item_name, s.item_custom,
      s.buy_price, s.sell_price, s.batch_qty, s.current_stock, s.estimated_capacity AS current_space, s.stock_at, s.last_seen, s.active
    FROM chestshop_shop s
    LEFT JOIN firm f ON f.firm_id = s.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = s.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = s.shop_owner_uuid_bin
    ${where}
    ORDER BY ${args.direction === 'sell'
      ? sql`(s.sell_price / GREATEST(s.batch_qty, 1)) DESC`
      : sql`(s.current_stock IS NOT NULL AND s.current_stock < GREATEST(s.batch_qty, 1) AND s.is_admin_shop = 0), (s.buy_price IS NULL), (s.buy_price / GREATEST(s.batch_qty, 1)) ASC`},
      s.last_seen DESC, s.shop_id ASC
    LIMIT ${args.limit} OFFSET ${args.offset}
  `.execute(db);
  return r.rows.map(toShopRow);
}

export async function countShops(args: {
  itemKey: string | null;
  material: string | null;
  firmId: number | null;
  ownerUuid: string | null;
  buyable: boolean;
  direction?: 'buy' | 'sell';
  inStock: boolean;
  search: string | null;
}): Promise<number> {
  const conds: ReturnType<typeof sql>[] = [sql`s.active = 1`];
  if (args.itemKey) conds.push(sql`s.item_key = ${args.itemKey}`);
  if (args.material) conds.push(sql`s.material = ${args.material}`);
  if (args.firmId !== null) conds.push(sql`s.shop_firm_id = ${args.firmId}`);
  if (args.ownerUuid) conds.push(sql`s.shop_owner_uuid_bin = ${uuidToBin(args.ownerUuid)}`);
  if (args.direction === 'sell') conds.push(sql`s.sell_price IS NOT NULL`);
  else if (args.buyable || args.direction === 'buy') conds.push(sql`s.buy_price IS NOT NULL`);
  if (args.inStock) conds.push(sql`(s.is_admin_shop = 1 OR s.current_stock >= GREATEST(s.batch_qty, 1))`);
  if (args.search) {
    conds.push(sql`(s.item_name LIKE CONCAT('%', ${args.search}, '%') OR s.material LIKE CONCAT('%', ${args.search}, '%'))`);
  }
  const where = sql`WHERE ${sql.join(conds, sql` AND `)}`;
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM chestshop_shop s ${where}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

// ── Internal converters ────────────────────────────────────────────────

interface RawMarketItem {
  item_key: string;
  material: string | null;
  item_name: string | null;
  item_custom: number;
  trade_count: string | number;
  total_quantity: string | number;
  total_volume: string;
}
function toMarketItemRow(r: RawMarketItem): MarketItemRow {
  return {
    item_key: r.item_key,
    material: r.material,
    item_name: r.item_name,
    item_custom: r.item_custom,
    trade_count: Number(r.trade_count),
    total_quantity: Number(r.total_quantity),
    total_volume: r.total_volume,
  };
}

interface RawSale {
  sale_id: number;
  txn_id: number;
  occurred_at: Date | null;
  direction: string;
  quantity: string | number;
  unit_price: string;
  total_price: string;
  tax_amount: string;
  material: string | null;
  item_key: string;
  item_name: string | null;
  item_custom: number;
  admin_shop: number;
  shop_account_type: string | null;
  shop_firm_id: number | null;
  shop_owner_uuid_bin: Buffer | null;
  owner_name: string | null;
  world: string | null;
  sign_x: number | null;
  sign_y: number | null;
  sign_z: number | null;
  shop_stock: number | null;
}
function toSaleRow(r: RawSale): MarketSaleRow {
  return {
    sale_id: r.sale_id,
    txn_id: r.txn_id,
    occurred_at: r.occurred_at,
    direction: r.direction,
    quantity: Number(r.quantity),
    unit_price: r.unit_price,
    total_price: r.total_price,
    tax_amount: r.tax_amount,
    material: r.material,
    item_key: r.item_key,
    item_name: r.item_name,
    item_custom: r.item_custom,
    admin_shop: r.admin_shop,
    shop_account_type: r.shop_account_type,
    shop_firm_id: r.shop_firm_id,
    shop_owner_uuid: r.shop_owner_uuid_bin ? binToUuid(r.shop_owner_uuid_bin) : null,
    owner_name: r.owner_name,
    world: r.world,
    sign_x: r.sign_x,
    sign_y: r.sign_y,
    sign_z: r.sign_z,
    shop_stock: r.shop_stock,
  };
}

interface RawSeller {
  admin_shop: number;
  shop_account_type: string | null;
  shop_firm_id: number | null;
  shop_owner_uuid_bin: Buffer | null;
  shop_account_id: number | null;
  owner_name: string | null;
  sale_count: string | number;
  total_quantity: string | number;
  total_volume: string;
}
function toSellerRow(r: RawSeller): MarketSellerRow {
  return {
    admin_shop: r.admin_shop,
    shop_account_type: r.shop_account_type,
    shop_firm_id: r.shop_firm_id,
    shop_owner_uuid: r.shop_owner_uuid_bin ? binToUuid(r.shop_owner_uuid_bin) : null,
    shop_account_id: r.shop_account_id,
    owner_name: r.owner_name,
    sale_count: Number(r.sale_count),
    total_quantity: Number(r.total_quantity),
    total_volume: r.total_volume,
  };
}

interface RawShop {
  shop_id: number;
  world: string | null;
  sign_x: number;
  sign_y: number;
  sign_z: number;
  admin_shop: number;
  shop_account_type: string | null;
  shop_firm_id: number | null;
  shop_owner_uuid_bin: Buffer | null;
  owner_name: string | null;
  material: string | null;
  item_key: string;
  item_name: string | null;
  item_custom: number;
  buy_price: string | null;
  sell_price: string | null;
  batch_qty: number;
  current_stock: number | null;
  current_space: number | null;
  stock_at: Date | null;
  last_seen: Date | null;
  active: number;
}
function toShopRow(r: RawShop): MarketShopRow {
  return {
    shop_id: r.shop_id,
    world: r.world,
    sign_x: r.sign_x,
    sign_y: r.sign_y,
    sign_z: r.sign_z,
    admin_shop: r.admin_shop,
    shop_account_type: r.shop_account_type,
    shop_firm_id: r.shop_firm_id,
    shop_owner_uuid: r.shop_owner_uuid_bin ? binToUuid(r.shop_owner_uuid_bin) : null,
    owner_name: r.owner_name,
    material: r.material,
    item_key: r.item_key,
    item_name: r.item_name,
    item_custom: r.item_custom,
    buy_price: r.buy_price,
    sell_price: r.sell_price,
    batch_qty: r.batch_qty,
    current_stock: r.current_stock,
    current_space: r.current_space,
    stock_at: r.stock_at,
    last_seen: r.last_seen,
    active: r.active,
  };
}

// ── Rollup-backed market views (PAR-537) ─────────────────────────────────
//
// Read explorer_rollup_item_day through idx_erid_day (day) or its primary key
// (item_key, day, direction): bounded by the day range, never by sale history.
// Days are 'YYYY-MM-DD' UTC, inclusive. Callers check item_day coverage.

export interface ItemWindowRow {
  item_key: string;
  trades: number;
  qty: number;
  volume: string;
  /** Quantity-weighted average unit price over the window, DECIMAL(19,4) string; null without quantity. */
  avg_price: string | null;
}

/** Window totals per item. */
export async function selectItemWindow(from: string, to: string, limit: number, order: 'trades' | 'volume'): Promise<ItemWindowRow[]> {
  const r = await sql<{ item_key: string; trades: number | string; qty: number | string; volume: string; avg_price: string | null }>`
    SELECT item_key, SUM(trades) AS trades, SUM(qty) AS qty, SUM(volume) AS volume,
           CAST(SUM(volume) / NULLIF(SUM(qty), 0) AS DECIMAL(19,4)) AS avg_price
    FROM explorer_rollup_item_day
    WHERE day BETWEEN ${from} AND ${to}
    GROUP BY item_key
    ORDER BY ${order === 'volume' ? sql`SUM(volume)` : sql`SUM(trades)`} DESC, item_key
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({ ...row, trades: Number(row.trades), qty: Number(row.qty) }));
}

export interface ItemMoverRow {
  item_key: string;
  trades: number;
  volume: string;
  /** Average unit price in the earlier and later window (DECIMAL(19,4) strings). */
  before_price: string;
  after_price: string;
}

/**
 * Average unit price in [prevFrom, prevTo] against [from, to] for items traded
 * at least `minTrades` times in each window. Ranked by the size of the move
 * by the caller; the database only aggregates.
 */
export async function selectItemMovers(prevFrom: string, prevTo: string, from: string, to: string, minTrades: number): Promise<ItemMoverRow[]> {
  const r = await sql<{ item_key: string; trades: number | string; volume: string; before_price: string; after_price: string }>`
    SELECT item_key,
           SUM(CASE WHEN day >= ${from} THEN trades ELSE 0 END) AS trades,
           SUM(CASE WHEN day >= ${from} THEN volume ELSE 0 END) AS volume,
           CAST(SUM(CASE WHEN day <= ${prevTo} THEN volume ELSE 0 END) / NULLIF(SUM(CASE WHEN day <= ${prevTo} THEN qty ELSE 0 END), 0) AS DECIMAL(19,4)) AS before_price,
           CAST(SUM(CASE WHEN day >= ${from} THEN volume ELSE 0 END) / NULLIF(SUM(CASE WHEN day >= ${from} THEN qty ELSE 0 END), 0) AS DECIMAL(19,4)) AS after_price
    FROM explorer_rollup_item_day
    WHERE day BETWEEN ${prevFrom} AND ${to}
    GROUP BY item_key
    HAVING SUM(CASE WHEN day <= ${prevTo} THEN trades ELSE 0 END) >= ${minTrades}
       AND SUM(CASE WHEN day >= ${from} THEN trades ELSE 0 END) >= ${minTrades}
       AND before_price > 0 AND after_price IS NOT NULL
  `.execute(db);
  return r.rows.map((row) => ({ ...row, trades: Number(row.trades) }));
}

export interface MarketDayRow { day: string; trades: number; qty: number; volume: string; items: number }

/** Market-wide totals per day (both directions). */
export async function selectMarketDays(from: string, to: string): Promise<MarketDayRow[]> {
  const r = await sql<{ day: string; trades: number | string; qty: number | string; volume: string; items: number | string }>`
    SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, SUM(trades) AS trades, SUM(qty) AS qty, SUM(volume) AS volume,
           COUNT(DISTINCT item_key) AS items
    FROM explorer_rollup_item_day
    WHERE day BETWEEN ${from} AND ${to}
    GROUP BY day
    ORDER BY day
  `.execute(db);
  return r.rows.map((row) => ({ day: row.day, trades: Number(row.trades), qty: Number(row.qty), volume: row.volume, items: Number(row.items) }));
}

/** Distinct items traded over a range. */
export async function countItemsTraded(from: string, to: string): Promise<number> {
  const r = await sql<{ c: number | string }>`
    SELECT COUNT(DISTINCT item_key) AS c FROM explorer_rollup_item_day WHERE day BETWEEN ${from} AND ${to}
  `.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

export interface ItemIdentityRow { item_key: string; item_name: string | null; material: string | null; item_custom: number }

/**
 * Display identity (name, material, custom flag) for item keys, from each
 * key's latest sale: one idx_cs_item (item_key, occurred_at) probe per key.
 */
export async function findItemIdentities(itemKeys: readonly string[]): Promise<ItemIdentityRow[]> {
  if (itemKeys.length === 0) return [];
  const r = await sql<ItemIdentityRow>`
    SELECT k.item_key, s.item_name, s.material, s.item_custom
    FROM (SELECT ${sql.join(itemKeys.map((key, i) => (i === 0 ? sql`${key} AS item_key` : sql`${key}`)), sql` UNION ALL SELECT `)}) k
    LEFT JOIN chestshop_sale s ON s.sale_id = (
      SELECT x.sale_id FROM chestshop_sale x WHERE x.item_key = k.item_key ORDER BY x.occurred_at DESC, x.sale_id DESC LIMIT 1
    )
  `.execute(db);
  return r.rows;
}

/** Newest shop signs (shop_id is assigned when a sign is first seen). */
export async function listNewestShops(limit: number): Promise<MarketShopRow[]> {
  const r = await sql<RawShop>`
    SELECT s.shop_id, s.world, s.sign_x, s.sign_y, s.sign_z, s.is_admin_shop AS admin_shop,
      s.shop_account_type, s.shop_firm_id, s.shop_owner_uuid_bin,
      COALESCE(f.display_name, fp.current_name, a.display_name) AS owner_name,
      s.material, s.item_key, s.item_name, s.item_custom,
      s.buy_price, s.sell_price, s.batch_qty, s.current_stock, s.estimated_capacity AS current_space, s.stock_at, s.last_seen, s.active
    FROM chestshop_shop s
    LEFT JOIN firm f ON f.firm_id = s.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = s.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = s.shop_owner_uuid_bin
    WHERE s.active = 1 AND s.visible = 1
    ORDER BY s.shop_id DESC
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map(toShopRow);
}

// ── Item catalogue (chip table over the item rollup) ──────────────────────

/** One row per item traded in [from, to]: the catalogue's base, bounded by the window. */
function catalogueWindow(from: string, to: string) {
  return sql`(
    SELECT item_key, SUM(trades) AS trades, SUM(qty) AS qty, SUM(volume) AS volume,
           CAST(SUM(volume) / NULLIF(SUM(qty), 0) AS DECIMAL(19,4)) AS avg_price
    FROM explorer_rollup_item_day
    WHERE day BETWEEN ${from} AND ${to}
    GROUP BY item_key
  ) c`;
}

export const itemsColumnMap = defineColumnMap({
  table: itemsTable,
  filters: {
    trades: { column: sql`c.trades` },
    volume: { column: sql`c.volume` },
    price: { column: sql`c.avg_price` },
  },
  sorts: { name: sql`c.item_key`, trades: sql`c.trades`, volume: sql`c.volume`, price: sql`c.avg_price` },
  tiebreak: { column: sql`c.item_key`, dir: 'asc' },
  search: (q) => {
    // Item keys are the searchable identity ("oak log" finds OAK_LOG).
    const t = q.trim().replace(/\s+/g, '_');
    return t ? sql<SqlBool>`c.item_key LIKE ${likeContains(t)}` : null;
  },
});

export async function listCatalogue(state: ItemsTableState, from: string, to: string, limit: number, offset: number): Promise<ItemWindowRow[]> {
  const c = compileTableState(state, itemsColumnMap);
  const r = await sql<{ item_key: string; trades: number | string; qty: number | string; volume: string; avg_price: string | null }>`
    SELECT c.item_key, c.trades, c.qty, c.volume, c.avg_price FROM ${catalogueWindow(from, to)} ${c.where} ${c.orderBy} LIMIT ${limit} OFFSET ${offset}
  `.execute(db);
  return r.rows.map((row) => ({ ...row, trades: Number(row.trades), qty: Number(row.qty) }));
}

export async function countCatalogue(state: ItemsTableState, from: string, to: string): Promise<number> {
  const c = compileTableState(state, itemsColumnMap);
  const r = await sql<{ n: number | string }>`SELECT COUNT(*) AS n FROM ${catalogueWindow(from, to)} ${c.where}`.execute(db);
  return Number(r.rows[0]?.n ?? 0);
}

// ── Shop finder (chip table over chestshop_shop) ──────────────────────────

/** The compared side of the sign, per item. */
function unitPrice(state: ShopsTableState) {
  return sellSide(state) ? sql`(s.sell_price / GREATEST(s.batch_qty, 1))` : sql`(s.buy_price / GREATEST(s.batch_qty, 1))`;
}
function sellSide(state: ShopsTableState): boolean {
  const d = state.filters.find((f) => f.field === 'direction');
  return !!d && d.values.length === 1 && d.values[0] === 'sell';
}

/**
 * chestshop_shop is about 51k rows. Item filters use idx_shop_item; the rest
 * are evaluated over the active shops, which bounds every query by the shop
 * table, never by sale history.
 */
function shopsColumnMap(state: ShopsTableState) {
  return defineColumnMap({
    table: shopsTable,
    filters: {
      item: { column: sql`s.item_key` },
      // The side is applied separately (it decides the price column); here it
      // only requires a price on that side.
      direction: { compile: () => (sellSide(state) ? sql<SqlBool>`s.sell_price IS NOT NULL` : sql<SqlBool>`s.buy_price IS NOT NULL`) },
      price: { column: unitPrice(state) },
      inStock: { compile: (f) => (f.values[0] === 'true'
        ? sql<SqlBool>`(s.is_admin_shop = 1 OR s.current_stock >= GREATEST(s.batch_qty, 1))`
        : sql<SqlBool>`(s.is_admin_shop = 0 AND (s.current_stock IS NULL OR s.current_stock < GREATEST(s.batch_qty, 1)))`) },
      owner: {
        compile: (f) => {
          const parts = f.values.map((v) => v === 'admin' ? sql<SqlBool>`s.is_admin_shop = 1`
            : v === 'firm' ? sql<SqlBool>`(s.is_admin_shop = 0 AND s.shop_firm_id IS NOT NULL)`
              : sql<SqlBool>`(s.is_admin_shop = 0 AND s.shop_firm_id IS NULL)`);
          return parts.length ? sql<SqlBool>`(${sql.join(parts, sql` OR `)})` : null;
        },
      },
      world: { column: sql`s.world` },
    },
    sorts: { price: unitPrice(state), stock: sql`s.current_stock`, seen: sql`s.last_seen` },
    tiebreak: { column: sql`s.shop_id`, dir: 'asc' },
    search: (q) => {
      const like = likeContains(q.trim());
      return sql<SqlBool>`(s.item_name LIKE ${like} OR s.material LIKE ${like})`;
    },
  });
}

function shopsWhere(state: ShopsTableState) {
  const c = compileTableState(state, shopsColumnMap(state));
  const conds = [sql<SqlBool>`s.active = 1`, sql<SqlBool>`s.visible = 1`, ...c.conditions];
  if (!state.filters.some((f) => f.field === 'direction')) conds.push(sql<SqlBool>`s.buy_price IS NOT NULL`);
  return { where: sql`WHERE ${sql.join(conds, sql` AND `)}`, orderBy: c.orderBy };
}

export async function listShopDirectory(state: ShopsTableState, limit: number, offset: number): Promise<MarketShopRow[]> {
  const { where, orderBy } = shopsWhere(state);
  const r = await sql<RawShop>`
    SELECT s.shop_id, s.world, s.sign_x, s.sign_y, s.sign_z, s.is_admin_shop AS admin_shop,
      s.shop_account_type, s.shop_firm_id, s.shop_owner_uuid_bin,
      COALESCE(f.display_name, fp.current_name, a.display_name) AS owner_name,
      s.material, s.item_key, s.item_name, s.item_custom,
      s.buy_price, s.sell_price, s.batch_qty, s.current_stock, s.estimated_capacity AS current_space, s.stock_at, s.last_seen, s.active
    FROM chestshop_shop s
    LEFT JOIN firm f ON f.firm_id = s.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = s.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = s.shop_owner_uuid_bin
    ${where}
    ${orderBy}
    LIMIT ${limit} OFFSET ${offset}
  `.execute(db);
  return r.rows.map(toShopRow);
}

export async function countShopDirectory(state: ShopsTableState): Promise<number> {
  const { where } = shopsWhere(state);
  const r = await sql<{ c: number | string }>`SELECT COUNT(*) AS c FROM chestshop_shop s ${where}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

/** Item keys starting with `prefix` among live shops (idx_shop_item range). */
export async function findShopItemsByPrefix(prefix: string, limit: number): Promise<{ item_key: string; item_name: string }[]> {
  const t = prefix.trim().replace(/\s+/g, '_');
  const r = await sql<{ item_key: string; item_name: string }>`
    SELECT s.item_key, MAX(s.item_name) AS item_name FROM chestshop_shop s
    WHERE s.item_key LIKE ${`${t.replace(/[\\%_]/g, (ch) => `\\${ch}`)}%`} AND s.active = 1
    GROUP BY s.item_key ORDER BY s.item_key LIMIT ${limit}
  `.execute(db);
  return r.rows;
}
