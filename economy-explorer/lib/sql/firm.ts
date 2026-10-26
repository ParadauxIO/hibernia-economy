import 'server-only';
import { sql, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import { firmsTable, type FirmsTableState } from '@/lib/tables/firms';
import { compileTableState, defineColumnMap, likeContains } from '@/lib/sql/tableState';

export interface FirmRow {
  firm_id: number;
  display_name: string;
  proprietor_uuid: string | null;
  /** The proprietor's current IGN from the player directory; null when not cached. */
  proprietor_name: string | null;
  discord_url: string | null;
  hq_region: string | null;
  default_account_id: number | null;
  archived: number;
  created_at: Date;
  /** Whether the firm is exempt from the weekly corporate balance tax. */
  exempt: boolean;
}

export interface FirmEmployeeRow {
  player_uuid: string | null;
  player_name: string | null;
  role_name: string;
  joined_at: Date | null;
}

export interface FirmRoleRow {
  role_id: number;
  name: string;
  rank_order: number;
  proprietor_like: number;
  default_role: number;
}

export interface FirmRolePermissionRow {
  role_id: number;
  permission: string;
}

export interface FirmAccountRow {
  account_id: number;
  display_name: string | null;
  account_type: string;
  archived: number;
  balance: string;
}

/** Mirrors FirmMapper.findFirmByDisplayName (line 55-58). */
export async function findFirmByDisplayName(displayName: string): Promise<FirmRow | null> {
  const r = await sql<{
    firm_id: number;
    display_name: string;
    proprietorUuid: Buffer | null;
    proprietor_name: string | null;
    discord_url: string | null;
    hq_region: string | null;
    default_account_id: number | null;
    archived: number;
    created_at: Date;
    exemptValue: string | null;
  }>`
    SELECT firm.firm_id, firm.display_name, firm.proprietor_uuid_bin AS proprietorUuid,
           pp.current_name AS proprietor_name,
           firm.discord_url, firm.hq_region, firm.default_account_id,
           firm.is_archived AS archived, firm.created_at,
           (
             SELECT fp.value FROM firm_properties fp
             WHERE fp.firm_id = firm.firm_id
               AND fp.\`key\` = 'balance-tax.exempt'
               AND fp.type = 'BOOLEAN'
               AND fp.deleted_at IS NULL
             LIMIT 1
           ) AS exemptValue
    FROM firm
    LEFT JOIN economy_players pp ON pp.player_uuid_bin = firm.proprietor_uuid_bin
    WHERE firm.display_name = ${displayName}
    ORDER BY firm.is_archived ASC, firm.name_released ASC, firm.firm_id DESC
    LIMIT 1
  `.execute(db);
  const row = r.rows[0];
  if (!row) return null;
  return {
    firm_id: row.firm_id,
    display_name: row.display_name,
    proprietor_uuid: row.proprietorUuid ? binToUuid(row.proprietorUuid) : null,
    proprietor_name: row.proprietor_name,
    discord_url: row.discord_url,
    hq_region: row.hq_region,
    default_account_id: row.default_account_id,
    archived: row.archived,
    created_at: row.created_at,
    // Mirrors FirmPropertyServiceImpl.getBoolean: Boolean.parseBoolean on the
    // stored String.valueOf(boolean) ("true"/"false"), case-insensitive.
    exempt: (row.exemptValue ?? '').toLowerCase() === 'true',
  };
}

/** A firm row for the admin firm tool — core columns + summed live balance. */
export interface AdminFirmRow {
  firm_id: number;
  display_name: string;
  archived: number;
  discord_url: string | null;
  hq_region: string | null;
  total_balance: string;
}

/** Search firms by (partial) name for the admin firm tool, with their summed
 *  live account balance so an admin sees what a disband would sweep. */
export async function searchFirmsByName(q: string, limit = 25): Promise<AdminFirmRow[]> {
  const r = await sql<AdminFirmRow>`
    SELECT f.firm_id, f.display_name, f.is_archived AS archived, f.discord_url, f.hq_region,
           (SELECT COALESCE(SUM(abm.balance), 0.00)
              FROM firm_accounts fa
              LEFT JOIN account_balances_mat abm ON abm.account_id = fa.account_id
             WHERE fa.firm_id = f.firm_id AND fa.removed_at IS NULL) AS total_balance
    FROM firm f
    WHERE f.display_name LIKE ${'%' + q + '%'}
    ORDER BY f.is_archived, f.display_name
    LIMIT ${limit}
  `.execute(db);
  return r.rows;
}

/** Rich firm header for the admin firm report (PAR-224). */
export interface FirmReportHeader {
  firm_id: number;
  display_name: string;
  archived: number;
  discord_url: string | null;
  hq_region: string | null;
  proprietor_uuid: string | null;
  proprietor_name: string | null;
  total_balance: string;
}

export async function getFirmHeader(firmId: number): Promise<FirmReportHeader | null> {
  const r = await sql<{
    firm_id: number; display_name: string; archived: number; discord_url: string | null; hq_region: string | null;
    proprietorUuid: Buffer | null; proprietor_name: string | null; total_balance: string;
  }>`
    SELECT f.firm_id, f.display_name, f.is_archived AS archived, f.discord_url, f.hq_region,
           f.proprietor_uuid_bin AS proprietorUuid, pp.current_name AS proprietor_name,
           (SELECT COALESCE(SUM(abm.balance), 0.00)
              FROM firm_accounts fa
              LEFT JOIN account_balances_mat abm ON abm.account_id = fa.account_id
             WHERE fa.firm_id = f.firm_id AND fa.removed_at IS NULL) AS total_balance
    FROM firm f
    LEFT JOIN economy_players pp ON pp.player_uuid_bin = f.proprietor_uuid_bin
    WHERE f.firm_id = ${firmId}
  `.execute(db);
  const row = r.rows[0];
  if (!row) return null;
  return {
    firm_id: row.firm_id, display_name: row.display_name, archived: row.archived,
    discord_url: row.discord_url, hq_region: row.hq_region,
    proprietor_uuid: row.proprietorUuid ? binToUuid(row.proprietorUuid) : null,
    proprietor_name: row.proprietor_name, total_balance: row.total_balance,
  };
}

/** Look up a firm by id (name + archived state) — used to confirm an admin action. */
export async function findFirmById(firmId: number): Promise<{ firm_id: number; display_name: string; archived: number } | null> {
  const r = await sql<{ firm_id: number; display_name: string; archived: number }>`
    SELECT firm_id, display_name, is_archived AS archived FROM firm WHERE firm_id = ${firmId}
  `.execute(db);
  return r.rows[0] ?? null;
}

/** Mirrors FirmMapper.listFirmEmployees (line 68-75). */
export async function listFirmEmployees(firmId: number): Promise<FirmEmployeeRow[]> {
  const r = await sql<{
    playerUuid: Buffer | null;
    playerName: string | null;
    roleName: string;
    joinedAt: Date | null;
  }>`
    SELECT fe.player_uuid_bin AS playerUuid, fp.current_name AS playerName,
           fr.name AS roleName, fe.joined_at AS joinedAt
    FROM firm_employee fe
    JOIN firm_role fr ON fr.role_id = fe.role_id AND fr.deleted_at IS NULL
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = fe.player_uuid_bin
    WHERE fe.firm_id = ${firmId} AND fe.left_at IS NULL
    ORDER BY fr.rank_order, fe.joined_at
  `.execute(db);
  return r.rows.map((row) => ({
    player_uuid: row.playerUuid ? binToUuid(row.playerUuid) : null,
    player_name: row.playerName,
    role_name: row.roleName,
    joined_at: row.joinedAt,
  }));
}

/** Mirrors FirmMapper.listFirmRoles (line 77-79). */
export async function listFirmRoles(firmId: number): Promise<FirmRoleRow[]> {
  const r = await sql<FirmRoleRow>`
    SELECT role_id, name, rank_order, is_proprietor_like AS proprietor_like, is_default AS default_role
    FROM firm_role WHERE firm_id = ${firmId} AND deleted_at IS NULL ORDER BY rank_order
  `.execute(db);
  return r.rows;
}

/** Mirrors FirmMapper.listFirmRolePermissions (line 88-94). */
export async function listFirmRolePermissions(firmId: number): Promise<FirmRolePermissionRow[]> {
  const r = await sql<FirmRolePermissionRow>`
    SELECT rp.role_id, rp.permission
    FROM firm_role_permission rp
    JOIN firm_role r ON r.role_id = rp.role_id
    WHERE r.firm_id = ${firmId}
      AND r.deleted_at IS NULL
      AND rp.deleted_at IS NULL
  `.execute(db);
  return r.rows;
}

/** Mirrors FirmMapper.listFirmAccounts (line 110-117). */
export async function listFirmAccounts(firmId: number): Promise<FirmAccountRow[]> {
  const r = await sql<FirmAccountRow>`
    SELECT a.account_id, a.display_name, a.account_type, a.is_archived AS archived,
           COALESCE(abm.balance, 0.00) AS balance
    FROM firm_accounts fa
    JOIN accounts a ON a.account_id = fa.account_id
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    WHERE fa.firm_id = ${firmId} AND fa.removed_at IS NULL
    ORDER BY a.account_id
  `.execute(db);
  return r.rows;
}

export interface PlayerFirmMembership {
  firm_id: number;
  display_name: string;
  role_name: string;
  joined_at: Date | null;
}

/** Active firm memberships for a player. Surfaced on account-detail pages. */
export async function listPlayerFirmMemberships(playerUuid: string): Promise<PlayerFirmMembership[]> {
  const r = await sql<{
    firm_id: number;
    display_name: string;
    role_name: string;
    joined_at: Date | null;
  }>`
    SELECT f.firm_id, f.display_name, fr.name AS role_name, fe.joined_at
    FROM firm_employee fe
    JOIN firm f ON f.firm_id = fe.firm_id AND f.is_archived = 0
    JOIN firm_role fr ON fr.role_id = fe.role_id AND fr.deleted_at IS NULL
    WHERE fe.player_uuid_bin = ${uuidToBin(playerUuid)} AND fe.left_at IS NULL
    ORDER BY fr.rank_order, fe.joined_at
  `.execute(db);
  return r.rows;
}

/**
 * Is the given player currently an employee of this firm? Mirrors the
 * canViewFirmInternals access check on the Spring side. firm_employee.left_at
 * IS NULL → currently active. role_id row may be soft-deleted; that's still
 * "current employee" for membership purposes, only the displayed role name
 * filters out deleted roles.
 */
export async function isFirmMember(firmId: number, playerUuid: string): Promise<boolean> {
  const r = await sql<{ c: string | number }>`
    SELECT COUNT(*) AS c FROM firm_employee
    WHERE firm_id = ${firmId} AND player_uuid_bin = ${uuidToBin(playerUuid)} AND left_at IS NULL
  `.execute(db);
  return Number(r.rows[0]?.c ?? 0) > 0;
}

/**
 * Does the player have financial visibility into this firm? True when they are
 * the firm's **proprietor** (the owner is implicitly all-powerful — they are
 * tracked only by firm.proprietor_uuid_bin and are NOT inserted as a
 * firm_employee row, so an employee-only check silently locks owners out), OR a
 * current employee (left_at IS NULL) whose role carries the FINANCIAL or ADMIN
 * permission. Mirrors the plugin's FirmStaffServiceImpl.hasPermission, which
 * short-circuits `if (isProprietor) return true;` before the role lookup.
 * Gates per-firm sales / volume / quantity drilldown — balances stay public,
 * profitability does not. Narrower than {@link isFirmMember}: a plain employee
 * sees the org (roster, roles) but not the books.
 */
export async function hasFirmFinancialAccess(firmId: number, playerUuid: string): Promise<boolean> {
  const r = await sql<{ c: string | number }>`
    SELECT EXISTS (
      SELECT 1 FROM firm_financial_access_web
      WHERE firm_id = ${firmId} AND subject_uuid_bin = ${uuidToBin(playerUuid)}
    ) AS c
  `.execute(db);
  return Number(r.rows[0]?.c ?? 0) > 0;
}

/** The firm that owns this account, if any (via firm_accounts). */
export async function getAccountFirmId(accountId: number): Promise<number | null> {
  const r = await sql<{ firm_id: number }>`
    SELECT firm_id FROM firm_accounts WHERE account_id = ${accountId} LIMIT 1
  `.execute(db);
  return r.rows[0]?.firm_id ?? null;
}

export interface FirmStats {
  businessWealth: string;
  activeFirms: number;
  newThisMonth: number;
  activeEmployees: number;
  avgEmployees: number;
}

/** High-level business KPIs for the firms index header. */
export async function getFirmStats(): Promise<FirmStats> {
  const [wealth, firms, emp] = await Promise.all([
    sql<{ s: string }>`
      SELECT COALESCE(SUM(abm.balance), 0.00) AS s
      FROM account_balances_mat abm
      JOIN accounts a ON a.account_id = abm.account_id
      WHERE a.is_archived = 0 AND a.account_type = 'BUSINESS'
    `.execute(db),
    sql<{ active: string | number; newm: string | number }>`
      SELECT SUM(is_archived = 0) AS active,
             SUM(is_archived = 0 AND created_at >= DATE_FORMAT(NOW(), '%Y-%m-01')) AS newm
      FROM firm
    `.execute(db),
    sql<{ c: string | number }>`
      SELECT COUNT(*) AS c
      FROM firm_employee fe
      JOIN firm f ON f.firm_id = fe.firm_id AND f.is_archived = 0
      WHERE fe.left_at IS NULL
    `.execute(db),
  ]);
  const activeFirms = Number(firms.rows[0]?.active ?? 0);
  const activeEmployees = Number(emp.rows[0]?.c ?? 0);
  return {
    businessWealth: wealth.rows[0]?.s ?? '0.00',
    activeFirms,
    newThisMonth: Number(firms.rows[0]?.newm ?? 0),
    activeEmployees,
    avgEmployees: activeFirms > 0 ? activeEmployees / activeFirms : 0,
  };
}

// ── Firm summaries (PAR-537) ──────────────────────────────────────────────

/** A firm as public pages show it: identity, proprietor (as a role), size. Never a balance. */
export interface FirmSummary {
  firm_id: number;
  display_name: string;
  proprietor_uuid: string | null;
  proprietor_name: string | null;
  hq_region: string | null;
  discord_url: string | null;
  default_account_id: number | null;
  archived: number;
  created_at: Date;
  staff_count: number;
  account_count: number;
}

interface RawFirmSummary {
  firm_id: number;
  display_name: string;
  proprietor_uuid_bin: Buffer | null;
  proprietor_name: string | null;
  hq_region: string | null;
  discord_url: string | null;
  default_account_id: number | null;
  archived: number;
  created_at: Date;
  staff_count: number | string;
  account_count: number | string;
}

const FIRM_SUMMARY_COLUMNS = sql`
  f.firm_id, f.display_name, f.proprietor_uuid_bin, pp.current_name AS proprietor_name, f.hq_region, f.discord_url,
  f.default_account_id, f.is_archived AS archived, f.created_at,
  (SELECT COUNT(*) FROM firm_employee fe WHERE fe.firm_id = f.firm_id AND fe.left_at IS NULL) AS staff_count,
  (SELECT COUNT(*) FROM firm_accounts fc WHERE fc.firm_id = f.firm_id AND fc.removed_at IS NULL) AS account_count`;

function toFirmSummary(r: RawFirmSummary): FirmSummary {
  return {
    firm_id: r.firm_id,
    display_name: r.display_name,
    proprietor_uuid: r.proprietor_uuid_bin ? binToUuid(r.proprietor_uuid_bin) : null,
    proprietor_name: r.proprietor_name,
    hq_region: r.hq_region,
    discord_url: r.discord_url,
    default_account_id: r.default_account_id,
    archived: r.archived,
    created_at: r.created_at,
    staff_count: Number(r.staff_count),
    account_count: Number(r.account_count),
  };
}

/** The firm holding a business account (firm_accounts.uq_account_once), or null. */
export async function findFirmForAccount(accountId: number): Promise<FirmSummary | null> {
  const r = await sql<RawFirmSummary>`
    SELECT ${FIRM_SUMMARY_COLUMNS}
    FROM firm_accounts fa
    JOIN firm f ON f.firm_id = fa.firm_id
    LEFT JOIN economy_players pp ON pp.player_uuid_bin = f.proprietor_uuid_bin
    WHERE fa.account_id = ${accountId} AND fa.removed_at IS NULL
    LIMIT 1
  `.execute(db);
  return r.rows[0] ? toFirmSummary(r.rows[0]) : null;
}

export async function findFirmSummary(firmId: number): Promise<FirmSummary | null> {
  const r = await sql<RawFirmSummary>`
    SELECT ${FIRM_SUMMARY_COLUMNS}
    FROM firm f
    LEFT JOIN economy_players pp ON pp.player_uuid_bin = f.proprietor_uuid_bin
    WHERE f.firm_id = ${firmId}
  `.execute(db);
  return r.rows[0] ? toFirmSummary(r.rows[0]) : null;
}

// ── Firms directory (spec §5.5) ───────────────────────────────────────────

/**
 * The directory reads one row per firm (about 7k) with its account total,
 * account count and staff count from two grouped passes over firm_accounts and
 * firm_employee, then filters and sorts that. Bounded by the firm table; the
 * default views are cached by the service.
 */
const FIRM_DIRECTORY_FROM = sql`(
  SELECT f.firm_id, f.display_name, f.hq_region, f.discord_url, f.default_account_id,
         f.is_archived AS archived, f.created_at, f.proprietor_uuid_bin, pp.current_name AS proprietor_name,
         COALESCE(b.total, 0.00) AS total_balance, COALESCE(b.n, 0) AS account_count, COALESCE(e.n, 0) AS staff_count
  FROM firm f
  LEFT JOIN economy_players pp ON pp.player_uuid_bin = f.proprietor_uuid_bin
  LEFT JOIN (
    SELECT fa.firm_id, SUM(COALESCE(abm.balance, 0.00)) AS total, COUNT(*) AS n
    FROM firm_accounts fa LEFT JOIN account_balances_mat abm ON abm.account_id = fa.account_id
    WHERE fa.removed_at IS NULL GROUP BY fa.firm_id
  ) b ON b.firm_id = f.firm_id
  LEFT JOIN (
    SELECT firm_id, COUNT(*) AS n FROM firm_employee WHERE left_at IS NULL GROUP BY firm_id
  ) e ON e.firm_id = f.firm_id
) x`;

export const firmsColumnMap = defineColumnMap({
  table: firmsTable,
  filters: {
    balance: { column: sql`x.total_balance` },
    staff: { column: sql`x.staff_count` },
    hq: { column: sql`x.hq_region` },
    created: { column: sql`x.created_at` },
    archived: { column: sql`x.archived` },
  },
  sorts: { name: sql`x.display_name`, balance: sql`x.total_balance`, staff: sql`x.staff_count`, created: sql`x.created_at` },
  tiebreak: { column: sql`x.firm_id`, dir: 'desc' },
  search: (q) => {
    const like = likeContains(q.trim());
    return sql<SqlBool>`(x.display_name LIKE ${like} OR x.hq_region LIKE ${like})`;
  },
});

export interface FirmDirectoryRow {
  firm_id: number;
  display_name: string;
  hq_region: string | null;
  discord_url: string | null;
  default_account_id: number | null;
  archived: number;
  created_at: Date;
  proprietor_uuid: string | null;
  proprietor_name: string | null;
  total_balance: string;
  account_count: number;
  staff_count: number;
}

function firmDirectoryWhere(state: FirmsTableState, now: Date) {
  const c = compileTableState(state, firmsColumnMap, now);
  const conds = [...c.conditions];
  if (!state.filters.some((f) => f.field === 'archived')) conds.unshift(sql<SqlBool>`x.archived = 0`);
  return { where: conds.length ? sql`WHERE ${sql.join(conds, sql` AND `)}` : sql``, orderBy: c.orderBy };
}

export async function listFirmDirectory(state: FirmsTableState, limit: number, offset: number, now: Date = new Date()): Promise<FirmDirectoryRow[]> {
  const { where, orderBy } = firmDirectoryWhere(state, now);
  const r = await sql<Omit<FirmDirectoryRow, 'proprietor_uuid' | 'account_count' | 'staff_count'> & { proprietor_uuid_bin: Buffer | null; account_count: number | string; staff_count: number | string }>`
    SELECT x.* FROM ${FIRM_DIRECTORY_FROM} ${where} ${orderBy} LIMIT ${limit} OFFSET ${offset}
  `.execute(db);
  return r.rows.map(({ proprietor_uuid_bin, ...row }) => ({
    ...row,
    proprietor_uuid: proprietor_uuid_bin ? binToUuid(proprietor_uuid_bin) : null,
    account_count: Number(row.account_count),
    staff_count: Number(row.staff_count),
  }));
}

export async function countFirmDirectory(state: FirmsTableState, now: Date = new Date()): Promise<number> {
  const { where } = firmDirectoryWhere(state, now);
  const r = await sql<{ c: number | string }>`SELECT COUNT(*) AS c FROM ${FIRM_DIRECTORY_FROM} ${where}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

// ── Firm market and financials ────────────────────────────────────────────

export interface FirmItemRow {
  item_key: string;
  item_name: string | null;
  material: string;
  item_custom: number;
  trades: number;
  quantity: number;
  /** What customers paid the firm's shops (BUY: the customer bought), and what the shops paid customers (SELL). */
  sold_volume: string;
  bought_volume: string;
}

/**
 * ChestShop trades at a firm's shops by item over [from, to), from
 * idx_cs_firm (shop_firm_id, occurred_at): bounded by the firm and the window.
 */
export async function listFirmItems(firmId: number, from: Date, to: Date, limit: number): Promise<FirmItemRow[]> {
  const r = await sql<{ item_key: string; item_name: string; material: string; item_custom: number; trades: number | string; quantity: number | string; sold_volume: string; bought_volume: string }>`
    SELECT s.item_key, MAX(s.item_name) AS item_name, MAX(s.material) AS material, MAX(s.item_custom) AS item_custom,
           COUNT(*) AS trades, SUM(s.quantity) AS quantity,
           COALESCE(SUM(CASE WHEN s.direction = 'BUY' THEN s.total_price END), 0.00) AS sold_volume,
           COALESCE(SUM(CASE WHEN s.direction = 'SELL' THEN s.total_price END), 0.00) AS bought_volume
    FROM chestshop_sale s
    WHERE s.shop_firm_id = ${firmId} AND s.occurred_at >= ${from} AND s.occurred_at < ${to}
    GROUP BY s.item_key
    ORDER BY COUNT(*) DESC, s.item_key
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({ ...row, trades: Number(row.trades), quantity: Number(row.quantity) }));
}

export interface FirmMonthFlowRow {
  month: string;
  plugin_key: string;
  tax_kind: string;
  counterparty_type: string;
  is_internal: number;
  inflow: string;
  outflow: string;
}

/**
 * A set of accounts' flows by calendar month and the categorisation inputs,
 * from explorer_rollup_account_day (PK prefix account_id, day). Days are
 * 'YYYY-MM-DD', inclusive. Callers check rollup coverage first.
 */
export async function selectMonthlyFlows(accountIds: readonly number[], fromDay: string, toDay: string): Promise<FirmMonthFlowRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<FirmMonthFlowRow & { is_internal: number | string }>`
    SELECT DATE_FORMAT(day, '%Y-%m-01') AS month, plugin_key, tax_kind, counterparty_type, is_internal,
           SUM(inflow) AS inflow, SUM(outflow) AS outflow
    FROM explorer_rollup_account_day
    WHERE account_id IN (${sql.join(accountIds)}) AND day BETWEEN ${fromDay} AND ${toDay}
    GROUP BY month, plugin_key, tax_kind, counterparty_type, is_internal
    ORDER BY month
  `.execute(db);
  return r.rows.map((row) => ({ ...row, is_internal: Number(row.is_internal) }));
}
