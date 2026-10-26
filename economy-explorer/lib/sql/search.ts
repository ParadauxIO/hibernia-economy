import 'server-only';
import { sql, type RawBuilder, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';

// Search queries (spec §9.1). Two kinds, kept apart on purpose:
//
//  * Prefix lookups for the typeahead and the ⌘K palette. Each is a range scan
//    on a name index and never a leading wildcard:
//      players  → uq_economy_players_name (name_lower, V17)
//      accounts → idx_accounts_type_name (account_type, display_name, V7). The
//                 index leads with the type, so the query names all four types
//                 to get four range scans instead of a table scan.
//      firms    → idx_firm_display_name (display_name, V33)
//      items    → idx_cs_item (item_key, occurred_at, V6) read as a loose index
//                 scan: one seek per distinct key, MAX(occurred_at) from the index.
//  * Contains matches for the /search page only (run on Enter, never per
//    keystroke). Each is bounded by a small set rather than the ledger:
//      accounts → BUSINESS, GOVERNMENT and SYSTEM names, an index-only scan of
//                 idx_accounts_type_name (about 7.7k entries on production).
//                 Personal accounts are found through their player instead:
//                 their display_name is usually the owner's UUID.
//      firms    → idx_firm_display_name (about 7.3k entries).
//      items    → the distinct keys of idx_cs_item (about 5.6k groups).
//    Player names stay prefix-only: economy_players has 140k+ rows.
//
// Ranking is in SQL so LIMIT keeps the best rows: exact match, then prefix,
// then a per-type tiebreak (recent login for players, last trade for items,
// balance for accounts, shorter names for firms).

export type SearchKind = 'account' | 'firm' | 'player';
export type SearchHitKind = SearchKind | 'item';
export type SearchMatch = 'exact' | 'prefix' | 'contains';

/**
 * One typeahead or search result. The original fields are unchanged (the header
 * search and the command palette consume them); `href` and `match` are optional
 * additions.
 */
export interface SearchResult {
  kind: SearchKind;
  label: string;
  secondary: string | null;
  account_id: number | null;
  account_type: string | null;
  balance: string | null;
  firm_id: number | null;
  firm_name: string | null;
  player_uuid: string | null;
  player_name: string | null;
  /** Where the result leads: the account, the firm, or a player's personal account. */
  href?: string;
  /** How the text matched, best first. */
  match?: SearchMatch;
}

/** An item result. Separate from SearchResult so existing consumers keep a closed `kind`. */
export interface ItemSearchResult {
  kind: 'item';
  label: string;
  secondary: string | null;
  item_key: string;
  last_traded_at: Date | null;
  href: string;
  match: SearchMatch;
}

export type SearchHit = SearchResult | ItemSearchResult;

/** Escape LIKE wildcards so user text is literal. */
export function likeEscape(text: string): string {
  return text.replace(/[\\%_]/g, (c) => `\\${c}`);
}

function matchOf(name: string, q: string): SearchMatch {
  const n = name.toLowerCase();
  const t = q.toLowerCase();
  if (n === t) return 'exact';
  return n.startsWith(t) ? 'prefix' : 'contains';
}

/** Personal display names that are just the owner's UUID, and V17's 32-hex name placeholders. */
const UUID_NAME_SQL = (col: RawBuilder<unknown>) => sql<SqlBool>`${col} REGEXP '^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-'`;
const HEX_PLACEHOLDER_SQL = sql<SqlBool>`p.current_name REGEXP '^[0-9A-Fa-f]{32}$'`;

// ── Players (prefix only) ────────────────────────────────────────────────

interface RawPlayer {
  player_uuid_bin: Buffer;
  current_name: string;
  first_seen: Date | null;
  last_login_epoch: number | string | null;
  account_id: number | null;
  balance: string | null;
}

function toPlayer(r: RawPlayer, q: string): SearchResult {
  const uuid = binToUuid(r.player_uuid_bin);
  return {
    kind: 'player',
    label: r.current_name,
    secondary: r.first_seen ? r.first_seen.toISOString().slice(0, 10) : null,
    account_id: null,
    account_type: null,
    balance: null,
    firm_id: null,
    firm_name: null,
    player_uuid: uuid,
    player_name: r.current_name,
    ...(r.account_id !== null ? { href: `/accounts/${r.account_id}` } : {}),
    match: matchOf(r.current_name, q),
  };
}

/**
 * Players whose name starts with `prefix`: exact first, then the most recently
 * logged in. A range scan on uq_economy_players_name; the personal account is
 * looked up (idx_accounts_owner) only for the rows returned.
 */
export async function findPlayersByPrefix(prefix: string, limit: number): Promise<SearchResult[]> {
  const t = prefix.toLowerCase();
  const r = await sql<RawPlayer>`
    SELECT page.player_uuid_bin, page.current_name, page.first_seen, page.last_login_epoch,
           (SELECT a.account_id FROM accounts a
             WHERE a.owner_uuid_bin = page.player_uuid_bin AND a.account_type = 'PERSONAL' AND a.is_archived = 0
             ORDER BY a.account_id LIMIT 1) AS account_id,
           NULL AS balance
    FROM (
      SELECT p.player_uuid_bin, p.current_name, p.first_seen, p.last_login_epoch, p.name_lower
      FROM economy_players p
      WHERE p.name_lower LIKE ${likeEscape(t) + '%'} AND NOT ${HEX_PLACEHOLDER_SQL}
      ORDER BY (p.name_lower = ${t}) DESC, p.last_login_epoch IS NULL, p.last_login_epoch DESC, p.name_lower
      LIMIT ${limit}
    ) page
    ORDER BY (page.name_lower = ${t}) DESC, page.last_login_epoch IS NULL, page.last_login_epoch DESC, page.name_lower
  `.execute(db);
  return r.rows.map((row) => toPlayer(row, prefix));
}

/** The player with exactly this UUID, for a pasted UUID. */
export async function findPlayerByUuid(uuid: string): Promise<SearchResult[]> {
  const r = await sql<RawPlayer>`
    SELECT p.player_uuid_bin, p.current_name, p.first_seen, p.last_login_epoch,
           (SELECT a.account_id FROM accounts a
             WHERE a.owner_uuid_bin = p.player_uuid_bin AND a.account_type = 'PERSONAL' AND a.is_archived = 0
             ORDER BY a.account_id LIMIT 1) AS account_id,
           NULL AS balance
    FROM economy_players p WHERE p.player_uuid_bin = ${uuidToBin(uuid)}
  `.execute(db);
  return r.rows.map((row) => ({ ...toPlayer(row, uuid), match: 'exact' as const }));
}

export async function countPlayersByPrefix(prefix: string): Promise<number> {
  const r = await sql<{ c: number | string }>`
    SELECT COUNT(*) AS c FROM economy_players p
    WHERE p.name_lower LIKE ${likeEscape(prefix.toLowerCase()) + '%'} AND NOT ${HEX_PLACEHOLDER_SQL}
  `.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

// ── Accounts ──────────────────────────────────────────────────────────────

const ALL_TYPES = sql`('PERSONAL', 'BUSINESS', 'GOVERNMENT', 'SYSTEM')`;
const NAMED_TYPES = sql`('BUSINESS', 'GOVERNMENT', 'SYSTEM')`;

interface RawAccount {
  account_id: number;
  account_type: string;
  display_name: string | null;
  owner_name: string | null;
  balance: string;
}

function toAccount(r: RawAccount, q: string): SearchResult {
  const label = r.display_name && !/^[0-9a-f]{8}-?[0-9a-f]{4}-/i.test(r.display_name)
    ? r.display_name
    : r.owner_name ?? `Account #${r.account_id}`;
  return {
    kind: 'account',
    label,
    // As before: the account type, or the account's own name when it differs from the owner's.
    secondary: r.owner_name && r.display_name && r.owner_name !== r.display_name && label === r.owner_name ? r.display_name : r.account_type,
    account_id: r.account_id,
    account_type: r.account_type,
    balance: r.balance,
    firm_id: null,
    firm_name: null,
    player_uuid: null,
    player_name: null,
    href: `/accounts/${r.account_id}`,
    match: matchOf(label, q),
  };
}

/** Display columns for a page of account ids, in the page's order. */
function accountColumns(ids: RawBuilder<unknown>, order: RawBuilder<unknown>) {
  return sql<RawAccount>`
    SELECT a.account_id, a.account_type, a.display_name, fp.current_name AS owner_name,
           COALESCE(abm.balance, 0.00) AS balance
    FROM (${ids}) page
    JOIN accounts a ON a.account_id = page.account_id
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    ${order}
  `;
}

/**
 * Active accounts whose own name starts with `prefix`. Personal accounts named
 * after their owner's UUID are skipped: the player result covers them.
 */
export async function findAccountsByPrefix(prefix: string, limit: number): Promise<SearchResult[]> {
  const ids = sql`
    SELECT a.account_id, (a.display_name = ${prefix}) AS exact, CHAR_LENGTH(a.display_name) AS len, a.display_name AS name
    FROM accounts a
    WHERE a.account_type IN ${ALL_TYPES} AND a.display_name LIKE ${likeEscape(prefix) + '%'}
      AND a.is_archived = 0 AND NOT ${UUID_NAME_SQL(sql`a.display_name`)}
    ORDER BY exact DESC, len, name, a.account_id
    LIMIT ${limit}
  `;
  const r = await accountColumns(ids, sql`ORDER BY page.exact DESC, page.len, page.name, page.account_id`).execute(db);
  return r.rows.map((row) => toAccount(row, prefix));
}

/** An account by number (`123` or `#123`). Archived accounts included: a number is exact. */
export async function findAccountById(accountId: number): Promise<SearchResult[]> {
  const r = await accountColumns(sql`SELECT ${accountId} AS account_id`, sql``).execute(db);
  return r.rows.map((row) => ({ ...toAccount(row, String(accountId)), match: 'exact' as const }));
}

function namedAccountsContaining(q: string) {
  return sql<SqlBool>`a.account_type IN ${NAMED_TYPES} AND a.display_name LIKE ${'%' + likeEscape(q) + '%'} AND a.is_archived = 0`;
}

/** /search only: active business, government and system accounts whose name contains `q`. */
export async function searchAccountsContaining(q: string, limit: number, offset: number): Promise<SearchResult[]> {
  const ids = sql`
    SELECT a.account_id, (a.display_name = ${q}) AS exact, (a.display_name LIKE ${likeEscape(q) + '%'}) AS prefix,
           COALESCE(b.balance, 0.00) AS balance
    FROM accounts a
    LEFT JOIN account_balances_mat b ON b.account_id = a.account_id
    WHERE ${namedAccountsContaining(q)}
    ORDER BY exact DESC, prefix DESC, balance DESC, a.account_id
    LIMIT ${limit} OFFSET ${offset}
  `;
  const r = await accountColumns(ids, sql`ORDER BY page.exact DESC, page.prefix DESC, page.balance DESC, page.account_id`).execute(db);
  return r.rows.map((row) => toAccount(row, q));
}

export async function countAccountsContaining(q: string): Promise<number> {
  const r = await sql<{ c: number | string }>`SELECT COUNT(*) AS c FROM accounts a WHERE ${namedAccountsContaining(q)}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

// ── Firms ─────────────────────────────────────────────────────────────────

interface RawFirm { firm_id: number; display_name: string; hq_region: string | null; balance: string | null }

function toFirm(r: RawFirm, q: string): SearchResult {
  return {
    kind: 'firm',
    label: r.display_name,
    secondary: r.hq_region ?? '—',
    account_id: null,
    account_type: null,
    balance: r.balance,
    firm_id: r.firm_id,
    firm_name: r.display_name,
    player_uuid: null,
    player_name: null,
    href: `/firms/${encodeURIComponent(r.display_name)}`,
    match: matchOf(r.display_name, q),
  };
}

function firmColumns(ids: RawBuilder<unknown>, order: RawBuilder<unknown>) {
  return sql<RawFirm>`
    SELECT f.firm_id, f.display_name, f.hq_region, abm.balance
    FROM (${ids}) page
    JOIN firm f ON f.firm_id = page.firm_id
    LEFT JOIN account_balances_mat abm ON abm.account_id = f.default_account_id
    ${order}
  `;
}

/** Active firms whose name starts with `prefix` (idx_firm_display_name range). */
export async function findFirmsByPrefix(prefix: string, limit: number): Promise<SearchResult[]> {
  const ids = sql`
    SELECT f.firm_id, (f.display_name = ${prefix}) AS exact, CHAR_LENGTH(f.display_name) AS len, f.display_name AS name
    FROM firm f
    WHERE f.display_name LIKE ${likeEscape(prefix) + '%'} AND f.is_archived = 0
    ORDER BY exact DESC, len, name
    LIMIT ${limit}
  `;
  const r = await firmColumns(ids, sql`ORDER BY page.exact DESC, page.len, page.name`).execute(db);
  return r.rows.map((row) => toFirm(row, prefix));
}

const firmsContaining = (q: string) => sql<SqlBool>`f.display_name LIKE ${'%' + likeEscape(q) + '%'} AND f.is_archived = 0`;

/** /search only: active firms whose name contains `q`. */
export async function searchFirmsContaining(q: string, limit: number, offset: number): Promise<SearchResult[]> {
  const ids = sql`
    SELECT f.firm_id, (f.display_name = ${q}) AS exact, (f.display_name LIKE ${likeEscape(q) + '%'}) AS prefix, f.display_name AS name
    FROM firm f WHERE ${firmsContaining(q)}
    ORDER BY exact DESC, prefix DESC, name
    LIMIT ${limit} OFFSET ${offset}
  `;
  const r = await firmColumns(ids, sql`ORDER BY page.exact DESC, page.prefix DESC, page.name`).execute(db);
  return r.rows.map((row) => toFirm(row, q));
}

export async function countFirmsContaining(q: string): Promise<number> {
  const r = await sql<{ c: number | string }>`SELECT COUNT(*) AS c FROM firm f WHERE ${firmsContaining(q)}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

// ── Items ─────────────────────────────────────────────────────────────────

interface RawItem { item_key: string; last_at: Date | null; item_name: string | null }

function toItem(r: RawItem, q: string): ItemSearchResult {
  const label = r.item_name && r.item_name.trim() ? r.item_name : r.item_key;
  return {
    kind: 'item',
    label,
    secondary: label === r.item_key ? null : r.item_key,
    item_key: r.item_key,
    last_traded_at: r.last_at,
    href: `/chestshop/items/${encodeURIComponent(r.item_key)}`,
    match: matchOf(r.item_key, q),
  };
}

/** Distinct traded item keys matching `pattern`, best first, with the latest name for each. */
async function itemsMatching(pattern: string, exact: string, limit: number, offset: number): Promise<RawItem[]> {
  const r = await sql<RawItem>`
    SELECT k.item_key, k.last_at,
           (SELECT s.item_name FROM chestshop_sale s WHERE s.item_key = k.item_key
             ORDER BY s.occurred_at DESC, s.sale_id DESC LIMIT 1) AS item_name
    FROM (
      SELECT g.item_key, g.last_at FROM (
        SELECT cs.item_key, MAX(cs.occurred_at) AS last_at
        FROM chestshop_sale cs WHERE cs.item_key LIKE ${pattern}
        GROUP BY cs.item_key
      ) g
      ORDER BY (g.item_key = ${exact}) DESC, g.last_at DESC, g.item_key
      LIMIT ${limit} OFFSET ${offset}
    ) k
    ORDER BY (k.item_key = ${exact}) DESC, k.last_at DESC, k.item_key
  `.execute(db);
  return r.rows;
}

/** Traded items whose key starts with `prefix`: exact first, then the most recently traded. */
export async function findItemsByPrefix(prefix: string, limit: number): Promise<ItemSearchResult[]> {
  return (await itemsMatching(likeEscape(prefix) + '%', prefix, limit, 0)).map((r) => toItem(r, prefix));
}

/** /search only: traded items whose key contains `q`. */
export async function searchItemsContaining(q: string, limit: number, offset: number): Promise<ItemSearchResult[]> {
  return (await itemsMatching('%' + likeEscape(q) + '%', q, limit, offset)).map((r) => toItem(r, q));
}

export async function countItemsContaining(q: string): Promise<number> {
  const r = await sql<{ c: number | string }>`
    SELECT COUNT(*) AS c FROM (
      SELECT cs.item_key FROM chestshop_sale cs WHERE cs.item_key LIKE ${'%' + likeEscape(q) + '%'} GROUP BY cs.item_key
    ) k
  `.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

/** Item names for keys already on a URL (one idx_cs_item seek per key). */
export async function findItemNames(keys: readonly string[]): Promise<Map<string, string>> {
  if (keys.length === 0) return new Map();
  const r = await sql<{ item_key: string; item_name: string | null }>`
    SELECT k.item_key,
           (SELECT s.item_name FROM chestshop_sale s WHERE s.item_key = k.item_key
             ORDER BY s.occurred_at DESC, s.sale_id DESC LIMIT 1) AS item_name
    FROM (${sql.join(keys.map((k) => sql`SELECT ${k} AS item_key`), sql` UNION ALL `)}) k
  `.execute(db);
  const out = new Map<string, string>();
  for (const row of r.rows) if (row.item_name) out.set(row.item_key, row.item_name);
  return out;
}

// ── Player names ↔ UUIDs, for entity chips ───────────────────────────────

/** UUIDs for exact player names (uq_economy_players_name seeks), keyed by lower-case name. */
export async function findPlayerUuidsByName(names: readonly string[]): Promise<Map<string, string>> {
  if (names.length === 0) return new Map();
  const r = await sql<{ name_lower: string; player_uuid_bin: Buffer }>`
    SELECT p.name_lower, p.player_uuid_bin FROM economy_players p
    WHERE p.name_lower IN (${sql.join(names.map((n) => n.toLowerCase()))})
  `.execute(db);
  return new Map(r.rows.map((row) => [row.name_lower, binToUuid(row.player_uuid_bin)]));
}

/** Current names for player UUIDs (primary-key seeks). */
export async function findPlayerNames(uuids: readonly string[]): Promise<Map<string, string>> {
  if (uuids.length === 0) return new Map();
  const r = await sql<{ player_uuid_bin: Buffer; current_name: string }>`
    SELECT p.player_uuid_bin, p.current_name FROM economy_players p
    WHERE p.player_uuid_bin IN (${sql.join(uuids.map(uuidToBin))})
  `.execute(db);
  return new Map(r.rows.map((row) => [binToUuid(row.player_uuid_bin), row.current_name]));
}

/** Labels for account ids (primary-key seeks). */
export async function findAccountLabels(ids: readonly number[]): Promise<Map<number, string>> {
  if (ids.length === 0) return new Map();
  const r = await accountColumns(sql`SELECT a.account_id FROM accounts a WHERE a.account_id IN (${sql.join(ids)})`, sql``).execute(db);
  return new Map(r.rows.map((row) => [row.account_id, `${toAccount(row, '').label} #${row.account_id}`]));
}
