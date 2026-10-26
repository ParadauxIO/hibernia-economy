import 'server-only';
import { sql, type RawBuilder, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { finesTable, type FinesTableState } from '@/lib/tables/fines';
import { govLedgerTable, govViewsTable, type GovLedgerState, type GovViewsState } from '@/lib/tables/gov';
import { compileTableState, defineColumnMap, likeContains } from './tableState';

// Pure queries behind /gov (spec §10). Scope is decided in
// lib/services/governmentScope.ts and arrives here as an explicit list of
// account ids; an empty list always means "nothing", never "everything".
//
// Two kinds of read:
//   * aggregates come from explorer_rollup_account_day / _pair_month, bounded by
//     the rollup primary keys (account_id, day/month);
//   * top-N and lists read the ledger, bounded to a txn_id window found on
//     idx_ledger_settle_time (txnIdAt) and walked on
//     idx_postings_account_txn_amount, or driven by idx_ledger_plugin_time /
//     idx_ledger_tax_kind_time. Callers cap those windows at 31 days.
//
// Days are 'YYYY-MM-DD' UTC; the database session runs in UTC like production.

type Condition = RawBuilder<SqlBool>;

const NONE = sql<SqlBool>`FALSE`;
const ids = (list: readonly number[]) => (list.length ? sql.join([...new Set(list)]) : sql`NULL`);
const idIn = (column: RawBuilder<unknown>, list: readonly number[]): Condition =>
  list.length ? sql<SqlBool>`${column} IN (${ids(list)})` : NONE;

function bin(uuidOrNull: Buffer | null | undefined): string | null {
  return uuidOrNull ? binToUuid(uuidOrNull) : null;
}

// ── scope ─────────────────────────────────────────────────────────────────

export interface GovAccountRow {
  account_id: number;
  display_name: string | null;
  owner_uuid: string | null;
  owner_name: string | null;
  account_type: string;
  balance: string;
}

/**
 * THE relationship rule for government scope (spec §10.1), used by /gov and by
 * the top bar's Government entry (lib/sql/nav.ts): the player owns the account
 * or can read it through account_read_access_web (direct or LuckPerms-group
 * access). `a` must be the accounts row being tested.
 */
export function governmentRelationship(playerUuid: string): Condition {
  const subject = uuidToBin(playerUuid);
  return sql<SqlBool>`(a.owner_uuid_bin = ${subject}
    OR EXISTS (SELECT 1 FROM account_read_access_web ar WHERE ar.account_id = a.account_id AND ar.subject_uuid_bin = ${subject}))`;
}

/** Active GOVERNMENT accounts: all of them, or those in a player's relationship scope. */
export async function getGovernmentAccounts(scope: { all: true } | { playerUuid: string }): Promise<GovAccountRow[]> {
  const r = await sql<Omit<GovAccountRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }>`
    SELECT a.account_id, a.display_name, a.owner_uuid_bin, fp.current_name AS owner_name,
           a.account_type, COALESCE(abm.balance, 0.00) AS balance
    FROM accounts a
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE a.is_archived = 0 AND a.account_type = 'GOVERNMENT'
      AND ${'all' in scope ? sql`TRUE` : governmentRelationship(scope.playerUuid)}
    ORDER BY COALESCE(abm.balance, 0.00) DESC, a.account_id
  `.execute(db);
  return r.rows.map(({ owner_uuid_bin, ...row }) => ({ ...row, account_id: Number(row.account_id), owner_uuid: bin(owner_uuid_bin) }));
}

// ── txn windows ───────────────────────────────────────────────────────────

/**
 * The first transaction settled at or after `at` (a UTC 'YYYY-MM-DD HH:MM:SS'),
 * or null when none has been yet. One index probe on idx_ledger_settle_time.
 * Transaction ids are assigned in settlement order, so [txnIdAt(from),
 * txnIdAt(to)) is the window's id range; ledger_postings has no time column,
 * and this is what lets a per-account read stay on (account_id, txn_id).
 */
export async function txnIdAt(at: string): Promise<number | null> {
  const r = await sql<{ txn_id: number | string }>`
    SELECT txn_id FROM ledger_txns WHERE settlement_time >= ${at}
    ORDER BY settlement_time, txn_id LIMIT 1
  `.execute(db);
  const row = r.rows[0];
  return row ? Number(row.txn_id) : null;
}

/** An inclusive txn_id window; `hi` null means "to the newest". */
export interface TxnWindow { lo: number; hi: number | null }

export function inWindow(column: RawBuilder<unknown>, w: TxnWindow): Condition {
  return w.hi === null ? sql<SqlBool>`${column} >= ${w.lo}` : sql<SqlBool>`${column} BETWEEN ${w.lo} AND ${w.hi}`;
}

// ── rollup aggregates ─────────────────────────────────────────────────────

export interface FlowGroupRow {
  account_id: number;
  plugin_key: string;
  tax_kind: string;
  counterparty_type: string;
  is_internal: number;
  inflow: string;
  outflow: string;
  txn_count: number;
}

/** Flows per account and categorisation inputs over a day range. */
export async function selectGovFlowGroups(accountIds: readonly number[], from: string, to: string): Promise<FlowGroupRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<FlowGroupRow>`
    SELECT account_id, plugin_key, tax_kind, counterparty_type, is_internal,
           SUM(inflow) AS inflow, SUM(outflow) AS outflow, SUM(txn_count) AS txn_count
    FROM explorer_rollup_account_day
    WHERE account_id IN (${ids(accountIds)}) AND day BETWEEN ${from} AND ${to}
    GROUP BY account_id, plugin_key, tax_kind, counterparty_type, is_internal
  `.execute(db);
  return r.rows.map((row) => ({ ...row, account_id: Number(row.account_id), is_internal: Number(row.is_internal), txn_count: Number(row.txn_count) }));
}

export interface MonthFlowRow { month: string; government: number; inflow: string; outflow: string }

/** Inflow and outflow per calendar month, split by whether the counterparty was a government account. */
export async function selectGovMonthlyFlows(accountIds: readonly number[], from: string, to: string): Promise<MonthFlowRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<MonthFlowRow>`
    SELECT DATE_FORMAT(day, '%Y-%m-01') AS month,
           (counterparty_type = 'GOVERNMENT' OR is_internal = 1) AS government,
           SUM(inflow) AS inflow, SUM(outflow) AS outflow
    FROM explorer_rollup_account_day
    WHERE account_id IN (${ids(accountIds)}) AND day BETWEEN ${from} AND ${to}
    GROUP BY month, government
    ORDER BY month
  `.execute(db);
  return r.rows.map((row) => ({ ...row, government: Number(row.government) }));
}

export interface AccountDayNetRow { account_id: number; day: string; net: string }

/** Net change per account and day (quiet days absent). Sparklines and 30-day nets. */
export async function selectGovAccountDayNets(accountIds: readonly number[], from: string, to: string): Promise<AccountDayNetRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<AccountDayNetRow>`
    SELECT account_id, DATE_FORMAT(day, '%Y-%m-%d') AS day, SUM(inflow) - SUM(outflow) AS net
    FROM explorer_rollup_account_day
    WHERE account_id IN (${ids(accountIds)}) AND day BETWEEN ${from} AND ${to}
    GROUP BY account_id, day
    ORDER BY account_id, day
  `.execute(db);
  return r.rows.map((row) => ({ ...row, account_id: Number(row.account_id) }));
}

export interface PluginDayRow { day: string; outflow: string; txn_count: number }

/** Daily outflow of the given plugin keys (as stored) from a set of accounts. */
export async function selectGovPluginOutflowDays(accountIds: readonly number[], pluginKeys: readonly string[], from: string, to: string): Promise<PluginDayRow[]> {
  if (accountIds.length === 0 || pluginKeys.length === 0) return [];
  const r = await sql<PluginDayRow>`
    SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, SUM(outflow) AS outflow, SUM(txn_count) AS txn_count
    FROM explorer_rollup_account_day
    WHERE account_id IN (${ids(accountIds)}) AND day BETWEEN ${from} AND ${to}
      AND plugin_key IN (${sql.join([...pluginKeys])}) AND outflow > 0
    GROUP BY day
    ORDER BY day
  `.execute(db);
  return r.rows.map((row) => ({ ...row, txn_count: Number(row.txn_count) }));
}

export interface CounterpartyRow {
  counterparty_id: number;
  display_name: string | null;
  account_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
  firm_id: number | null;
  firm_name: string | null;
  inflow: string;
  outflow: string;
  txn_count: number;
}

/**
 * Counterparties of a set of accounts over whole months, ordered by money
 * received from them (`in`) or paid to them (`out`). Accounts inside the set
 * are left out: those are its internal transfers.
 */
export async function selectGovCounterparties(
  accountIds: readonly number[], fromMonth: string, toMonth: string, order: 'in' | 'out', limit: number,
): Promise<CounterpartyRow[]> {
  if (accountIds.length === 0) return [];
  const orderBy = order === 'in' ? sql`SUM(inflow)` : sql`SUM(outflow)`;
  const r = await sql<Omit<CounterpartyRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }>`
    SELECT pm.counterparty_id, a.display_name, a.account_type, a.owner_uuid_bin, fp.current_name AS owner_name,
           fa.firm_id, f.display_name AS firm_name, pm.inflow, pm.outflow, pm.txn_count
    FROM (
      SELECT counterparty_id, SUM(inflow) AS inflow, SUM(outflow) AS outflow, SUM(txn_count) AS txn_count
      FROM explorer_rollup_pair_month
      WHERE account_id IN (${ids(accountIds)}) AND month BETWEEN ${fromMonth} AND ${toMonth}
        AND counterparty_id NOT IN (${ids(accountIds)})
      GROUP BY counterparty_id
      HAVING ${orderBy} > 0
      ORDER BY ${orderBy} DESC, counterparty_id
      LIMIT ${limit}
    ) pm
    JOIN accounts a ON a.account_id = pm.counterparty_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    LEFT JOIN firm_accounts fa ON fa.account_id = a.account_id AND fa.removed_at IS NULL
    LEFT JOIN firm f ON f.firm_id = fa.firm_id
    ORDER BY ${order === 'in' ? sql`pm.inflow` : sql`pm.outflow`} DESC, pm.counterparty_id
  `.execute(db);
  return r.rows.map(({ owner_uuid_bin, ...row }) => ({
    ...row, counterparty_id: Number(row.counterparty_id), firm_id: row.firm_id === null ? null : Number(row.firm_id),
    owner_uuid: bin(owner_uuid_bin), txn_count: Number(row.txn_count),
  }));
}

// ── ledger top-N (bounded windows) ────────────────────────────────────────

export interface MovementRow {
  txn_id: number;
  account_id: number;
  account_name: string | null;
  amount: string;
  settled_at: Date;
  message: string;
  plugin_system: string | null;
  initiator_uuid: string | null;
  initiator_name: string | null;
  authorizer_uuid: string | null;
  authorizer_name: string | null;
  counterparty_id: number | null;
  counterparty_name: string | null;
  counterparty_type: string | null;
  counterparty_owner_name: string | null;
}

/** Shared projection for posting rows: txn details, people, and the largest opposite leg. */
export function movementSelect(inner: RawBuilder<unknown>): RawBuilder<unknown> {
  return sql`
    SELECT p.txn_id, p.account_id, own.display_name AS account_name, p.amount, t.settlement_time AS settled_at,
           t.message, t.plugin_system, t.initiator_uuid_bin, ip.current_name AS initiator_name,
           t.authorizer_uuid_bin, ap.current_name AS authorizer_name,
           cp.account_id AS counterparty_id, cp.display_name AS counterparty_name, cp.account_type AS counterparty_type,
           cpo.current_name AS counterparty_owner_name
    FROM (${inner}) p
    JOIN ledger_txns t ON t.txn_id = p.txn_id
    JOIN accounts own ON own.account_id = p.account_id
    LEFT JOIN economy_players ip ON ip.player_uuid_bin = t.initiator_uuid_bin
    LEFT JOIN economy_players ap ON ap.player_uuid_bin = t.authorizer_uuid_bin
    LEFT JOIN accounts cp ON cp.account_id = (
      SELECT o.account_id FROM ledger_postings o
      WHERE o.txn_id = p.txn_id AND SIGN(o.amount) = -SIGN(p.amount)
      ORDER BY ABS(o.amount) DESC, o.posting_id LIMIT 1)
    LEFT JOIN economy_players cpo ON cpo.player_uuid_bin = cp.owner_uuid_bin
  `;
}

export type RawMovement = Omit<MovementRow, 'initiator_uuid' | 'authorizer_uuid'> & { initiator_uuid_bin: Buffer | null; authorizer_uuid_bin: Buffer | null };

export function toMovement({ initiator_uuid_bin, authorizer_uuid_bin, ...row }: RawMovement): MovementRow {
  return {
    ...row,
    txn_id: Number(row.txn_id),
    account_id: Number(row.account_id),
    counterparty_id: row.counterparty_id === null ? null : Number(row.counterparty_id),
    initiator_uuid: bin(initiator_uuid_bin),
    authorizer_uuid: bin(authorizer_uuid_bin),
  };
}

/**
 * The largest postings (either direction) on a set of accounts inside a txn
 * window. The inner query reads only idx_postings_account_txn_amount (it
 * covers account, txn and amount); details are joined for the `limit` rows.
 */
export async function selectLargestMovements(accountIds: readonly number[], window: TxnWindow, limit: number): Promise<MovementRow[]> {
  if (accountIds.length === 0) return [];
  const inner = sql`
    SELECT p.txn_id, p.account_id, p.amount
    FROM ledger_postings p
    WHERE p.account_id IN (${ids(accountIds)}) AND ${inWindow(sql`p.txn_id`, window)}
    ORDER BY ABS(p.amount) DESC, p.txn_id DESC
    LIMIT ${limit}`;
  const r = await sql<RawMovement>`${movementSelect(inner)} ORDER BY ABS(p.amount) DESC, p.txn_id DESC`.execute(db);
  return r.rows.map(toMovement);
}

export interface PayerRow {
  account_id: number;
  display_name: string | null;
  account_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
  firm_id: number | null;
  firm_name: string | null;
  total: string;
  payments: number;
}

function payerProjection(inner: RawBuilder<unknown>): RawBuilder<unknown> {
  return sql`
    SELECT x.account_id, a.display_name, a.account_type, a.owner_uuid_bin, fp.current_name AS owner_name,
           fa.firm_id, f.display_name AS firm_name, x.total, x.payments
    FROM (${inner}) x
    JOIN accounts a ON a.account_id = x.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    LEFT JOIN firm_accounts fa ON fa.account_id = a.account_id AND fa.removed_at IS NULL
    LEFT JOIN firm f ON f.firm_id = fa.firm_id
    ORDER BY x.total DESC, x.account_id`;
}

function toPayer({ owner_uuid_bin, ...row }: Omit<PayerRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }): PayerRow {
  return { ...row, account_id: Number(row.account_id), firm_id: row.firm_id === null ? null : Number(row.firm_id), owner_uuid: bin(owner_uuid_bin), payments: Number(row.payments) };
}

/**
 * Accounts that paid the most tax into a set of accounts. Driven by
 * idx_ledger_tax_kind_time over [fromTs, toTs) for the given kinds; each tax
 * transaction then joins its legs by txn_id. The payer is the debited leg.
 */
export async function selectTopTaxPayers(accountIds: readonly number[], kinds: readonly string[], fromTs: string, toTs: string, limit: number): Promise<PayerRow[]> {
  if (accountIds.length === 0 || kinds.length === 0) return [];
  const inner = sql`
    SELECT payer.account_id, SUM(-payer.amount) AS total, COUNT(DISTINCT t.txn_id) AS payments
    FROM ledger_txns t FORCE INDEX (idx_ledger_tax_kind_time)
    STRAIGHT_JOIN ledger_postings g ON g.txn_id = t.txn_id
    STRAIGHT_JOIN ledger_postings payer ON payer.txn_id = t.txn_id
    WHERE t.tax_kind IN (${sql.join([...kinds])}) AND t.settlement_time >= ${fromTs} AND t.settlement_time < ${toTs}
      AND g.account_id IN (${ids(accountIds)}) AND g.amount > 0
      AND payer.amount < 0 AND payer.account_id NOT IN (${ids(accountIds)})
    GROUP BY payer.account_id
    ORDER BY total DESC, payer.account_id
    LIMIT ${limit}`;
  const r = await sql<Omit<PayerRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }>`${payerProjection(inner)}`.execute(db);
  return r.rows.map(toPayer);
}

/**
 * Debtors who paid the most in (non-revoked) fines into a set of accounts.
 * government_fines is small and bounded by idx_fines_issued_at.
 */
export async function selectTopFinePayers(accountIds: readonly number[], fromTs: string, toTs: string, limit: number): Promise<(PayerRow & { player_uuid: string | null; player_name: string | null })[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<{ debtor_account_id: number | null; player_uuid_bin: Buffer | null; player_name: string | null; display_name: string | null; account_type: string | null; firm_id: number | null; firm_name: string | null; total: string; payments: number }>`
    SELECT x.debtor_account_id, x.player_uuid_bin, fp.current_name AS player_name, a.display_name, a.account_type,
           fa.firm_id, f.display_name AS firm_name, x.total, x.payments
    FROM (
      SELECT gf.debtor_account_id, gf.player_uuid_bin, SUM(gf.amount) AS total, COUNT(*) AS payments
      FROM government_fines gf
      WHERE gf.issued_at >= ${fromTs} AND gf.issued_at < ${toTs} AND gf.revoked = 0
        AND gf.gov_account_id IN (${ids(accountIds)})
      GROUP BY gf.debtor_account_id, gf.player_uuid_bin
      ORDER BY total DESC
      LIMIT ${limit}
    ) x
    LEFT JOIN accounts a ON a.account_id = x.debtor_account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = x.player_uuid_bin
    LEFT JOIN firm_accounts fa ON fa.account_id = a.account_id AND fa.removed_at IS NULL
    LEFT JOIN firm f ON f.firm_id = fa.firm_id
    ORDER BY x.total DESC
  `.execute(db);
  return r.rows.map((row) => ({
    account_id: row.debtor_account_id === null ? 0 : Number(row.debtor_account_id),
    display_name: row.display_name,
    account_type: row.account_type ?? 'PERSONAL',
    owner_uuid: bin(row.player_uuid_bin),
    owner_name: row.player_name,
    player_uuid: bin(row.player_uuid_bin),
    player_name: row.player_name,
    firm_id: row.firm_id === null ? null : Number(row.firm_id),
    firm_name: row.firm_name,
    total: row.total,
    payments: Number(row.payments),
  }));
}

export interface OfficialRow {
  initiator_uuid: string | null;
  initiator_name: string | null;
  authorizer_uuid: string | null;
  authorizer_name: string | null;
  total: string;
  transactions: number;
  last_at: Date;
}

/**
 * Money moved out of (or into) a set of accounts per initiator and authorizer
 * (ledger_txns.initiator_uuid_bin / authorizer_uuid_bin). The posting side is
 * walked on idx_postings_account_txn_amount (the sign filter is inside the
 * index); only matching postings look up their transaction.
 */
export async function selectMoversByOfficial(accountIds: readonly number[], window: TxnWindow, direction: 'in' | 'out', limit: number): Promise<OfficialRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<Omit<OfficialRow, 'initiator_uuid' | 'authorizer_uuid'> & { initiator_uuid_bin: Buffer | null; authorizer_uuid_bin: Buffer | null }>`
    SELECT x.initiator_uuid_bin, ip.current_name AS initiator_name, x.authorizer_uuid_bin, ap.current_name AS authorizer_name,
           x.total, x.transactions, x.last_at
    FROM (
      SELECT t.initiator_uuid_bin, t.authorizer_uuid_bin, SUM(ABS(p.amount)) AS total, COUNT(*) AS transactions, MAX(t.settlement_time) AS last_at
      FROM ledger_postings p
      STRAIGHT_JOIN ledger_txns t ON t.txn_id = p.txn_id
      WHERE p.account_id IN (${ids(accountIds)}) AND ${inWindow(sql`p.txn_id`, window)}
        AND ${direction === 'out' ? sql`p.amount < 0` : sql`p.amount > 0`}
      GROUP BY t.initiator_uuid_bin, t.authorizer_uuid_bin
      ORDER BY total DESC
      LIMIT ${limit}
    ) x
    LEFT JOIN economy_players ip ON ip.player_uuid_bin = x.initiator_uuid_bin
    LEFT JOIN economy_players ap ON ap.player_uuid_bin = x.authorizer_uuid_bin
    ORDER BY x.total DESC
  `.execute(db);
  return r.rows.map(({ initiator_uuid_bin, authorizer_uuid_bin, ...row }) => ({
    ...row, initiator_uuid: bin(initiator_uuid_bin), authorizer_uuid: bin(authorizer_uuid_bin), transactions: Number(row.transactions),
  }));
}

export interface SalaryBucketRow { at: string; payments: number; recipients: number; total: string }

/**
 * Salary payouts from a set of accounts per settlement minute, driven by
 * idx_ledger_plugin_time for the given plugin keys over [fromTs, toTs).
 */
export async function selectSalaryBuckets(accountIds: readonly number[], pluginKeys: readonly string[], fromTs: string, toTs: string): Promise<SalaryBucketRow[]> {
  if (accountIds.length === 0 || pluginKeys.length === 0) return [];
  const r = await sql<SalaryBucketRow>`
    SELECT DATE_FORMAT(t.settlement_time, '%Y-%m-%dT%H:%i:00.000Z') AS at, COUNT(DISTINCT t.txn_id) AS payments,
           COUNT(DISTINCT r.account_id) AS recipients, SUM(-p.amount) AS total
    FROM ledger_txns t FORCE INDEX (idx_ledger_plugin_time)
    STRAIGHT_JOIN ledger_postings p ON p.txn_id = t.txn_id
    LEFT JOIN ledger_postings r ON r.txn_id = t.txn_id AND r.amount > 0
    WHERE t.plugin_system IN (${sql.join([...pluginKeys])}) AND t.settlement_time >= ${fromTs} AND t.settlement_time < ${toTs}
      AND p.account_id IN (${ids(accountIds)}) AND p.amount < 0
    GROUP BY at
    ORDER BY at
  `.execute(db);
  return r.rows.map((row) => ({ ...row, payments: Number(row.payments), recipients: Number(row.recipients) }));
}

export interface SalaryRecipientRow {
  account_id: number;
  display_name: string | null;
  account_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
  total: string;
  payments: number;
  last_paid: Date;
}

/** Who received salary from a set of accounts over [fromTs, toTs), largest first. */
export async function selectSalaryRecipients(accountIds: readonly number[], pluginKeys: readonly string[], fromTs: string, toTs: string, limit: number): Promise<SalaryRecipientRow[]> {
  if (accountIds.length === 0 || pluginKeys.length === 0) return [];
  const r = await sql<Omit<SalaryRecipientRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }>`
    SELECT x.account_id, a.display_name, a.account_type, a.owner_uuid_bin, fp.current_name AS owner_name, x.total, x.payments, x.last_paid
    FROM (
      SELECT r.account_id, SUM(r.amount) AS total, COUNT(DISTINCT t.txn_id) AS payments, MAX(t.settlement_time) AS last_paid
      FROM ledger_txns t FORCE INDEX (idx_ledger_plugin_time)
      STRAIGHT_JOIN ledger_postings p ON p.txn_id = t.txn_id
      STRAIGHT_JOIN ledger_postings r ON r.txn_id = t.txn_id
      WHERE t.plugin_system IN (${sql.join([...pluginKeys])}) AND t.settlement_time >= ${fromTs} AND t.settlement_time < ${toTs}
        AND p.account_id IN (${ids(accountIds)}) AND p.amount < 0 AND r.amount > 0
      GROUP BY r.account_id
      ORDER BY total DESC, r.account_id
      LIMIT ${limit}
    ) x
    JOIN accounts a ON a.account_id = x.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    ORDER BY x.total DESC, x.account_id
  `.execute(db);
  return r.rows.map(({ owner_uuid_bin, ...row }) => ({ ...row, account_id: Number(row.account_id), owner_uuid: bin(owner_uuid_bin), payments: Number(row.payments) }));
}

// ── ledger feed (gov transactions) ────────────────────────────────────────

function playerCondition(column: RawBuilder<unknown>, values: readonly string[]): Condition | null {
  const uuids = values.filter((v) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v));
  const names = values.filter((v) => !uuids.includes(v));
  const parts: Condition[] = [];
  if (uuids.length) parts.push(sql<SqlBool>`${column} IN (${sql.join(uuids.map(uuidToBin))})`);
  if (names.length) parts.push(sql<SqlBool>`${column} IN (SELECT player_uuid_bin FROM economy_players WHERE current_name IN (${sql.join(names)}))`);
  if (parts.length === 0) return null;
  return parts.length === 1 ? (parts[0] as Condition) : sql<SqlBool>`(${sql.join(parts, sql` OR `)})`;
}

/** Ledger feed filters. Posting-side ones stay in the per-account index walk; the rest join the txn. */
export const govLedgerColumnMap = defineColumnMap({
  table: govLedgerTable,
  filters: {
    direction: {
      compile: (f) => {
        const vals = new Set(f.values);
        if (vals.has('in') && vals.has('out')) return null;
        return vals.has('in') ? sql<SqlBool>`p.amount > 0` : sql<SqlBool>`p.amount < 0`;
      },
    },
    amount: { column: sql`ABS(p.amount)` },
    account: { column: sql`p.account_id` },
    initiator: { compile: (f) => playerCondition(sql`t.initiator_uuid_bin`, f.values) },
    authorized: { compile: (f) => (f.values[0] === 'true' ? sql<SqlBool>`t.authorizer_uuid_bin IS NOT NULL` : sql<SqlBool>`t.authorizer_uuid_bin IS NULL`) },
  },
  sorts: { time: sql`p.txn_id` },
});

/**
 * One page of postings on a set of accounts, newest first, after a keyset
 * cursor (`id` = txn_id). One UNION ALL branch per account, each walking
 * (account_id, txn_id) backwards and stopping at `limit` rows, so a busy
 * treasury never sorts its whole window. Filters on the txn are applied inside
 * each branch.
 */
export async function selectGovLedger(accountIds: readonly number[], window: TxnWindow, state: GovLedgerState, cursor: KeysetCursor | null, limit: number, now = new Date()): Promise<MovementRow[]> {
  const accountFilter = state.filters.find((f) => f.field === 'account');
  const scoped = accountFilter ? accountIds.filter((id) => accountFilter.values.includes(String(id))) : [...accountIds];
  if (scoped.length === 0) return [];
  // The account chip narrows the branch list instead of becoming a condition.
  const extra = compileTableState({ ...state, q: '', filters: state.filters.filter((f) => f.field !== 'account') }, govLedgerColumnMap, now).conditions;
  const needsTxn = state.filters.some((f) => f.field === 'initiator' || f.field === 'authorized');
  const branches = scoped.map((id) => sql`(
    SELECT p.txn_id, p.account_id, p.amount
    FROM ledger_postings p
    ${needsTxn ? sql`STRAIGHT_JOIN ledger_txns t ON t.txn_id = p.txn_id` : sql``}
    WHERE p.account_id = ${id} AND ${inWindow(sql`p.txn_id`, window)}
      ${cursor ? sql`AND p.txn_id < ${cursor.id}` : sql``}
      ${extra.length ? sql`AND ${sql.join(extra, sql` AND `)}` : sql``}
    ORDER BY p.txn_id DESC
    LIMIT ${limit})`);
  const inner = sql`SELECT * FROM (${sql.join(branches, sql` UNION ALL `)}) u ORDER BY u.txn_id DESC, u.account_id LIMIT ${limit}`;
  const r = await sql<RawMovement>`${movementSelect(inner)} ORDER BY p.txn_id DESC, p.account_id`.execute(db);
  return r.rows.map(toMovement);
}

// ── fines ─────────────────────────────────────────────────────────────────

export interface FineRow {
  fine_id: number;
  player_uuid: string | null;
  player_name: string | null;
  debtor_account_id: number | null;
  gov_account_id: number;
  gov_account_name: string | null;
  amount: string;
  reason: string | null;
  txn_id: number | null;
  issued_by_uuid: string | null;
  issued_by_name: string | null;
  issued_at: Date;
  revoked: number;
  revoked_at: Date | null;
}

export const finesColumnMap = defineColumnMap({
  table: finesTable,
  filters: {
    status: { column: sql`gf.revoked`, values: { active: 0, revoked: 1 } },
    amount: { column: sql`gf.amount` },
    issued: { column: sql`gf.issued_at` },
    issuer: { compile: (f) => playerCondition(sql`gf.issued_by_uuid_bin`, f.values) },
    player: { compile: (f) => playerCondition(sql`gf.player_uuid_bin`, f.values) },
    account: { column: sql`gf.gov_account_id` },
  },
  sorts: { issued: sql`gf.issued_at`, amount: sql`gf.amount` },
  tiebreak: { column: sql`gf.fine_id`, dir: 'desc' },
  search: (q) => sql<SqlBool>`gf.reason LIKE ${likeContains(q)}`,
});

export interface FinesQuery {
  accountIds: readonly number[];
  fromTs: string;
  toTs: string;
  state: FinesTableState;
  now?: Date;
}

/** Scope, range and chips. The scope is always the first condition. */
function finesWhere(q: FinesQuery) {
  const compiled = compileTableState(q.state, finesColumnMap, q.now);
  const conditions: Condition[] = [
    idIn(sql`gf.gov_account_id`, q.accountIds),
    sql<SqlBool>`gf.issued_at >= ${q.fromTs}`,
    sql<SqlBool>`gf.issued_at < ${q.toTs}`,
    ...compiled.conditions,
  ];
  return { where: sql`WHERE ${sql.join(conditions, sql` AND `)}`, orderBy: compiled.orderBy };
}

export async function listFines(q: FinesQuery, limit: number, offset: number): Promise<FineRow[]> {
  if (q.accountIds.length === 0) return [];
  const { where, orderBy } = finesWhere(q);
  const r = await sql<Omit<FineRow, 'player_uuid' | 'issued_by_uuid'> & { player_uuid_bin: Buffer | null; issued_by_uuid_bin: Buffer | null }>`
    SELECT gf.fine_id, gf.player_uuid_bin, pp.current_name AS player_name, gf.debtor_account_id, gf.gov_account_id,
           a.display_name AS gov_account_name, gf.amount, gf.reason, gf.txn_id, gf.issued_by_uuid_bin,
           ip.current_name AS issued_by_name, gf.issued_at, gf.revoked, gf.revoked_at
    FROM government_fines gf
    LEFT JOIN economy_players pp ON pp.player_uuid_bin = gf.player_uuid_bin
    LEFT JOIN economy_players ip ON ip.player_uuid_bin = gf.issued_by_uuid_bin
    LEFT JOIN accounts a ON a.account_id = gf.gov_account_id
    ${where}
    ${orderBy}
    LIMIT ${limit} OFFSET ${offset}
  `.execute(db);
  return r.rows.map(({ player_uuid_bin, issued_by_uuid_bin, ...row }) => ({
    ...row,
    fine_id: Number(row.fine_id),
    gov_account_id: Number(row.gov_account_id),
    debtor_account_id: row.debtor_account_id === null ? null : Number(row.debtor_account_id),
    txn_id: row.txn_id === null ? null : Number(row.txn_id),
    revoked: Number(row.revoked),
    player_uuid: bin(player_uuid_bin),
    issued_by_uuid: bin(issued_by_uuid_bin),
  }));
}

export interface FineSummaryRow { count: number; total: string; revoked: number; revoked_total: string }

/** Count, total and revocations for the current filters (one pass over the filtered rows). */
export async function summariseFines(q: FinesQuery): Promise<FineSummaryRow> {
  if (q.accountIds.length === 0) return { count: 0, total: '0.00', revoked: 0, revoked_total: '0.00' };
  const { where } = finesWhere(q);
  const r = await sql<{ n: number | string; total: string | null; revoked: number | string | null; revoked_total: string | null }>`
    SELECT COUNT(*) AS n, COALESCE(SUM(gf.amount), 0.00) AS total,
           COALESCE(SUM(gf.revoked), 0) AS revoked,
           COALESCE(SUM(CASE WHEN gf.revoked = 1 THEN gf.amount ELSE 0 END), 0.00) AS revoked_total
    FROM government_fines gf
    ${where}
  `.execute(db);
  const row = r.rows[0];
  return { count: Number(row?.n ?? 0), total: row?.total ?? '0.00', revoked: Number(row?.revoked ?? 0), revoked_total: row?.revoked_total ?? '0.00' };
}

export interface IssuerRow { issuer_uuid: string | null; issuer_name: string | null; fines: number; total: string }

/** Officials who issued the most fines under the current filters. */
export async function topFineIssuers(q: FinesQuery, limit: number): Promise<IssuerRow[]> {
  if (q.accountIds.length === 0) return [];
  const { where } = finesWhere(q);
  const r = await sql<{ issued_by_uuid_bin: Buffer | null; issuer_name: string | null; fines: number | string; total: string }>`
    SELECT x.issued_by_uuid_bin, ip.current_name AS issuer_name, x.fines, x.total
    FROM (
      SELECT gf.issued_by_uuid_bin, COUNT(*) AS fines, SUM(gf.amount) AS total
      FROM government_fines gf
      ${where}
      GROUP BY gf.issued_by_uuid_bin
      ORDER BY fines DESC, total DESC
      LIMIT ${limit}
    ) x
    LEFT JOIN economy_players ip ON ip.player_uuid_bin = x.issued_by_uuid_bin
    ORDER BY x.fines DESC, x.total DESC
  `.execute(db);
  return r.rows.map((row) => ({ issuer_uuid: bin(row.issued_by_uuid_bin), issuer_name: row.issuer_name, fines: Number(row.fines), total: row.total }));
}

/** Names for player chips (`issuer:in:<uuid>`), keyed by canonical uuid. */
export async function findPlayerNames(uuids: readonly string[]): Promise<Map<string, string>> {
  const valid = uuids.filter((u) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(u));
  if (valid.length === 0) return new Map();
  const r = await sql<{ player_uuid_bin: Buffer; current_name: string }>`
    SELECT player_uuid_bin, current_name FROM economy_players WHERE player_uuid_bin IN (${sql.join(valid.map(uuidToBin))})
  `.execute(db);
  return new Map(r.rows.map((row) => [binToUuid(row.player_uuid_bin), row.current_name]));
}

/** Player name typeahead for the issuer / player chips. Prefix match on economy_players.current_name. */
export async function findPlayersByPrefix(prefix: string, limit: number): Promise<{ value: string; label: string }[]> {
  const term = prefix.trim().slice(0, 32);
  if (!term) return [];
  const r = await sql<{ player_uuid_bin: Buffer; current_name: string }>`
    SELECT player_uuid_bin, current_name FROM economy_players
    WHERE current_name LIKE ${term.replace(/[\\%_]/g, (c) => `\\${c}`) + '%'}
    ORDER BY current_name LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({ value: binToUuid(row.player_uuid_bin), label: row.current_name }));
}

export interface FineMonthRow { count: number; total: string; revoked: number; issuers: number }

/** Fines summary for a report period (no chips). */
export async function summariseFinesForPeriod(accountIds: readonly number[], fromTs: string, toTs: string): Promise<FineMonthRow> {
  if (accountIds.length === 0) return { count: 0, total: '0.00', revoked: 0, issuers: 0 };
  const r = await sql<{ n: number | string; total: string | null; revoked: number | string | null; issuers: number | string }>`
    SELECT COUNT(*) AS n, COALESCE(SUM(CASE WHEN revoked = 0 THEN amount ELSE 0 END), 0.00) AS total,
           COALESCE(SUM(revoked), 0) AS revoked, COUNT(DISTINCT issued_by_uuid_bin) AS issuers
    FROM government_fines
    WHERE gov_account_id IN (${ids(accountIds)}) AND issued_at >= ${fromTs} AND issued_at < ${toTs}
  `.execute(db);
  const row = r.rows[0];
  return { count: Number(row?.n ?? 0), total: row?.total ?? '0.00', revoked: Number(row?.revoked ?? 0), issuers: Number(row?.issuers ?? 0) };
}

// ── explorer audit (who viewed in-scope data) ────────────────────────────

export interface ViewRow {
  audit_id: number;
  at: Date;
  actor_uuid: string | null;
  actor_name: string | null;
  actor_role: string | null;
  method: string;
  path: string;
  target_type: string | null;
  target_id: string | null;
  outcome: number;
}

export const govViewsColumnMap = defineColumnMap({
  table: govViewsTable,
  filters: {
    actor: { compile: (f) => playerCondition(sql`e.actor_uuid_bin`, f.values) },
    account: {
      compile: (f) => sql<SqlBool>`(e.target_type = 'account' AND e.target_id IN (${sql.join(f.values.map(String))}))`,
    },
  },
  sorts: { at: sql`e.audit_id` },
  search: (q) => sql<SqlBool>`(e.actor_name LIKE ${likeContains(q)} OR e.path LIKE ${likeContains(q)})`,
});

/**
 * explorer_audit rows about in-scope data: account views of the scope's
 * accounts, and /gov workspace pages (which only ever show in-scope data).
 * Bounded by idx_audit_at to [fromTs, toTs); newest first after a keyset
 * cursor on audit_id.
 */
export async function selectGovViews(accountIds: readonly number[], fromTs: string, toTs: string, state: GovViewsState, cursor: KeysetCursor | null, limit: number, now = new Date()): Promise<ViewRow[]> {
  if (accountIds.length === 0) return [];
  const compiled = compileTableState(state, govViewsColumnMap, now);
  const r = await sql<Omit<ViewRow, 'actor_uuid'> & { actor_uuid_bin: Buffer | null }>`
    SELECT e.audit_id, e.at, e.actor_uuid_bin, e.actor_name, e.actor_role, e.method, e.path, e.target_type, e.target_id, e.outcome
    FROM explorer_audit e
    WHERE e.at >= ${fromTs} AND e.at < ${toTs}
      AND ((e.target_type = 'account' AND e.target_id IN (${sql.join(accountIds.map(String))}))
        OR (e.target_type = 'global' AND (e.path = '/gov' OR e.path LIKE '/gov/%')))
      ${cursor ? sql`AND e.audit_id < ${cursor.id}` : sql``}
      ${compiled.conditions.length ? sql`AND ${sql.join(compiled.conditions, sql` AND `)}` : sql``}
    ORDER BY e.audit_id DESC
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map(({ actor_uuid_bin, ...row }) => ({ ...row, audit_id: Number(row.audit_id), outcome: Number(row.outcome), actor_uuid: bin(actor_uuid_bin) }));
}
