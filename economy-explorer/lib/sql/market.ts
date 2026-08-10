import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';

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
export async function countDistinctItems(): Promise<number> {
  const r = await sql<{ c: string | number }>`SELECT COUNT(DISTINCT item_key) AS c FROM chestshop_sale`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

export async function volumeByDay(days: number): Promise<VolumeDayPoint[]> {
  const r = await sql<{ txn_date: string; txn_count: string | number; total_volume: string }>`
    SELECT DATE_FORMAT(DATE(occurred_at),'%Y-%m-%d') AS txn_date,
           COUNT(*) AS txn_count,
           COALESCE(SUM(total_price),0.00) AS total_volume
    FROM chestshop_sale
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

export async function listItems(args: { search: string | null; limit: number; offset: number }): Promise<MarketItemRow[]> {
  const where = args.search
    ? sql`WHERE (item_name LIKE CONCAT('%', ${args.search}, '%') OR material LIKE CONCAT('%', ${args.search}, '%') OR item_key LIKE CONCAT('%', ${args.search}, '%'))`
    : sql``;
  const r = await sql<RawMarketItem>`
    SELECT item_key, MAX(material) AS material, MAX(item_name) AS item_name,
           MAX(item_custom) AS item_custom, COUNT(*) AS trade_count,
           COALESCE(SUM(quantity),0) AS total_quantity, COALESCE(SUM(total_price),0.00) AS total_volume
    FROM chestshop_sale
    ${where}
    GROUP BY item_key ORDER BY trade_count DESC, item_name ASC
    LIMIT ${args.limit} OFFSET ${args.offset}
  `.execute(db);
  return r.rows.map(toMarketItemRow);
}

export async function countItems(search: string | null): Promise<number> {
  const where = search
    ? sql`WHERE (item_name LIKE CONCAT('%', ${search}, '%') OR material LIKE CONCAT('%', ${search}, '%') OR item_key LIKE CONCAT('%', ${search}, '%'))`
    : sql``;
  const r = await sql<{ c: string | number }>`SELECT COUNT(DISTINCT item_key) AS c FROM chestshop_sale ${where}`.execute(db);
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
    const current = series.length ? series[series.length - 1].price : null;
    const first = series.length ? series[0].price : null;
    const change = first && first > 0 && current != null ? (current - first) / first : null;
    return { ...i, series, current_price: current, price_change_pct: change };
  });
}

// ── Per-item ────────────────────────────────────────────────────────────

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
// currently unused. Do NOT wire either into a public page without an isStaff
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

export async function getMarketFirm(firmId: number): Promise<MarketFirmRow | null> {
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

export async function listFirmMarketItems(firmId: number, limit: number, offset: number): Promise<MarketItemRow[]> {
  const r = await sql<RawMarketItem>`
    SELECT item_key, MAX(material) AS material, MAX(item_name) AS item_name,
           MAX(item_custom) AS item_custom, COUNT(*) AS trade_count,
           COALESCE(SUM(quantity),0) AS total_quantity, COALESCE(SUM(total_price),0.00) AS total_volume
    FROM chestshop_sale WHERE shop_firm_id = ${firmId}
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

// ── Customer-side sales feed (admin/government only) ─────────────────────

/** Mirrors MarketReadMapper.listSales (line 258-274). Customer column included. */
export async function listSalesFeed(args: {
  customerUuid: string | null;
  search: string | null;
  limit: number;
  offset: number;
}): Promise<MarketSaleRow[]> {
  const conds: ReturnType<typeof sql>[] = [];
  if (args.customerUuid) conds.push(sql`cs.customer_uuid_bin = ${uuidToBin(args.customerUuid)}`);
  if (args.search) {
    conds.push(sql`(cs.item_name LIKE CONCAT('%', ${args.search}, '%')
      OR cs.material LIKE CONCAT('%', ${args.search}, '%')
      OR f.display_name LIKE CONCAT('%', ${args.search}, '%')
      OR a.display_name LIKE CONCAT('%', ${args.search}, '%'))`);
  }
  const where = conds.length ? sql`WHERE ${sql.join(conds, sql` AND `)}` : sql``;

  const r = await sql<RawSale & { customer_uuid_bin: Buffer | null; customer_name: string | null }>`
    SELECT cs.sale_id, cs.txn_id, cs.occurred_at, cs.direction, cs.quantity, cs.unit_price,
           cs.total_price, cs.tax_amount, cs.material, cs.item_key, cs.item_name, cs.item_custom,
           cs.is_admin_shop AS admin_shop, cs.shop_account_type, cs.shop_firm_id, cs.shop_owner_uuid_bin,
           COALESCE(f.display_name, fp.current_name, a.display_name) AS owner_name,
           cs.world, cs.sign_x, cs.sign_y, cs.sign_z, cs.shop_stock,
           cs.customer_uuid_bin,
           COALESCE(cfp.current_name, ca.display_name) AS customer_name
    FROM chestshop_sale cs
    LEFT JOIN firm f ON f.firm_id = cs.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = cs.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = cs.shop_owner_uuid_bin
    LEFT JOIN accounts ca ON ca.owner_uuid_bin = cs.customer_uuid_bin AND ca.account_type='PERSONAL'
    LEFT JOIN economy_players cfp ON cfp.player_uuid_bin = cs.customer_uuid_bin
    ${where}
    ORDER BY cs.occurred_at DESC, cs.sale_id DESC LIMIT ${args.limit} OFFSET ${args.offset}
  `.execute(db);
  return r.rows.map((row) => ({
    ...toSaleRow(row),
    customer_uuid: row.customer_uuid_bin ? binToUuid(row.customer_uuid_bin) : null,
    customer_name: row.customer_name,
  }));
}

export async function countSalesFeed(args: {
  customerUuid: string | null;
  search: string | null;
}): Promise<number> {
  const conds: ReturnType<typeof sql>[] = [];
  if (args.customerUuid) conds.push(sql`cs.customer_uuid_bin = ${uuidToBin(args.customerUuid)}`);
  if (args.search) {
    conds.push(sql`(cs.item_name LIKE CONCAT('%', ${args.search}, '%')
      OR cs.material LIKE CONCAT('%', ${args.search}, '%')
      OR f.display_name LIKE CONCAT('%', ${args.search}, '%')
      OR a.display_name LIKE CONCAT('%', ${args.search}, '%'))`);
  }
  const where = conds.length ? sql`WHERE ${sql.join(conds, sql` AND `)}` : sql``;
  const r = await sql<{ c: string | number }>`
    SELECT COUNT(*) AS c FROM chestshop_sale cs
    LEFT JOIN firm f ON f.firm_id = cs.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = cs.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = cs.shop_owner_uuid_bin
    ${where}
  `.execute(db);
  return Number(r.rows[0]?.c ?? 0);
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
  if (args.buyable) conds.push(sql`s.buy_price IS NOT NULL`);
  if (args.inStock) conds.push(sql`(s.is_admin_shop = 1 OR s.current_stock > 0)`);
  if (args.search) {
    conds.push(sql`(s.item_name LIKE CONCAT('%', ${args.search}, '%') OR s.material LIKE CONCAT('%', ${args.search}, '%'))`);
  }
  const where = sql`WHERE ${sql.join(conds, sql` AND `)}`;

  const r = await sql<RawShop>`
    SELECT s.shop_id, s.world, s.sign_x, s.sign_y, s.sign_z, s.is_admin_shop AS admin_shop,
      s.shop_account_type, s.shop_firm_id, s.shop_owner_uuid_bin,
      COALESCE(f.display_name, fp.current_name, a.display_name) AS owner_name,
      s.material, s.item_key, s.item_name, s.item_custom,
      s.buy_price, s.sell_price, s.batch_qty, s.current_stock, s.stock_at, s.last_seen, s.active
    FROM chestshop_shop s
    LEFT JOIN firm f ON f.firm_id = s.shop_firm_id
    LEFT JOIN accounts a ON a.account_id = s.shop_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = s.shop_owner_uuid_bin
    ${where}
    ORDER BY (s.current_stock IS NOT NULL AND s.current_stock = 0), (s.buy_price IS NULL),
      s.buy_price ASC, s.last_seen DESC
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
  inStock: boolean;
  search: string | null;
}): Promise<number> {
  const conds: ReturnType<typeof sql>[] = [sql`s.active = 1`];
  if (args.itemKey) conds.push(sql`s.item_key = ${args.itemKey}`);
  if (args.material) conds.push(sql`s.material = ${args.material}`);
  if (args.firmId !== null) conds.push(sql`s.shop_firm_id = ${args.firmId}`);
  if (args.ownerUuid) conds.push(sql`s.shop_owner_uuid_bin = ${uuidToBin(args.ownerUuid)}`);
  if (args.buyable) conds.push(sql`s.buy_price IS NOT NULL`);
  if (args.inStock) conds.push(sql`(s.is_admin_shop = 1 OR s.current_stock > 0)`);
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
    stock_at: r.stock_at,
    last_seen: r.last_seen,
    active: r.active,
  };
}
