import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';

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
    SELECT (
      EXISTS (
        SELECT 1 FROM firm
        WHERE firm_id = ${firmId}
          AND proprietor_uuid_bin = ${uuidToBin(playerUuid)}
      )
      OR EXISTS (
        SELECT 1
        FROM firm_employee fe
        JOIN firm_role_permission rp ON rp.role_id = fe.role_id AND rp.deleted_at IS NULL
        WHERE fe.firm_id = ${firmId}
          AND fe.player_uuid_bin = ${uuidToBin(playerUuid)}
          AND fe.left_at IS NULL
          AND rp.permission IN ('FINANCIAL', 'ADMIN')
      )
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
