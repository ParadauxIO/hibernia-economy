import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';

// Pure reads of the explorer_rollup_* tables (V43), written by lib/rollup.
// Every query is bounded by a day range on a rollup primary key or day index,
// never by raw ledger tables. Days are 'YYYY-MM-DD' UTC strings, inclusive.
// Business logic (coverage, categories, balance derivation) lives in
// lib/services/rollup.ts.

export interface CoverageRecord {
  job: string;
  from_day: string;
  to_day: string;
  history_complete: number;
  /** ISO-8601 UTC. */
  updated_at: string;
}

/** TIMESTAMP → ISO string through UNIX_TIMESTAMP, independent of the session time zone. */
function isoFromEpoch(epoch: string | number | null | undefined): string | null {
  return epoch === null || epoch === undefined ? null : new Date(Math.round(Number(epoch) * 1000)).toISOString();
}

export async function selectCoverage(): Promise<CoverageRecord[]> {
  const r = await sql<Omit<CoverageRecord, 'updated_at'> & { updated_epoch: string }>`
    SELECT job, DATE_FORMAT(from_day, '%Y-%m-%d') AS from_day, DATE_FORMAT(to_day, '%Y-%m-%d') AS to_day,
           history_complete, UNIX_TIMESTAMP(updated_at) AS updated_epoch
    FROM explorer_rollup_coverage
  `.execute(db);
  return r.rows.map(({ updated_epoch, ...row }) => ({
    ...row, history_complete: Number(row.history_complete), updated_at: isoFromEpoch(updated_epoch)!,
  }));
}

export interface RunSummary {
  last_success_at: string | null;
  last_error_at: string | null;
  errors_last_hour: number;
}

/** Newest successful and failed step, from the run log's started_at index. */
export async function selectRunSummary(): Promise<RunSummary> {
  const r = await sql<{ last_success_at: string | null; last_error_at: string | null; errors_last_hour: string | number }>`
    SELECT
      (SELECT UNIX_TIMESTAMP(MAX(finished_at)) FROM explorer_rollup_run
         WHERE error IS NULL AND started_at >= CURRENT_TIMESTAMP - INTERVAL 7 DAY) AS last_success_at,
      (SELECT UNIX_TIMESTAMP(MAX(finished_at)) FROM explorer_rollup_run
         WHERE error IS NOT NULL AND started_at >= CURRENT_TIMESTAMP - INTERVAL 7 DAY) AS last_error_at,
      (SELECT COUNT(*) FROM explorer_rollup_run
         WHERE error IS NOT NULL AND started_at >= CURRENT_TIMESTAMP - INTERVAL 1 HOUR) AS errors_last_hour
  `.execute(db);
  const row = r.rows[0];
  return {
    last_success_at: isoFromEpoch(row?.last_success_at),
    last_error_at: isoFromEpoch(row?.last_error_at),
    errors_last_hour: Number(row?.errors_last_hour ?? 0),
  };
}

export interface AccountDayTotalRow { day: string; inflow: string; outflow: string; txn_count: number }

/** Per-day totals over a set of accounts. `excludeInternal` drops is_internal rows. */
export async function selectAccountDayTotals(
  accountIds: readonly number[], from: string, to: string, excludeInternal: boolean,
): Promise<AccountDayTotalRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<{ day: string; inflow: string; outflow: string; txn_count: string | number }>`
    SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, SUM(inflow) AS inflow, SUM(outflow) AS outflow,
           SUM(txn_count) AS txn_count
    FROM explorer_rollup_account_day
    WHERE account_id IN (${sql.join(accountIds)}) AND day BETWEEN ${from} AND ${to}
      ${excludeInternal ? sql`AND is_internal = 0` : sql``}
    GROUP BY day
    ORDER BY day
  `.execute(db);
  return r.rows.map((row) => ({ ...row, txn_count: Number(row.txn_count) }));
}

/** Net change (inflow - outflow) per day from `from` onwards, over a set of accounts. */
export async function selectAccountNetSince(accountIds: readonly number[], from: string): Promise<{ day: string; net: string }[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<{ day: string; net: string }>`
    SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, SUM(inflow) - SUM(outflow) AS net
    FROM explorer_rollup_account_day
    WHERE account_id IN (${sql.join(accountIds)}) AND day >= ${from}
    GROUP BY day
    ORDER BY day
  `.execute(db);
  return r.rows;
}

/** Current materialised balance summed over a set of accounts. */
export async function selectCurrentBalance(accountIds: readonly number[]): Promise<string> {
  if (accountIds.length === 0) return '0.00';
  const r = await sql<{ balance: string | null }>`
    SELECT COALESCE(SUM(balance), 0.00) AS balance FROM account_balances_mat
    WHERE account_id IN (${sql.join(accountIds)})
  `.execute(db);
  return r.rows[0]?.balance ?? '0.00';
}

export interface AccountFlowGroupRow {
  plugin_key: string;
  tax_kind: string;
  counterparty_type: string;
  is_internal: number;
  inflow: string;
  outflow: string;
}

/** Flows over a set of accounts and range, grouped by the categorisation inputs. */
export async function selectAccountFlowGroups(accountIds: readonly number[], from: string, to: string): Promise<AccountFlowGroupRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<AccountFlowGroupRow>`
    SELECT plugin_key, tax_kind, counterparty_type, is_internal, SUM(inflow) AS inflow, SUM(outflow) AS outflow
    FROM explorer_rollup_account_day
    WHERE account_id IN (${sql.join(accountIds)}) AND day BETWEEN ${from} AND ${to}
    GROUP BY plugin_key, tax_kind, counterparty_type, is_internal
  `.execute(db);
  return r.rows.map((row) => ({ ...row, is_internal: Number(row.is_internal) }));
}

export interface CounterpartyMonthRow {
  counterparty_id: number;
  display_name: string | null;
  account_type: string;
  owner_uuid_bin: Buffer | null;
  owner_name: string | null;
  inflow: string;
  outflow: string;
  txn_count: number;
}

/**
 * Counterparties of a set of accounts over whole months [fromMonth, toMonth]
 * (first days of months), largest combined volume first. Counterparties inside
 * the set are excluded: those are the set's internal transfers.
 */
export async function selectCounterpartyMonths(
  accountIds: readonly number[], fromMonth: string, toMonth: string, limit: number,
): Promise<CounterpartyMonthRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<Omit<CounterpartyMonthRow, 'txn_count'> & { txn_count: string | number }>`
    SELECT pm.counterparty_id, a.display_name, a.account_type, a.owner_uuid_bin, fp.current_name AS owner_name,
           pm.inflow, pm.outflow, pm.txn_count
    FROM (
      SELECT counterparty_id, SUM(inflow) AS inflow, SUM(outflow) AS outflow, SUM(txn_count) AS txn_count
      FROM explorer_rollup_pair_month
      WHERE account_id IN (${sql.join(accountIds)}) AND month BETWEEN ${fromMonth} AND ${toMonth}
        AND counterparty_id NOT IN (${sql.join(accountIds)})
      GROUP BY counterparty_id
      ORDER BY SUM(inflow) + SUM(outflow) DESC, counterparty_id
      LIMIT ${limit}
    ) pm
    JOIN accounts a ON a.account_id = pm.counterparty_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    ORDER BY pm.inflow + pm.outflow DESC, pm.counterparty_id
  `.execute(db);
  return r.rows.map((row) => ({ ...row, txn_count: Number(row.txn_count) }));
}

export interface EconomyDayRow {
  day: string;
  txn_count: number;
  volume: string;
  raw_txn_count: number;
  raw_volume: string;
  bridge_pairs: number;
  active_players: number;
  new_players: number;
}

export async function selectEconomyDays(from: string, to: string): Promise<EconomyDayRow[]> {
  const r = await sql<Record<keyof EconomyDayRow, string | number>>`
    SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, txn_count, volume, raw_txn_count, raw_volume,
           bridge_pairs, active_players, new_players
    FROM explorer_rollup_economy_day
    WHERE day BETWEEN ${from} AND ${to}
    ORDER BY day
  `.execute(db);
  return r.rows.map((row) => ({
    day: String(row.day),
    txn_count: Number(row.txn_count),
    volume: String(row.volume),
    raw_txn_count: Number(row.raw_txn_count),
    raw_volume: String(row.raw_volume),
    bridge_pairs: Number(row.bridge_pairs),
    active_players: Number(row.active_players),
    new_players: Number(row.new_players),
  }));
}

export interface TypeDayRow { day: string; account_type: string; plugin_key: string; inflow: string; outflow: string }

export async function selectTypeDays(from: string, to: string): Promise<TypeDayRow[]> {
  const r = await sql<TypeDayRow>`
    SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, account_type, plugin_key, inflow, outflow
    FROM explorer_rollup_type_day
    WHERE day BETWEEN ${from} AND ${to}
    ORDER BY day, account_type, plugin_key
  `.execute(db);
  return r.rows;
}

export async function selectHeatmap(from: string, to: string): Promise<{ weekday: number; hour: number; txn_count: number }[]> {
  const r = await sql<{ weekday: number; hour: number; txn_count: string | number }>`
    SELECT weekday, hour, SUM(txn_count) AS txn_count
    FROM explorer_rollup_hour_heatmap
    WHERE day BETWEEN ${from} AND ${to}
    GROUP BY weekday, hour
    ORDER BY weekday, hour
  `.execute(db);
  return r.rows.map((row) => ({ weekday: Number(row.weekday), hour: Number(row.hour), txn_count: Number(row.txn_count) }));
}

export interface ItemDayRow {
  item_key: string;
  day: string;
  direction: 'BUY' | 'SELL';
  qty: number;
  volume: string;
  trades: number;
  min_unit_price: string;
  max_unit_price: string;
  open_unit_price: string;
  close_unit_price: string;
}

export async function selectItemDays(itemKeys: readonly string[], from: string, to: string): Promise<ItemDayRow[]> {
  if (itemKeys.length === 0) return [];
  const r = await sql<Omit<ItemDayRow, 'qty' | 'trades'> & { qty: string | number; trades: string | number }>`
    SELECT item_key, DATE_FORMAT(day, '%Y-%m-%d') AS day, direction, qty, volume, trades,
           min_unit_price, max_unit_price, open_unit_price, close_unit_price
    FROM explorer_rollup_item_day
    WHERE item_key IN (${sql.join(itemKeys)}) AND day BETWEEN ${from} AND ${to}
    ORDER BY item_key, day, direction
  `.execute(db);
  return r.rows.map((row) => ({ ...row, qty: Number(row.qty), trades: Number(row.trades) }));
}

export interface WealthSnapshotRow {
  day: string;
  taken_at: string;
  supply_personal: string;
  supply_business: string;
  supply_government: string;
  supply_system: string;
  personal_holders: number;
  gini: string;
  top1_share: string;
  top10_share: string;
  holder_buckets: string;
}

export async function selectWealthSnapshots(from: string, to: string): Promise<WealthSnapshotRow[]> {
  const r = await sql<Omit<WealthSnapshotRow, 'taken_at'> & { taken_epoch: string }>`
    SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, UNIX_TIMESTAMP(taken_at) AS taken_epoch,
           supply_personal, supply_business, supply_government, supply_system, personal_holders,
           gini, top1_share, top10_share, holder_buckets
    FROM explorer_snapshot_wealth_day
    WHERE day BETWEEN ${from} AND ${to}
    ORDER BY day
  `.execute(db);
  return r.rows.map(({ taken_epoch, ...row }) => ({
    ...row, taken_at: isoFromEpoch(taken_epoch)!, personal_holders: Number(row.personal_holders),
  }));
}
