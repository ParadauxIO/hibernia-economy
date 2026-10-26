import 'server-only';
import { sql, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import { playersTable, type PlayersTableState } from '@/lib/tables/players';
import { compileTableState, defineColumnMap } from '@/lib/sql/tableState';

// The public player directory and profile (spec §7.2). A profile answers "who
// is this?": identity, personal accounts, firm roles and shops. Nothing here
// sums money across accounts, and firm balances are never read (§3 rule 5).
//
// Index use: name lookups go through uq_economy_players_name (name_lower),
// accounts through idx_accounts_owner, firm roles through idx_emp_player and
// idx_firm_proprietor, shops through idx_shop_owner. The directory's date
// filters and sorts use V45's first_seen / last_seen indexes.

export interface PlayerRow {
  uuid: string;
  name: string;
  first_seen: Date;
  last_seen: Date;
}

interface RawPlayer {
  player_uuid_bin: Buffer;
  current_name: string;
  first_seen: Date;
  last_seen: Date;
}

function toPlayer(r: RawPlayer): PlayerRow {
  return { uuid: binToUuid(r.player_uuid_bin), name: r.current_name, first_seen: r.first_seen, last_seen: r.last_seen };
}

/** Case-insensitive exact name lookup on the unique name_lower index. */
export async function findPlayerByName(name: string): Promise<PlayerRow | null> {
  const r = await sql<RawPlayer>`
    SELECT player_uuid_bin, current_name, first_seen, last_seen
    FROM economy_players WHERE name_lower = LOWER(${name})
    LIMIT 1
  `.execute(db);
  return r.rows[0] ? toPlayer(r.rows[0]) : null;
}

export async function findPlayerByUuid(uuid: string): Promise<PlayerRow | null> {
  const r = await sql<RawPlayer>`
    SELECT player_uuid_bin, current_name, first_seen, last_seen
    FROM economy_players WHERE player_uuid_bin = ${uuidToBin(uuid)}
  `.execute(db);
  return r.rows[0] ? toPlayer(r.rows[0]) : null;
}

export interface OwnedAccountRow {
  account_id: number;
  account_type: string;
  display_name: string | null;
  archived: number;
  created_at: Date;
  balance: string;
}

/**
 * Accounts of one type owned by a player or government entity, open ones
 * first, newest first. `excludeId` drops the account being viewed.
 */
export async function listOwnedAccounts(ownerUuid: string, type: string, opts: { excludeId?: number; limit?: number; includeArchived?: boolean } = {}): Promise<OwnedAccountRow[]> {
  const r = await sql<OwnedAccountRow>`
    SELECT a.account_id, a.account_type, a.display_name, a.is_archived AS archived, a.created_at,
           COALESCE(abm.balance, 0.00) AS balance
    FROM accounts a
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    WHERE a.owner_uuid_bin = ${uuidToBin(ownerUuid)} AND a.account_type = ${type}
      ${opts.excludeId !== undefined ? sql`AND a.account_id <> ${opts.excludeId}` : sql``}
      ${opts.includeArchived ? sql`` : sql`AND a.is_archived = 0`}
    ORDER BY a.is_archived, a.account_id DESC
    LIMIT ${opts.limit ?? 20}
  `.execute(db);
  return r.rows;
}

export interface PlayerFirmRole {
  firm_id: number;
  firm_name: string;
  /** True for the firm's proprietor (who may have no employee row at all). */
  proprietor: boolean;
  /** The employee role's name, when they hold one. */
  role_name: string | null;
  joined_at: Date | null;
}

/**
 * Active firms a player is part of: those they are proprietor of, and those
 * they are a current employee of, with the role. Disbanded firms are left out.
 * No balances: a firm's money is not its people's.
 */
export async function listPlayerFirmRoles(uuid: string): Promise<PlayerFirmRole[]> {
  const bin = uuidToBin(uuid);
  const r = await sql<{ firm_id: number; firm_name: string; proprietor: number | string; role_name: string | null; joined_at: Date | null; rank_order: number | null }>`
    SELECT f.firm_id, f.display_name AS firm_name,
           f.proprietor_uuid_bin = ${bin} AS proprietor,
           fr.name AS role_name, fe.joined_at, fr.rank_order
    FROM firm f
    LEFT JOIN firm_employee fe ON fe.firm_id = f.firm_id AND fe.player_uuid_bin = ${bin} AND fe.left_at IS NULL
    LEFT JOIN firm_role fr ON fr.role_id = fe.role_id AND fr.deleted_at IS NULL
    WHERE f.is_archived = 0
      AND f.firm_id IN (
        SELECT firm_id FROM firm WHERE proprietor_uuid_bin = ${bin}
        UNION
        SELECT firm_id FROM firm_employee WHERE player_uuid_bin = ${bin} AND left_at IS NULL
      )
    ORDER BY proprietor DESC, fr.rank_order, f.display_name
  `.execute(db);
  return r.rows.map((row) => ({
    firm_id: row.firm_id,
    firm_name: row.firm_name,
    proprietor: Number(row.proprietor) === 1,
    role_name: row.role_name,
    joined_at: row.joined_at,
  }));
}

export interface PlayerShopRow {
  shop_id: number;
  item_key: string;
  item_name: string;
  material: string;
  item_custom: number;
  world: string;
  sign_x: number;
  sign_y: number;
  sign_z: number;
  buy_price: string | null;
  sell_price: string | null;
  batch_qty: number;
  current_stock: number | null;
  admin_shop: number;
  last_seen: Date;
}

/**
 * A player's own active ChestShop shops (paid into their personal account),
 * as the public shop finder lists them. Firm shops belong to the firm and
 * appear on the firm's pages instead.
 */
export async function listPlayerShops(uuid: string, limit: number): Promise<{ rows: PlayerShopRow[]; total: number }> {
  const bin = uuidToBin(uuid);
  const [rows, count] = await Promise.all([
    sql<PlayerShopRow>`
      SELECT s.shop_id, s.item_key, s.item_name, s.material, s.item_custom, s.world, s.sign_x, s.sign_y, s.sign_z,
             s.buy_price, s.sell_price, s.batch_qty, s.current_stock, s.is_admin_shop AS admin_shop, s.last_seen
      FROM chestshop_shop s
      WHERE s.shop_owner_uuid_bin = ${bin} AND s.active = 1 AND s.visible = 1
        AND (s.shop_account_type = 'PERSONAL' OR s.shop_account_type IS NULL)
      ORDER BY s.item_name, s.shop_id
      LIMIT ${limit}
    `.execute(db),
    sql<{ c: number | string }>`
      SELECT COUNT(*) AS c FROM chestshop_shop s
      WHERE s.shop_owner_uuid_bin = ${bin} AND s.active = 1 AND s.visible = 1
        AND (s.shop_account_type = 'PERSONAL' OR s.shop_account_type IS NULL)
    `.execute(db),
  ]);
  return { rows: rows.rows, total: Number(count.rows[0]?.c ?? 0) };
}

// ── Directory ─────────────────────────────────────────────────────────────

const HAS_FIRM = sql<SqlBool>`(EXISTS (SELECT 1 FROM firm_employee fe JOIN firm f ON f.firm_id = fe.firm_id AND f.is_archived = 0
    WHERE fe.player_uuid_bin = p.player_uuid_bin AND fe.left_at IS NULL)
  OR EXISTS (SELECT 1 FROM firm f WHERE f.proprietor_uuid_bin = p.player_uuid_bin AND f.is_archived = 0))`;

/** Escape LIKE wildcards in a prefix. */
function likePrefix(text: string): string {
  return `${text.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
}

/**
 * Directory filters. Search is a name prefix on name_lower (a range scan of
 * the unique index, never a leading wildcard); first and last seen use V45's
 * indexes; "has a firm" is two indexed EXISTS probes per candidate row.
 */
export const playersColumnMap = defineColumnMap({
  table: playersTable,
  filters: {
    firstSeen: { column: sql`p.first_seen` },
    lastSeen: { column: sql`p.last_seen` },
    hasFirm: { compile: (f) => (f.values[0] === 'true' ? HAS_FIRM : sql<SqlBool>`NOT ${HAS_FIRM}`) },
  },
  sorts: { name: sql`p.name_lower`, firstSeen: sql`p.first_seen`, lastSeen: sql`p.last_seen` },
  tiebreak: { column: sql`p.player_uuid_bin`, dir: 'desc' },
  search: (q) => {
    const t = q.trim().toLowerCase();
    if (!t || t.length > 32) return null;
    return sql<SqlBool>`p.name_lower LIKE ${likePrefix(t)}`;
  },
});

export interface DirectoryPlayerRow extends PlayerRow {
  firm_count: number;
}

export async function listPlayers(state: PlayersTableState, limit: number, offset: number, now: Date = new Date()): Promise<DirectoryPlayerRow[]> {
  const c = compileTableState(state, playersColumnMap, now);
  const r = await sql<RawPlayer & { firm_count: number | string }>`
    SELECT p.player_uuid_bin, p.current_name, p.first_seen, p.last_seen,
           (SELECT COUNT(DISTINCT fe.firm_id) FROM firm_employee fe JOIN firm f ON f.firm_id = fe.firm_id AND f.is_archived = 0
             WHERE fe.player_uuid_bin = p.player_uuid_bin AND fe.left_at IS NULL)
           + (SELECT COUNT(*) FROM firm f WHERE f.proprietor_uuid_bin = p.player_uuid_bin AND f.is_archived = 0
             AND NOT EXISTS (SELECT 1 FROM firm_employee fe WHERE fe.firm_id = f.firm_id AND fe.player_uuid_bin = p.player_uuid_bin AND fe.left_at IS NULL)) AS firm_count
    FROM economy_players p
    ${c.where}
    ${c.orderBy}
    LIMIT ${limit} OFFSET ${offset}
  `.execute(db);
  return r.rows.map((row) => ({ ...toPlayer(row), firm_count: Number(row.firm_count) }));
}

export async function countPlayers(state: PlayersTableState, now: Date = new Date()): Promise<number> {
  const c = compileTableState(state, playersColumnMap, now);
  const r = await sql<{ c: number | string }>`SELECT COUNT(*) AS c FROM economy_players p ${c.where}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

