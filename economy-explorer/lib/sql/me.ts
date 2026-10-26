import 'server-only';
import { sql, type RawBuilder, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import { PLUGIN_CATEGORIES, type Category } from '@/lib/categories';
import type { ExplorerAccountRow } from '@/lib/sql/ledger';
import { defineColumnMap } from '@/lib/sql/tableState';
import { meTransactionsTable } from '@/lib/tables/me';

// Reads behind the player's own workspace (/me, spec §8). Pure queries: access
// decisions, grouping, categories and money arithmetic live in
// lib/services/me*.ts. Every query is bounded by an index on the player, their
// account ids, a txn_id range or a rollup day/month range.

type Condition = RawBuilder<SqlBool>;

const uuidOrNull = (bin: Buffer | null | undefined): string | null => (bin ? binToUuid(bin) : null);

// ── Accounts and relationships ────────────────────────────────────────────

/** Mirrors LedgerExplorerMapper.findAccountsForPlayer. Used by webhooks. */
export async function findAccountsForPlayer(playerUuid: string): Promise<ExplorerAccountRow[]> {
  const bin = uuidToBin(playerUuid);
  const r = await sql<{
    account_id: number;
    account_type: string;
    display_name: string | null;
    owner_uuid_bin: Buffer | null;
    owner_name: string | null;
    archived: number;
    requires_authorization: number;
    allow_overdraft: number;
    credit_limit: string;
    created_at: Date;
    balance: string;
  }>`
    SELECT a.account_id, a.account_type, a.display_name, a.owner_uuid_bin, fp.current_name AS owner_name,
           a.is_archived AS archived, a.requires_authorization, a.allow_overdraft,
           a.credit_limit, a.created_at, COALESCE(abm.balance, 0.00) AS balance
    FROM accounts a
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE a.owner_uuid_bin = ${bin}
       -- Same direct, LP and firm-finance relationships as Auth.canViewAccount.
       OR a.account_id IN (SELECT account_id FROM account_read_access_web
                           WHERE subject_uuid_bin = ${bin})
    ORDER BY balance DESC
  `.execute(db);
  return r.rows.map((row) => ({
    account_id: row.account_id,
    account_type: row.account_type,
    display_name: row.display_name,
    owner_uuid: uuidOrNull(row.owner_uuid_bin),
    owner_name: row.owner_name,
    archived: row.archived,
    requires_authorization: row.requires_authorization,
    allow_overdraft: row.allow_overdraft,
    credit_limit: row.credit_limit,
    created_at: row.created_at,
    balance: row.balance,
  }));
}

export type RelationKind = 'owner' | 'authorizer' | 'member' | 'viewer' | 'firm_finance' | 'group';

/** One reason a player can read one account. An account may have several. */
export interface AccountRelationRow {
  account_id: number;
  kind: RelationKind;
  /** Firm role name for firm_finance ('Proprietor' for the proprietor), LuckPerms group for group. */
  detail: string | null;
  account_type: string;
  display_name: string | null;
  owner_uuid: string | null;
  owner_name: string | null;
  archived: number;
  created_at: Date;
  balance: string;
  /** The firm holding the account (active firm_accounts link), if any. */
  firm_id: number | null;
  firm_name: string | null;
}

/**
 * Every account the player owns or has a read relationship to, with the kind
 * of each relationship, in one round trip. The branches are exactly
 * ownership ∪ account_read_access_web (V30): direct account_access levels,
 * fresh LuckPerms group grants, and firm finance (proprietor, or a current
 * employee whose role carries FINANCIAL or ADMIN). They are spelled out
 * instead of read from the view so each row says *why* it is readable.
 * Each branch seeks an index on the player (idx_access_subject,
 * idx_explorer_lp_player, idx_firm_proprietor, idx_emp_player).
 */
/** One collation for every UNION branch: the tables' collations and the connection's differ between servers. */
const text = (expr: RawBuilder<unknown>) => sql`CONVERT(${expr} USING utf8mb4) COLLATE utf8mb4_unicode_ci`;

export async function selectAccountRelations(playerUuid: string): Promise<AccountRelationRow[]> {
  const bin = uuidToBin(playerUuid);
  const r = await sql<Omit<AccountRelationRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }>`
    SELECT rel.account_id, rel.kind, rel.detail, a.account_type, a.display_name, a.owner_uuid_bin,
           op.current_name AS owner_name, a.is_archived AS archived, a.created_at,
           COALESCE(abm.balance, 0.00) AS balance, fa.firm_id, f.display_name AS firm_name
    FROM (
      SELECT o.account_id, ${text(sql`'owner'`)} AS kind, ${text(sql`NULL`)} AS detail
        FROM accounts o WHERE o.owner_uuid_bin = ${bin}
      UNION ALL
      SELECT aa.account_id, ${text(sql`LOWER(aa.level)`)}, ${text(sql`NULL`)}
        FROM account_access aa
       WHERE aa.subject_uuid_bin = ${bin} AND aa.removed_at IS NULL AND aa.level IN ('VIEWER', 'MEMBER', 'AUTHORIZER')
      UNION ALL
      SELECT aga.account_id, ${text(sql`'group'`)}, ${text(sql`aga.lp_group`)}
        FROM explorer_lp_group_member gm
        JOIN account_group_access aga ON aga.lp_group = gm.lp_group AND aga.removed_at IS NULL
       WHERE gm.player_uuid_bin = ${bin} AND gm.synced_at > CURRENT_TIMESTAMP - INTERVAL 1 HOUR
      UNION ALL
      SELECT pa.account_id, ${text(sql`'firm_finance'`)}, ${text(sql`'Proprietor'`)}
        FROM firm pf JOIN firm_accounts pa ON pa.firm_id = pf.firm_id
       WHERE pf.proprietor_uuid_bin = ${bin}
      UNION ALL
      SELECT ea.account_id, ${text(sql`'firm_finance'`)}, ${text(sql`er.name`)}
        FROM firm_employee fe
        JOIN firm_role er ON er.role_id = fe.role_id AND er.firm_id = fe.firm_id AND er.deleted_at IS NULL
        JOIN firm_accounts ea ON ea.firm_id = fe.firm_id
       WHERE fe.player_uuid_bin = ${bin} AND fe.left_at IS NULL
         AND EXISTS (SELECT 1 FROM firm_role_permission rp
                      WHERE rp.role_id = er.role_id AND rp.deleted_at IS NULL AND rp.permission IN ('FINANCIAL', 'ADMIN'))
    ) rel
    JOIN accounts a ON a.account_id = rel.account_id
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players op ON op.player_uuid_bin = a.owner_uuid_bin
    LEFT JOIN firm_accounts fa ON fa.account_id = a.account_id AND fa.removed_at IS NULL
    LEFT JOIN firm f ON f.firm_id = fa.firm_id
    ORDER BY a.account_id, rel.kind
  `.execute(db);
  return r.rows.map(({ owner_uuid_bin, ...row }) => ({
    ...row,
    archived: Number(row.archived),
    owner_uuid: uuidOrNull(owner_uuid_bin),
  }));
}

export interface FirmRoleRow {
  firm_id: number;
  firm_name: string;
  archived: number;
  is_proprietor: number;
  /** Current role, or null for a proprietor who is not also an employee. */
  role_name: string | null;
  rank_order: number | null;
  /** Comma-separated firm_role_permission values of the current role. */
  permissions: string | null;
  /** Current employment start (the hire date). */
  joined_at: Date | null;
  /** Earliest employment at this firm, including spells that ended (V39). */
  first_joined_at: Date | null;
}

/** Firms the player owns or currently works for, with role, permissions and hire date. */
export async function selectFirmRoles(playerUuid: string): Promise<FirmRoleRow[]> {
  const bin = uuidToBin(playerUuid);
  const r = await sql<FirmRoleRow>`
    SELECT f.firm_id, f.display_name AS firm_name, f.is_archived AS archived,
           (f.proprietor_uuid_bin = ${bin}) AS is_proprietor,
           fr.name AS role_name, fr.rank_order,
           (SELECT GROUP_CONCAT(DISTINCT CAST(rp.permission AS CHAR) ORDER BY CAST(rp.permission AS CHAR))
              FROM firm_role_permission rp WHERE rp.role_id = fr.role_id AND rp.deleted_at IS NULL) AS permissions,
           cur.joined_at,
           (SELECT MIN(h.joined_at) FROM firm_employee h WHERE h.firm_id = f.firm_id AND h.player_uuid_bin = ${bin}) AS first_joined_at
    FROM (
      SELECT firm_id FROM firm WHERE proprietor_uuid_bin = ${bin}
      UNION
      SELECT firm_id FROM firm_employee WHERE player_uuid_bin = ${bin} AND left_at IS NULL
    ) mine
    JOIN firm f ON f.firm_id = mine.firm_id
    LEFT JOIN firm_employee cur ON cur.firm_id = f.firm_id AND cur.player_uuid_bin = ${bin} AND cur.left_at IS NULL
    LEFT JOIN firm_role fr ON fr.role_id = cur.role_id AND fr.deleted_at IS NULL
    ORDER BY f.is_archived, is_proprietor DESC, f.display_name
  `.execute(db);
  return r.rows.map((row) => ({
    ...row,
    archived: Number(row.archived),
    is_proprietor: Number(row.is_proprietor),
    rank_order: row.rank_order === null ? null : Number(row.rank_order),
  }));
}

export interface FirmAccountBalanceRow { firm_id: number; account_id: number; balance: string }

/** Active accounts and balances of the given firms (idx_firm_account_active). */
export async function selectFirmAccountBalances(firmIds: readonly number[]): Promise<FirmAccountBalanceRow[]> {
  if (firmIds.length === 0) return [];
  const r = await sql<FirmAccountBalanceRow>`
    SELECT fa.firm_id, fa.account_id, COALESCE(abm.balance, 0.00) AS balance
    FROM firm_accounts fa
    LEFT JOIN account_balances_mat abm ON abm.account_id = fa.account_id
    WHERE fa.firm_id IN (${sql.join(firmIds)}) AND fa.removed_at IS NULL
    ORDER BY fa.firm_id, fa.account_id
  `.execute(db);
  return r.rows;
}

// ── Rollup reads (explorer_rollup_*, V43) ─────────────────────────────────

export interface MonthFlowGroupRow {
  /** YYYY-MM */
  month: string;
  plugin_key: string;
  tax_kind: string;
  counterparty_type: string;
  is_internal: number;
  inflow: string;
  outflow: string;
}

/**
 * Flows over a set of accounts per calendar month and categorisation input,
 * for days [from, to]. The PK leads with account_id, so this is one range per
 * account. Callers categorise (lib/categories.ts) and sum.
 */
export async function selectMonthlyFlowGroups(accountIds: readonly number[], from: string, to: string): Promise<MonthFlowGroupRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<MonthFlowGroupRow>`
    SELECT DATE_FORMAT(day, '%Y-%m') AS month, plugin_key, tax_kind, counterparty_type, is_internal,
           SUM(inflow) AS inflow, SUM(outflow) AS outflow
    FROM explorer_rollup_account_day
    WHERE account_id IN (${sql.join(accountIds)}) AND day BETWEEN ${from} AND ${to}
    GROUP BY month, plugin_key, tax_kind, counterparty_type, is_internal
    ORDER BY month
  `.execute(db);
  return r.rows.map((row) => ({ ...row, is_internal: Number(row.is_internal) }));
}

export interface FirmMonthNetRow { firm_id: number; inflow: string; outflow: string }

/**
 * Inflow and outflow of each firm's active accounts over [from, to], leaving
 * out transfers between the firm's own accounts (is_internal).
 */
export async function selectFirmFlows(firmIds: readonly number[], from: string, to: string): Promise<FirmMonthNetRow[]> {
  if (firmIds.length === 0) return [];
  const r = await sql<FirmMonthNetRow>`
    SELECT fa.firm_id, COALESCE(SUM(d.inflow), 0.00) AS inflow, COALESCE(SUM(d.outflow), 0.00) AS outflow
    FROM firm_accounts fa
    JOIN explorer_rollup_account_day d ON d.account_id = fa.account_id AND d.day BETWEEN ${from} AND ${to} AND d.is_internal = 0
    WHERE fa.firm_id IN (${sql.join(firmIds)}) AND fa.removed_at IS NULL
    GROUP BY fa.firm_id
  `.execute(db);
  return r.rows;
}

export interface TopCounterpartyRow {
  side: 'in' | 'out';
  counterparty_id: number;
  amount: string;
  txn_count: number;
  display_name: string | null;
  account_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
  firm_id: number | null;
  firm_name: string | null;
}

/**
 * The largest sources of money in and destinations of money out for a set of
 * accounts over whole months [fromMonth, toMonth] (first days), from
 * explorer_rollup_pair_month. Counterparties inside the set (the player's own
 * accounts) are left out.
 */
export async function selectTopCounterparties(
  accountIds: readonly number[], fromMonth: string, toMonth: string, limit: number,
): Promise<TopCounterpartyRow[]> {
  if (accountIds.length === 0) return [];
  const ids = sql.join(accountIds);
  const side = (column: 'inflow' | 'outflow', label: 'in' | 'out') => sql`
    (SELECT ${label} AS side, counterparty_id, SUM(${sql.ref(column)}) AS amount, SUM(txn_count) AS txn_count
       FROM explorer_rollup_pair_month
      WHERE account_id IN (${ids}) AND month BETWEEN ${fromMonth} AND ${toMonth} AND counterparty_id NOT IN (${ids})
      GROUP BY counterparty_id
     HAVING SUM(${sql.ref(column)}) > 0
      ORDER BY SUM(${sql.ref(column)}) DESC, counterparty_id
      LIMIT ${limit})`;
  const r = await sql<Omit<TopCounterpartyRow, 'owner_uuid' | 'txn_count'> & { owner_uuid_bin: Buffer | null; txn_count: string | number }>`
    SELECT t.side, t.counterparty_id, t.amount, t.txn_count, a.display_name, a.account_type, a.owner_uuid_bin,
           op.current_name AS owner_name, fa.firm_id, f.display_name AS firm_name
    FROM (${side('inflow', 'in')} UNION ALL ${side('outflow', 'out')}) t
    JOIN accounts a ON a.account_id = t.counterparty_id
    LEFT JOIN economy_players op ON op.player_uuid_bin = a.owner_uuid_bin
    LEFT JOIN firm_accounts fa ON fa.account_id = a.account_id AND fa.removed_at IS NULL
    LEFT JOIN firm f ON f.firm_id = fa.firm_id
    ORDER BY t.side, t.amount DESC, t.counterparty_id
  `.execute(db);
  return r.rows.map(({ owner_uuid_bin, ...row }) => ({ ...row, txn_count: Number(row.txn_count), owner_uuid: uuidOrNull(owner_uuid_bin) }));
}

// ── Postings feed ─────────────────────────────────────────────────────────

/** The account a posting belongs to, as the categorisation needs it. */
export interface SubjectAccount {
  accountId: number;
  accountType: string;
  ownerUuid: string | null;
  firmId: number | null;
}

/** Amount and direction chips compile to conditions on the postings index (account_id, txn_id, amount). */
export const meTransactionsColumnMap = defineColumnMap({
  table: meTransactionsTable,
  filters: {
    amount: { column: sql`ABS(lp.amount)` },
    direction: {
      compile: (f) => {
        const dirs = new Set(f.values);
        if (dirs.size !== 1) return null; // both directions: no condition
        return dirs.has('in') ? sql<SqlBool>`lp.amount > 0` : sql<SqlBool>`lp.amount < 0`;
      },
    },
    // Handled by selectPostings itself (bounded windows, per-account constants).
    account: { compile: () => null },
    category: { compile: () => null },
    counterparty: { compile: () => null },
    memo: { compile: () => null },
    date: { compile: () => null },
  },
  sorts: { date: sql`lp.txn_id` },
});

/** The effective tax kind, as explorer_rollup_account_day stores it: V38's kind, or 'fine' for a fine or its revocation. */
function taxKindSql(lt: string): RawBuilder<string> {
  const t = sql.ref(`${lt}.txn_id`);
  return sql<string>`CASE WHEN ${sql.ref(`${lt}.tax_kind`)} IS NOT NULL THEN ${sql.ref(`${lt}.tax_kind`)}
    WHEN EXISTS (SELECT 1 FROM government_fines gf WHERE gf.txn_id = ${t}) THEN 'fine'
    WHEN EXISTS (SELECT 1 FROM government_fines gf WHERE gf.revoke_txn_id = ${t}) THEN 'fine'
    ELSE '' END`;
}

/** Literal SQL string for a constant we control (category or plugin keys). */
const lit = (s: string) => sql.lit(s);

/**
 * lib/categories.ts `categorize` as a SQL CASE, generated from the same
 * PLUGIN_CATEGORIES table so the two cannot drift (an integration test checks
 * them against each other). Inputs are SQL expressions.
 */
export function categoryCaseSql(cols: {
  pluginKey: RawBuilder<unknown>; taxKind: RawBuilder<unknown>; amount: RawBuilder<unknown>;
  counterType: RawBuilder<unknown>; internal: RawBuilder<unknown>;
}): RawBuilder<string> {
  const key = sql`LOWER(TRIM(${cols.pluginKey}))`;
  const tax = sql`LOWER(TRIM(COALESCE(${cols.taxKind}, '')))`;
  const plain = new Map<Category, string[]>();
  const branches: RawBuilder<unknown>[] = [];
  for (const [plugin, rule] of Object.entries(PLUGIN_CATEGORIES)) {
    if (typeof rule === 'string') {
      plain.set(rule, [...(plain.get(rule) ?? []), plugin]);
    } else if ('in' in rule) {
      branches.push(sql`WHEN ${key} = ${lit(plugin)} THEN CASE WHEN ${cols.amount} > 0 THEN ${lit(rule.in)} ELSE ${lit(rule.out)} END`);
    } else {
      branches.push(sql`WHEN ${key} = ${lit(plugin)} THEN CASE WHEN ${cols.counterType} IN (${sql.join(rule.counterparty.map(lit))}) THEN ${lit(rule.category)} ELSE ${lit(rule.otherwise)} END`);
    }
  }
  for (const [category, plugins] of plain) branches.push(sql`WHEN ${key} IN (${sql.join(plugins.map(lit))}) THEN ${lit(category)}`);
  return sql<string>`CASE WHEN ${cols.internal} = 1 THEN 'internal'
    WHEN ${tax} = 'fine' THEN 'fines'
    WHEN ${tax} <> '' THEN 'taxes'
    ${sql.join(branches, sql` `)}
    ELSE 'other' END`;
}

/**
 * The counterparty leg rule of explorer_rollup_account_day: the largest
 * opposite-signed leg of the transaction (ties: lowest posting_id).
 */
function counterPostingSql(lpAlias: string): RawBuilder<number> {
  const amount = sql.ref(`${lpAlias}.amount`);
  return sql<number>`(SELECT c2.posting_id FROM ledger_postings c2
     WHERE c2.txn_id = ${sql.ref(`${lpAlias}.txn_id`)} AND SIGN(c2.amount) = -SIGN(${amount})
     ORDER BY CASE WHEN ${amount} > 0 THEN c2.amount ELSE -c2.amount END, c2.posting_id LIMIT 1)`;
}

/** is_internal of explorer_rollup_account_day, for subject (type, owner, firm, id) against a counter account. */
function internalSql(s: { id: RawBuilder<unknown>; type: RawBuilder<unknown>; owner: RawBuilder<unknown>; firm: RawBuilder<unknown> },
  c: { id: RawBuilder<unknown>; type: RawBuilder<unknown>; owner: RawBuilder<unknown>; firm: RawBuilder<unknown> }): RawBuilder<number> {
  return sql<number>`CASE WHEN ${c.id} IS NULL THEN 0
    WHEN ${c.id} = ${s.id} THEN 1
    WHEN ${s.type} = 'BUSINESS' AND ${c.type} = 'BUSINESS' THEN COALESCE(${s.firm} = ${c.firm}, 0)
    WHEN ${s.type} = ${c.type} AND ${s.type} IN ('PERSONAL', 'GOVERNMENT') THEN COALESCE(${s.owner} = ${c.owner}, 0)
    ELSE 0 END`;
}

export interface PostingScan {
  accounts: readonly SubjectAccount[];
  /** Exclusive upper txn_id (the cursor), or null for the newest. */
  beforeTxnId: number | null;
  /** Inclusive lower txn_id (a date bound or a search window), or null. */
  fromTxnId: number | null;
  /**
   * Exact settlement-time bounds [from, before), checked on top of the txn_id
   * range that drives the index, so a transaction settled out of txn_id order
   * never lands in the wrong period.
   */
  settled?: { from: Date | null; before: Date | null } | undefined;
  /** Index conditions on `lp` from meTransactionsColumnMap (amount, direction). */
  conditions: readonly Condition[];
  /** Row-by-row filters; callers must bound the txn_id range when any is set. */
  categories: readonly Category[] | null;
  counterpartyIds: readonly number[] | null;
  /** Each must appear in the posting memo or the transaction message. */
  texts: readonly string[];
  order: 'recent' | 'largest';
  limit: number;
}

export interface PostingRow {
  posting_id: number;
  txn_id: number;
  account_id: number;
  account_type: string;
  account_name: string | null;
  account_owner_uuid: string | null;
  account_owner_name: string | null;
  amount: string;
  memo: string | null;
  message: string;
  settlement_time: Date;
  plugin_key: string;
  tax_kind: string;
  counter_id: number | null;
  counter_type: string | null;
  counter_name: string | null;
  counter_owner_uuid: string | null;
  counter_owner_name: string | null;
  counter_firm_id: number | null;
  counter_firm_name: string | null;
  is_internal: number;
}

function likeContains(text: string): string {
  return `%${text.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
}

/** One account's slice of the feed: an index range on (account_id, txn_id), newest or largest first. */
function branch(scan: PostingScan, account: SubjectAccount): RawBuilder<unknown> {
  const where: Condition[] = [sql<SqlBool>`lp.account_id = ${account.accountId}`];
  if (scan.beforeTxnId !== null) where.push(sql<SqlBool>`lp.txn_id < ${scan.beforeTxnId}`);
  if (scan.fromTxnId !== null) where.push(sql<SqlBool>`lp.txn_id >= ${scan.fromTxnId}`);
  if (scan.settled?.from) where.push(sql<SqlBool>`lt.settlement_time >= ${scan.settled.from}`);
  if (scan.settled?.before) where.push(sql<SqlBool>`lt.settlement_time < ${scan.settled.before}`);
  where.push(...scan.conditions);
  if (scan.counterpartyIds?.length) {
    where.push(sql<SqlBool>`EXISTS (SELECT 1 FROM ledger_postings o WHERE o.txn_id = lp.txn_id
      AND o.account_id IN (${sql.join([...scan.counterpartyIds])}) AND o.account_id <> lp.account_id)`);
  }
  for (const text of scan.texts) where.push(sql<SqlBool>`(lp.memo LIKE ${likeContains(text)} OR lt.message LIKE ${likeContains(text)})`);
  const needsTxn = scan.texts.length > 0 || scan.categories !== null || !!scan.settled?.from || !!scan.settled?.before;
  const order = scan.order === 'largest' ? sql`ABS(r.amount) DESC, r.txn_id DESC` : sql`r.txn_id DESC`;
  const inner = (extra: RawBuilder<unknown>) => sql`
    SELECT lp.posting_id, lp.txn_id, lp.account_id, lp.amount ${extra}
    FROM ledger_postings lp ${needsTxn ? sql`JOIN ledger_txns lt ON lt.txn_id = lp.txn_id` : sql``}
    WHERE ${sql.join(where, sql` AND `)}`;
  if (!scan.categories) {
    return sql`(SELECT r.posting_id, r.txn_id, r.account_id, r.amount FROM (${inner(sql``)}) r ORDER BY ${order} LIMIT ${scan.limit})`;
  }
  // Category needs the counter leg's type and the internal flag, against this
  // account's constants (type, owner, firm).
  const owner = account.ownerUuid ? uuidToBin(account.ownerUuid) : null;
  const subject = { id: sql`${account.accountId}`, type: sql`${account.accountType}`, owner: sql`${owner}`, firm: sql`${account.firmId}` };
  const counter = { id: sql`cp.account_id`, type: sql`ca.account_type`, owner: sql`ca.owner_uuid_bin`, firm: sql`cf.firm_id` };
  const category = categoryCaseSql({
    pluginKey: sql`r.plugin_key`, taxKind: sql`r.tax_kind`, amount: sql`r.amount`,
    counterType: sql`ca.account_type`, internal: internalSql(subject, counter),
  });
  return sql`(SELECT r.posting_id, r.txn_id, r.account_id, r.amount
    FROM (${inner(sql`, COALESCE(lt.plugin_system, '') AS plugin_key, ${taxKindSql('lt')} AS tax_kind, ${counterPostingSql('lp')} AS counter_posting`)}) r
    LEFT JOIN ledger_postings cp ON cp.posting_id = r.counter_posting
    LEFT JOIN accounts ca ON ca.account_id = cp.account_id
    LEFT JOIN firm_accounts cf ON cf.account_id = cp.account_id AND cf.removed_at IS NULL
    WHERE ${category} IN (${sql.join(scan.categories.map(lit))})
    ORDER BY ${order} LIMIT ${scan.limit})`;
}

/**
 * A page of postings across the given accounts: one bounded index range per
 * account (UNION ALL of per-account LIMITs, so a busy account never makes the
 * database sort another's history), merged, then hydrated with the
 * transaction, the counter leg and the categorisation inputs.
 */
export async function selectPostings(scan: PostingScan): Promise<PostingRow[]> {
  if (scan.accounts.length === 0 || scan.limit <= 0) return [];
  const branches = scan.accounts.map((a) => branch(scan, a));
  const order = scan.order === 'largest' ? sql`ABS(p.amount) DESC, p.txn_id DESC, p.posting_id DESC` : sql`p.txn_id DESC, p.posting_id DESC`;
  const subject = { id: sql`a.account_id`, type: sql`a.account_type`, owner: sql`a.owner_uuid_bin`, firm: sql`fa.firm_id` };
  const counter = { id: sql`cp.account_id`, type: sql`ca.account_type`, owner: sql`ca.owner_uuid_bin`, firm: sql`cf.firm_id` };
  const r = await sql<Omit<PostingRow, 'account_owner_uuid' | 'counter_owner_uuid'> & { account_owner_bin: Buffer | null; counter_owner_bin: Buffer | null }>`
    SELECT p.posting_id, p.txn_id, p.account_id, a.account_type, a.display_name AS account_name,
           a.owner_uuid_bin AS account_owner_bin, aop.current_name AS account_owner_name,
           lp.amount, lp.memo, lt.message, lt.settlement_time,
           COALESCE(lt.plugin_system, '') AS plugin_key, ${taxKindSql('lt')} AS tax_kind,
           cp.account_id AS counter_id, ca.account_type AS counter_type, ca.display_name AS counter_name,
           ca.owner_uuid_bin AS counter_owner_bin, cop.current_name AS counter_owner_name,
           cf.firm_id AS counter_firm_id, cfirm.display_name AS counter_firm_name,
           ${internalSql(subject, counter)} AS is_internal
    FROM (SELECT * FROM (${sql.join(branches, sql` UNION ALL `)}) u ORDER BY ${sql.raw(scan.order === 'largest' ? 'ABS(u.amount) DESC, u.txn_id DESC, u.posting_id DESC' : 'u.txn_id DESC, u.posting_id DESC')} LIMIT ${scan.limit}) p
    JOIN ledger_postings lp ON lp.posting_id = p.posting_id
    JOIN ledger_txns lt ON lt.txn_id = p.txn_id
    JOIN accounts a ON a.account_id = p.account_id
    LEFT JOIN firm_accounts fa ON fa.account_id = a.account_id AND fa.removed_at IS NULL
    LEFT JOIN economy_players aop ON aop.player_uuid_bin = a.owner_uuid_bin
    LEFT JOIN ledger_postings cp ON cp.posting_id = ${counterPostingSql('lp')}
    LEFT JOIN accounts ca ON ca.account_id = cp.account_id
    LEFT JOIN firm_accounts cf ON cf.account_id = cp.account_id AND cf.removed_at IS NULL
    LEFT JOIN firm cfirm ON cfirm.firm_id = cf.firm_id
    LEFT JOIN economy_players cop ON cop.player_uuid_bin = ca.owner_uuid_bin
    ORDER BY ${order}
  `.execute(db);
  return r.rows.map(({ account_owner_bin, counter_owner_bin, ...row }) => ({
    ...row,
    is_internal: Number(row.is_internal),
    account_owner_uuid: uuidOrNull(account_owner_bin),
    counter_owner_uuid: uuidOrNull(counter_owner_bin),
  }));
}

/**
 * For each time, the first transaction settled at or after it: turns date
 * bounds into txn_id bounds (txn_id is monotonic with creation; ledger.ts
 * explains why ordering by it is safe). One round trip, one seek on
 * idx_ledger_settle_time per time. Null where nothing has settled since.
 */
export async function selectFirstTxnIdsAtOrAfter(times: readonly Date[]): Promise<(number | null)[]> {
  if (times.length === 0) return [];
  const cols = times.map((at, i) => sql`(SELECT txn_id FROM ledger_txns WHERE settlement_time >= ${at} ORDER BY settlement_time, txn_id LIMIT 1) AS ${sql.ref(`t${i}`)}`);
  const r = await sql<Record<string, number | null>>`SELECT ${sql.join(cols)}`.execute(db);
  const row = r.rows[0] ?? {};
  return times.map((_, i) => (row[`t${i}`] == null ? null : Number(row[`t${i}`])));
}

export interface AccountLabelRow { account_id: number; account_type: string; display_name: string | null; owner_uuid: string | null; owner_name: string | null }

/** Names for account ids already on a URL (primary-key lookups). */
export async function selectAccountLabels(ids: readonly number[]): Promise<AccountLabelRow[]> {
  if (ids.length === 0) return [];
  const r = await sql<Omit<AccountLabelRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }>`
    SELECT a.account_id, a.account_type, a.display_name, a.owner_uuid_bin, op.current_name AS owner_name
    FROM accounts a LEFT JOIN economy_players op ON op.player_uuid_bin = a.owner_uuid_bin
    WHERE a.account_id IN (${sql.join([...new Set(ids)])})
  `.execute(db);
  return r.rows.map(({ owner_uuid_bin, ...row }) => ({ ...row, owner_uuid: uuidOrNull(owner_uuid_bin) }));
}

// ── Fines ─────────────────────────────────────────────────────────────────

export interface MyFineRow {
  fine_id: number;
  amount: string;
  reason: string;
  issued_at: Date;
  revoked: number;
  revoked_at: Date | null;
  txn_id: number;
  revoke_txn_id: number | null;
  gov_account_id: number;
  gov_account_name: string | null;
  issued_by_uuid: string | null;
  issued_by_name: string | null;
}

/** Fines issued to the player, newest first (idx_fines_player). At most `limit`. */
export async function selectFinesForPlayer(playerUuid: string, limit: number): Promise<MyFineRow[]> {
  const r = await sql<Omit<MyFineRow, 'issued_by_uuid'> & { issued_by_bin: Buffer | null }>`
    SELECT gf.fine_id, gf.amount, gf.reason, gf.issued_at, gf.revoked, gf.revoked_at, gf.txn_id, gf.revoke_txn_id,
           gf.gov_account_id, a.display_name AS gov_account_name,
           gf.issued_by_uuid_bin AS issued_by_bin, ip.current_name AS issued_by_name
    FROM government_fines gf
    LEFT JOIN accounts a ON a.account_id = gf.gov_account_id
    LEFT JOIN economy_players ip ON ip.player_uuid_bin = gf.issued_by_uuid_bin
    WHERE gf.player_uuid_bin = ${uuidToBin(playerUuid)}
    ORDER BY gf.issued_at DESC, gf.fine_id DESC
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map(({ issued_by_bin, ...row }) => ({ ...row, revoked: Number(row.revoked), issued_by_uuid: uuidOrNull(issued_by_bin) }));
}

// ── API keys ──────────────────────────────────────────────────────────────

export interface MyApiKeyRow {
  key_id: number;
  key_type: string;
  account_id: number | null;
  account_name: string | null;
  account_owner_name: string | null;
  firm_id: number | null;
  firm_name: string | null;
  issued_at: Date;
  expires_at: Date;
  revoked: number;
  /** api_rate_limit_override multiplier for the issuer, or null for the default. */
  rate_multiplier: string | null;
}

/** The player's API keys (idx_api_key_owner) with their issuer rate-limit override. Never the token. */
export async function selectApiKeysForOwner(playerUuid: string): Promise<MyApiKeyRow[]> {
  const bin = uuidToBin(playerUuid);
  const r = await sql<MyApiKeyRow>`
    SELECT k.key_id, k.key_type, k.account_id, a.display_name AS account_name, aop.current_name AS account_owner_name, k.firm_id, f.display_name AS firm_name,
           k.issued_at, k.expires_at, k.revoked, o.multiplier AS rate_multiplier
    FROM api_keys k
    LEFT JOIN accounts a ON a.account_id = k.account_id
    LEFT JOIN economy_players aop ON aop.player_uuid_bin = a.owner_uuid_bin
    LEFT JOIN firm f ON f.firm_id = k.firm_id
    LEFT JOIN api_rate_limit_override o ON o.owner_uuid_bin = k.owner_uuid_bin
    WHERE k.owner_uuid_bin = ${bin}
    ORDER BY k.revoked, k.issued_at DESC, k.key_id DESC
    LIMIT 100
  `.execute(db);
  return r.rows.map((row) => ({ ...row, revoked: Number(row.revoked) }));
}

// ── Market (chestshop_sale) ───────────────────────────────────────────────
//
// The player's trades are two index ranges: idx_cs_customer (customer_uuid_bin,
// occurred_at) for trades at other people's shops, and idx_cs_owner
// (shop_owner_uuid_bin, occurred_at) for trades at their own PERSONAL shops
// (firm shops stay on the firm's pages). `bought` is from the player's side:
// customer + BUY or owner + SELL.

function playerSales(bin: Buffer, since: Date | null): RawBuilder<unknown> {
  const window = since ? sql`AND cs.occurred_at >= ${since}` : sql``;
  return sql`(
    SELECT cs.sale_id, cs.item_key, cs.item_name, cs.material, cs.quantity, cs.total_price,
           (cs.direction = 'BUY') AS bought, 'customer' AS role,
           cs.shop_firm_id, cs.shop_owner_uuid_bin AS partner_uuid_bin, cs.is_admin_shop, cs.shop_account_id
      FROM chestshop_sale cs WHERE cs.customer_uuid_bin = ${bin} ${window}
    UNION ALL
    SELECT cs.sale_id, cs.item_key, cs.item_name, cs.material, cs.quantity, cs.total_price,
           (cs.direction = 'SELL') AS bought, 'owner' AS role,
           NULL AS shop_firm_id, cs.customer_uuid_bin AS partner_uuid_bin, 0 AS is_admin_shop, NULL AS shop_account_id
      FROM chestshop_sale cs WHERE cs.shop_owner_uuid_bin = ${bin} AND cs.shop_account_type = 'PERSONAL' ${window}
  )`;
}

export interface MarketSummaryRow { bought: number; trades: number; quantity: number; amount: string }

/** Trades, quantity and money per side, summed in SQL. */
export async function selectMarketSummary(playerUuid: string, since: Date | null): Promise<MarketSummaryRow[]> {
  const r = await sql<{ bought: number; trades: string | number; quantity: string | number; amount: string }>`
    SELECT s.bought, COUNT(*) AS trades, COALESCE(SUM(s.quantity), 0) AS quantity, COALESCE(SUM(s.total_price), 0.00) AS amount
    FROM ${playerSales(uuidToBin(playerUuid), since)} s
    GROUP BY s.bought
  `.execute(db);
  return r.rows.map((row) => ({ bought: Number(row.bought), trades: Number(row.trades), quantity: Number(row.quantity), amount: row.amount }));
}

export interface MarketItemRow { bought: number; item_key: string; item_name: string | null; material: string | null; trades: number; quantity: number; amount: string }

/** Top items per side by money, `limit` each. */
export async function selectMarketTopItems(playerUuid: string, since: Date | null, limit: number): Promise<MarketItemRow[]> {
  const r = await sql<{ bought: number; item_key: string; item_name: string | null; material: string | null; trades: string | number; quantity: string | number; amount: string }>`
    SELECT bought, item_key, item_name, material, trades, quantity, amount FROM (
      SELECT g.*, ROW_NUMBER() OVER (PARTITION BY g.bought ORDER BY g.amount DESC, g.item_key) AS rn FROM (
        SELECT s.bought, s.item_key, MAX(s.item_name) AS item_name, MAX(s.material) AS material,
               COUNT(*) AS trades, SUM(s.quantity) AS quantity, SUM(s.total_price) AS amount
        FROM ${playerSales(uuidToBin(playerUuid), since)} s
        GROUP BY s.bought, s.item_key
      ) g
    ) ranked WHERE rn <= ${limit}
    ORDER BY bought DESC, amount DESC, item_key
  `.execute(db);
  return r.rows.map((row) => ({ ...row, bought: Number(row.bought), trades: Number(row.trades), quantity: Number(row.quantity) }));
}

export interface MarketPartnerRow {
  bought: number;
  /** 'firm' (a firm's shop), 'admin' (server shop), 'player' (a player's shop or a customer at yours). */
  partner_kind: 'firm' | 'admin' | 'player' | 'unknown';
  firm_id: number | null;
  firm_name: string | null;
  player_uuid: string | null;
  player_name: string | null;
  trades: number;
  amount: string;
}

/** Top trading partners per side by money, `limit` each. */
export async function selectMarketTopPartners(playerUuid: string, since: Date | null, limit: number): Promise<MarketPartnerRow[]> {
  const r = await sql<Omit<MarketPartnerRow, 'player_uuid' | 'trades'> & { partner_uuid_bin: Buffer | null; trades: string | number }>`
    SELECT ranked.bought, ranked.partner_kind, ranked.firm_id, f.display_name AS firm_name,
           ranked.partner_uuid_bin, pp.current_name AS player_name, ranked.trades, ranked.amount
    FROM (
      SELECT g.*, ROW_NUMBER() OVER (PARTITION BY g.bought ORDER BY g.amount DESC, g.partner_kind, g.firm_id, g.partner_uuid_bin) AS rn FROM (
        SELECT s.bought,
               CASE WHEN s.is_admin_shop = 1 THEN 'admin' WHEN s.shop_firm_id IS NOT NULL THEN 'firm'
                    WHEN s.partner_uuid_bin IS NOT NULL THEN 'player' ELSE 'unknown' END AS partner_kind,
               CASE WHEN s.is_admin_shop = 1 THEN NULL ELSE s.shop_firm_id END AS firm_id,
               CASE WHEN s.is_admin_shop = 1 OR s.shop_firm_id IS NOT NULL THEN NULL ELSE s.partner_uuid_bin END AS partner_uuid_bin,
               COUNT(*) AS trades, SUM(s.total_price) AS amount
        FROM ${playerSales(uuidToBin(playerUuid), since)} s
        GROUP BY s.bought, partner_kind, firm_id, partner_uuid_bin
      ) g
    ) ranked
    LEFT JOIN firm f ON f.firm_id = ranked.firm_id
    LEFT JOIN economy_players pp ON pp.player_uuid_bin = ranked.partner_uuid_bin
    WHERE ranked.rn <= ${limit}
    ORDER BY ranked.bought DESC, ranked.amount DESC
  `.execute(db);
  return r.rows.map(({ partner_uuid_bin, ...row }) => ({ ...row, bought: Number(row.bought), trades: Number(row.trades), player_uuid: uuidOrNull(partner_uuid_bin) }));
}

// ── Players (admin lookup) ────────────────────────────────────────────────

/** Resolve a player's current name from the economy_players cache (null if unknown). */
export async function findPlayerName(uuid: string): Promise<string | null> {
  const r = await sql<{ current_name: string | null }>`
    SELECT current_name FROM economy_players WHERE player_uuid_bin = ${uuidToBin(uuid)} LIMIT 1
  `.execute(db);
  return r.rows[0]?.current_name ?? null;
}

/** Name/UUID search over the economy_players cache, for the admin player lookup. */
export async function searchPlayers(q: string, limit: number): Promise<{ uuid: string; name: string }[]> {
  const r = await sql<{ player_uuid_bin: Buffer; current_name: string }>`
    SELECT player_uuid_bin, current_name FROM economy_players
    WHERE current_name LIKE CONCAT('%', ${q}, '%')
    ORDER BY current_name
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({ uuid: binToUuid(row.player_uuid_bin), name: row.current_name }));
}
