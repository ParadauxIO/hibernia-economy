import 'server-only';
import { sql, type Kysely, type RawBuilder } from 'kysely';
import { addDays, dayStart, monthEnd, monthStart } from './days';

// One recompute step per rollup table. Each rebuilds a single UTC day inside the
// caller's transaction: DELETE the day's rows, then one INSERT ... SELECT ...
// GROUP BY that the database executes. Nothing is aggregated in JavaScript.
//
// Every ledger read is bounded by idx_ledger_settle_time (or the V36 / sales
// time indexes) to [day 00:00, day+1 00:00) UTC, so a day costs that day's
// ~35k transactions and ~70k postings whatever the size of the history.

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Executor = Kysely<any>;

/** The tables the worker maintains, one coverage row and run-log job each. */
export const ROLLUP_JOBS = ['account_day', 'pair_month', 'economy_day', 'type_day', 'hour_heatmap', 'item_day', 'gov_recipient_day'] as const;
export type DayJob = (typeof ROLLUP_JOBS)[number];
/** Wealth is a point-in-time snapshot, taken for the current day only. */
export type RollupJob = DayJob | 'wealth';

interface Bounds { day: string; start: string; end: string }

function bounds(day: string): Bounds {
  return { day, start: dayStart(day), end: dayStart(addDays(day, 1)) };
}

function affected(result: { numAffectedRows?: bigint }): number {
  return Number(result.numAffectedRows ?? 0n);
}

async function run(trx: Executor, query: RawBuilder<unknown>): Promise<number> {
  return affected(await query.execute(trx));
}

// ── account_day ───────────────────────────────────────────────────────────
//
// Each posting goes wholly into one row, so inflow/outflow are the account's
// exact gross credits/debits for the day and inflow - outflow its net change
// (balanceSeries depends on that). The counterparty is the largest
// opposite-signed leg of the same transaction (ties: lowest posting_id). One
// window per sign picks that leg's posting_id in a single pass over the day's
// legs; its account is then a primary-key lookup. (Carrying every counterparty
// attribute through the windows was ~2x slower at 35k transactions a day.)
async function accountDay(trx: Executor, b: Bounds): Promise<number> {
  await sql`DELETE FROM explorer_rollup_account_day WHERE day = ${b.day}`.execute(trx);
  return run(trx, sql`
    INSERT INTO explorer_rollup_account_day
      (account_id, day, plugin_key, tax_kind, counterparty_type, is_internal, inflow, outflow, txn_count)
    SELECT l.account_id, ${b.day}, l.plugin_key, l.tax_kind,
           COALESCE(ca.account_type, '') AS counterparty_type,
           CASE WHEN cp.account_id IS NULL THEN 0
                WHEN cp.account_id = l.account_id THEN 1
                WHEN l.account_type = 'BUSINESS' AND ca.account_type = 'BUSINESS' THEN COALESCE(l.firm_id = cf.firm_id, 0)
                WHEN l.account_type = ca.account_type AND l.account_type IN ('PERSONAL', 'GOVERNMENT')
                  THEN l.owner_uuid_bin = ca.owner_uuid_bin
                ELSE 0 END AS is_internal,
           SUM(CASE WHEN l.amount > 0 THEN l.amount ELSE 0 END),
           SUM(CASE WHEN l.amount < 0 THEN -l.amount ELSE 0 END),
           COUNT(DISTINCT l.txn_id)
    FROM (
      SELECT p.txn_id, p.account_id, p.amount,
             COALESCE(t.plugin_system, '') AS plugin_key,
             CASE WHEN t.tax_kind IS NOT NULL THEN t.tax_kind
                  WHEN EXISTS (SELECT 1 FROM government_fines f WHERE f.txn_id = t.txn_id) THEN 'fine'
                  WHEN EXISTS (SELECT 1 FROM government_fines f WHERE f.revoke_txn_id = t.txn_id) THEN 'fine'
                  ELSE '' END AS tax_kind,
             a.account_type, a.owner_uuid_bin, fa.firm_id,
             CASE WHEN p.amount > 0 THEN FIRST_VALUE(p.posting_id) OVER (PARTITION BY p.txn_id ORDER BY p.amount ASC, p.posting_id)
                  WHEN p.amount < 0 THEN FIRST_VALUE(p.posting_id) OVER (PARTITION BY p.txn_id ORDER BY p.amount DESC, p.posting_id)
                  END AS counter_posting
      FROM ledger_txns t FORCE INDEX (idx_ledger_settle_time)
      STRAIGHT_JOIN ledger_postings p ON p.txn_id = t.txn_id
      STRAIGHT_JOIN accounts a ON a.account_id = p.account_id
      LEFT JOIN firm_accounts fa ON fa.account_id = p.account_id AND fa.removed_at IS NULL
      WHERE t.settlement_time >= ${b.start} AND t.settlement_time < ${b.end}
    ) l
    LEFT JOIN ledger_postings cp ON cp.posting_id = l.counter_posting AND SIGN(cp.amount) = -SIGN(l.amount)
    LEFT JOIN accounts ca ON ca.account_id = cp.account_id
    LEFT JOIN firm_accounts cf ON cf.account_id = cp.account_id AND cf.removed_at IS NULL
    GROUP BY l.account_id, l.plugin_key, l.tax_kind, counterparty_type, is_internal
  `);
}

// Government recipients retain the raw plugin key; category changes need no
// backfill. Distinct debit accounts avoid multiplying credits when a transaction
// has several debit postings for the same source account.
async function govRecipientDay(trx: Executor, b: Bounds): Promise<number> {
  await sql`DELETE FROM explorer_rollup_gov_recipient_day WHERE day = ${b.day}`.execute(trx);
  return run(trx, sql`
    INSERT INTO explorer_rollup_gov_recipient_day
      (account_id, day, plugin_key, recipient_id, total, payments, last_paid)
    SELECT source.account_id, ${b.day}, COALESCE(t.plugin_system, ''), r.account_id,
           SUM(r.amount), COUNT(DISTINCT t.txn_id), MAX(t.settlement_time)
    FROM ledger_txns t FORCE INDEX (idx_ledger_settle_time)
    STRAIGHT_JOIN (
      SELECT DISTINCT p.txn_id, p.account_id
      FROM ledger_txns bounded FORCE INDEX (idx_ledger_settle_time)
      STRAIGHT_JOIN ledger_postings p ON p.txn_id = bounded.txn_id
      JOIN accounts a ON a.account_id = p.account_id
      WHERE bounded.settlement_time >= ${b.start} AND bounded.settlement_time < ${b.end}
        AND p.amount < 0 AND a.account_type = 'GOVERNMENT'
    ) source ON source.txn_id = t.txn_id
    STRAIGHT_JOIN ledger_postings r ON r.txn_id = t.txn_id AND r.amount > 0
    WHERE t.settlement_time >= ${b.start} AND t.settlement_time < ${b.end}
    GROUP BY source.account_id, COALESCE(t.plugin_system, ''), r.account_id
  `);
}

// ── pair_month (via pair_day) ─────────────────────────────────────────────
//
// pair_day holds the day's pairs; the month row for every account touched by
// the day (before or after the rebuild) is then re-summed from pair_day, so a
// recompute never rescans a month of ledger. Pairwise amounts are
// LEAST(|own leg|, |other leg|), exact for two-leg transfers.
//
// The worker's DB user has DML only (no CREATE TEMPORARY TABLES, no DDL), so
// the touched-account set is held in Node rather than in a temporary table. It
// is bounded by the accounts active in one day and goes back to the database in
// IN lists of at most PAIR_ACCOUNT_CHUNK ids, so no statement grows with it.
const PAIR_ACCOUNT_CHUNK = 1000;

async function pairDayAccounts(trx: Executor, day: string): Promise<number[]> {
  const r = await sql<{ account_id: number }>`
    SELECT DISTINCT account_id FROM explorer_rollup_pair_day WHERE day = ${day}`.execute(trx);
  return r.rows.map((row) => Number(row.account_id));
}

async function pairMonth(trx: Executor, b: Bounds): Promise<number> {
  const month = monthStart(b.day);
  const last = monthEnd(b.day);
  const touched = new Set(await pairDayAccounts(trx, b.day));
  await sql`DELETE FROM explorer_rollup_pair_day WHERE day = ${b.day}`.execute(trx);
  const rows = await run(trx, sql`
    INSERT INTO explorer_rollup_pair_day (account_id, day, counterparty_id, inflow, outflow, txn_count)
    SELECT me.account_id, ${b.day}, other.account_id,
           SUM(CASE WHEN me.amount > 0 THEN LEAST(me.amount, -other.amount) ELSE 0 END),
           SUM(CASE WHEN me.amount < 0 THEN LEAST(-me.amount, other.amount) ELSE 0 END),
           COUNT(DISTINCT me.txn_id)
    FROM ledger_txns t FORCE INDEX (idx_ledger_settle_time)
    STRAIGHT_JOIN ledger_postings me ON me.txn_id = t.txn_id
    STRAIGHT_JOIN ledger_postings other ON other.txn_id = t.txn_id
      AND other.account_id <> me.account_id AND SIGN(other.amount) = -SIGN(me.amount)
    WHERE t.settlement_time >= ${b.start} AND t.settlement_time < ${b.end} AND me.amount <> 0
    GROUP BY me.account_id, other.account_id
  `);
  for (const id of await pairDayAccounts(trx, b.day)) touched.add(id);
  const accounts = [...touched].sort((x, y) => x - y);
  for (let i = 0; i < accounts.length; i += PAIR_ACCOUNT_CHUNK) {
    const ids = sql.join(accounts.slice(i, i + PAIR_ACCOUNT_CHUNK));
    await sql`DELETE FROM explorer_rollup_pair_month WHERE month = ${month} AND account_id IN (${ids})`.execute(trx);
    await sql`
      INSERT INTO explorer_rollup_pair_month (account_id, counterparty_id, month, inflow, outflow, txn_count)
      SELECT pd.account_id, pd.counterparty_id, ${month}, SUM(pd.inflow), SUM(pd.outflow), SUM(pd.txn_count)
      FROM explorer_rollup_pair_day pd
      WHERE pd.account_id IN (${ids}) AND pd.day BETWEEN ${month} AND ${last}
      GROUP BY pd.account_id, pd.counterparty_id
    `.execute(trx);
  }
  return rows;
}

/**
 * Bridge-pair CTEs for outgoing legs settled in [start, end), ported from
 * the live query of PAR-159 (now the test oracle in
 * test/integration/reference/liveBridgeVolume.ts) with an explicit upper bound instead of
 * NOW(). The bound cannot change which pairs match: a posting settled after
 * `end` that sits between a credit and its debit is still found by
 * bridge_blocked, which probes every posting in the SYSTEM account.
 */
export function bridgePairsBetween(start: string, end: string) {
  return sql`
    bridge_postings AS (
      SELECT p.posting_id,
             LAG(p.posting_id) OVER (PARTITION BY p.account_id ORDER BY p.posting_id) AS previous_id
      FROM ledger_txns t FORCE INDEX (idx_ledger_settle_time)
      STRAIGHT_JOIN ledger_postings p ON p.txn_id = t.txn_id
      STRAIGHT_JOIN accounts a ON a.account_id = p.account_id AND a.account_type = 'SYSTEM'
      WHERE t.settlement_time >= ${start} - INTERVAL 1 SECOND AND t.settlement_time < ${end}
    ),
    bridge_candidates AS (
      SELECT DISTINCT credit.txn_id AS incoming_txn, debit.txn_id AS outgoing_txn,
             credit.posting_id AS credit_id, debit.posting_id AS debit_id, debit.account_id AS system_acct
      FROM bridge_postings ordered
      STRAIGHT_JOIN ledger_postings debit ON debit.posting_id = ordered.posting_id AND debit.amount < 0
      STRAIGHT_JOIN ledger_txns outgoing ON outgoing.txn_id = debit.txn_id
      STRAIGHT_JOIN ledger_postings credit ON credit.posting_id = ordered.previous_id
      STRAIGHT_JOIN ledger_txns incoming ON incoming.txn_id = credit.txn_id
      STRAIGHT_JOIN ledger_postings payer ON payer.txn_id = credit.txn_id
        AND payer.account_id != credit.account_id AND payer.amount = -credit.amount
      STRAIGHT_JOIN accounts source ON source.account_id = payer.account_id AND source.account_type != 'SYSTEM'
      STRAIGHT_JOIN ledger_postings payee ON payee.txn_id = debit.txn_id
        AND payee.account_id != debit.account_id AND payee.amount = -debit.amount
      STRAIGHT_JOIN accounts destination ON destination.account_id = payee.account_id AND destination.account_type != 'SYSTEM'
      WHERE outgoing.settlement_time >= ${start} AND outgoing.settlement_time < ${end}
        AND credit.amount = -debit.amount
        AND incoming.settlement_time BETWEEN outgoing.settlement_time - INTERVAL 1 SECOND AND outgoing.settlement_time
        AND BINARY incoming.plugin_system = BINARY outgoing.plugin_system
        AND BINARY (CASE WHEN BINARY incoming.message LIKE 'Vault withdraw (%)'
          THEN CONCAT('Vault deposit ', SUBSTRING(incoming.message, 16)) ELSE incoming.message END) = BINARY outgoing.message
        AND (SELECT COUNT(*) FROM ledger_postings p WHERE p.txn_id = credit.txn_id) = 2
        AND (SELECT COUNT(*) FROM ledger_postings p WHERE p.txn_id = debit.txn_id) = 2
    ),
    bridge_blocked AS (
      SELECT DISTINCT c.outgoing_txn
      FROM bridge_candidates c
      STRAIGHT_JOIN ledger_postings intervening FORCE INDEX (PRIMARY)
        ON intervening.posting_id > c.credit_id AND intervening.posting_id < c.debit_id
        AND intervening.account_id = c.system_acct
    ),
    bridge_pairs AS (
      SELECT c.* FROM bridge_candidates c
      LEFT JOIN bridge_blocked b ON b.outgoing_txn = c.outgoing_txn
      WHERE b.outgoing_txn IS NULL
    )
  `;
}

// ── economy_day ───────────────────────────────────────────────────────────
//
// The adjusted figures are getDailyVolume's for one day: drop each bridge
// pair's incoming leg, counting the pair on its outgoing leg. An incoming leg
// settled in the last second of the day can pair with an outgoing leg in the
// first second of the next, so pairs are searched up to end + 1 second.
async function economyDay(trx: Executor, b: Bounds): Promise<number> {
  await sql`DELETE FROM explorer_rollup_economy_day WHERE day = ${b.day}`.execute(trx);
  return run(trx, sql`
    INSERT INTO explorer_rollup_economy_day
      (day, txn_count, volume, raw_txn_count, raw_volume, bridge_pairs, active_players, new_players)
    WITH ${bridgePairsBetween(b.start, b.end.replace(/00:00:00$/, '00:00:01'))}
    SELECT ${b.day}, adjusted.n, adjusted.volume, raw.n, raw.volume, raw.n - adjusted.n,
           (SELECT COUNT(DISTINCT a.owner_uuid_bin)
              FROM ledger_txns t FORCE INDEX (idx_ledger_settle_time)
              STRAIGHT_JOIN ledger_postings p ON p.txn_id = t.txn_id
              STRAIGHT_JOIN accounts a ON a.account_id = p.account_id AND a.account_type = 'PERSONAL'
              WHERE t.settlement_time >= ${b.start} AND t.settlement_time < ${b.end}),
           (SELECT COUNT(*) FROM economy_players
              WHERE first_seen >= ${b.start} AND first_seen < ${b.end})
    FROM (SELECT COUNT(*) AS n, COALESCE(SUM(volume), 0.00) AS volume
            FROM ledger_txn_volume v
            WHERE v.settlement_time >= ${b.start} AND v.settlement_time < ${b.end}
              AND NOT EXISTS (SELECT 1 FROM bridge_pairs bp WHERE bp.incoming_txn = v.txn_id)) adjusted
    CROSS JOIN (SELECT COUNT(*) AS n, COALESCE(SUM(volume), 0.00) AS volume
            FROM ledger_txn_volume v
            WHERE v.settlement_time >= ${b.start} AND v.settlement_time < ${b.end}) raw
  `);
}

// ── type_day ──────────────────────────────────────────────────────────────
async function typeDay(trx: Executor, b: Bounds): Promise<number> {
  await sql`DELETE FROM explorer_rollup_type_day WHERE day = ${b.day}`.execute(trx);
  return run(trx, sql`
    INSERT INTO explorer_rollup_type_day (day, account_type, plugin_key, inflow, outflow)
    SELECT ${b.day}, a.account_type, COALESCE(t.plugin_system, ''),
           SUM(CASE WHEN p.amount > 0 THEN p.amount ELSE 0 END),
           SUM(CASE WHEN p.amount < 0 THEN -p.amount ELSE 0 END)
    FROM ledger_txns t FORCE INDEX (idx_ledger_settle_time)
    STRAIGHT_JOIN ledger_postings p ON p.txn_id = t.txn_id
    STRAIGHT_JOIN accounts a ON a.account_id = p.account_id
    WHERE t.settlement_time >= ${b.start} AND t.settlement_time < ${b.end}
    GROUP BY a.account_type, COALESCE(t.plugin_system, '')
  `);
}

// ── hour_heatmap ──────────────────────────────────────────────────────────
async function hourHeatmap(trx: Executor, b: Bounds): Promise<number> {
  await sql`DELETE FROM explorer_rollup_hour_heatmap WHERE day = ${b.day}`.execute(trx);
  return run(trx, sql`
    INSERT INTO explorer_rollup_hour_heatmap (day, weekday, hour, txn_count)
    SELECT ${b.day}, WEEKDAY(${b.day}), HOUR(settlement_time), COUNT(*)
    FROM ledger_txns FORCE INDEX (idx_ledger_settle_time)
    WHERE settlement_time >= ${b.start} AND settlement_time < ${b.end}
    GROUP BY HOUR(settlement_time)
  `);
}

// ── item_day ──────────────────────────────────────────────────────────────
async function itemDay(trx: Executor, b: Bounds): Promise<number> {
  await sql`DELETE FROM explorer_rollup_item_day WHERE day = ${b.day}`.execute(trx);
  return run(trx, sql`
    INSERT INTO explorer_rollup_item_day
      (item_key, day, direction, qty, volume, trades, min_unit_price, max_unit_price, open_unit_price, close_unit_price)
    SELECT item_key, ${b.day}, direction, SUM(quantity), SUM(total_price), COUNT(*),
           MIN(unit_price), MAX(unit_price), MIN(open_price), MIN(close_price)
    FROM (
      SELECT s.item_key, s.direction, s.quantity, s.total_price, s.unit_price,
             FIRST_VALUE(s.unit_price) OVER (PARTITION BY s.item_key, s.direction
                                             ORDER BY s.occurred_at, s.sale_id) AS open_price,
             FIRST_VALUE(s.unit_price) OVER (PARTITION BY s.item_key, s.direction
                                             ORDER BY s.occurred_at DESC, s.sale_id DESC) AS close_price
      FROM chestshop_sale s FORCE INDEX (idx_cs_time)
      WHERE s.occurred_at >= ${b.start} AND s.occurred_at < ${b.end}
    ) sales
    GROUP BY item_key, direction
  `);
}

const STEPS: Record<DayJob, (trx: Executor, b: Bounds) => Promise<number>> = {
  account_day: accountDay,
  gov_recipient_day: govRecipientDay,
  pair_month: pairMonth,
  economy_day: economyDay,
  type_day: typeDay,
  hour_heatmap: hourHeatmap,
  item_day: itemDay,
};

/**
 * Extend a job's coverage by `day` when it is adjacent to (or inside) the
 * covered range. A day beyond a gap leaves coverage unchanged, so the range
 * stays contiguous; the worker fills gaps oldest-first.
 */
export async function recordCoverage(trx: Executor, job: RollupJob, day: string): Promise<void> {
  await sql`
    INSERT INTO explorer_rollup_coverage (job, from_day, to_day, updated_at)
    VALUES (${job}, ${day}, ${day}, CURRENT_TIMESTAMP(3))
    ON DUPLICATE KEY UPDATE
      from_day = IF(VALUES(from_day) = from_day - INTERVAL 1 DAY, VALUES(from_day), from_day),
      to_day = IF(VALUES(to_day) = to_day + INTERVAL 1 DAY, VALUES(to_day), to_day),
      updated_at = CURRENT_TIMESTAMP(3)
  `.execute(trx);
}

/** Rebuild one job's rows for one UTC day, and its coverage, in one transaction. */
export async function recomputeDay(db: Executor, job: DayJob, day: string): Promise<number> {
  return db.transaction().setIsolationLevel('read committed').execute(async (trx) => {
    const rows = await STEPS[job](trx, bounds(day));
    await recordCoverage(trx, job, day);
    return rows;
  });
}

// ── wealth snapshot ───────────────────────────────────────────────────────

const BUCKET = sql`CASE
  WHEN abm.balance < 0      THEN 'neg'
  WHEN abm.balance < 100    THEN '0_100'
  WHEN abm.balance < 500    THEN '100_500'
  WHEN abm.balance < 1000   THEN '500_1k'
  WHEN abm.balance < 2500   THEN '1k_2k5'
  WHEN abm.balance < 5000   THEN '2k5_5k'
  WHEN abm.balance < 10000  THEN '5k_10k'
  WHEN abm.balance < 25000  THEN '10k_25k'
  WHEN abm.balance < 50000  THEN '25k_50k'
  WHEN abm.balance < 100000 THEN '50k_100k'
  ELSE '100k_plus' END`;

/**
 * Snapshot current wealth as `day`'s row (the last snapshot of a day stands for
 * that day). Gini and top shares use lib/derived.ts's definitions over
 * positive active PERSONAL balances; buckets match getBalanceDistribution.
 */
export async function snapshotWealth(db: Executor, day: string): Promise<number> {
  return db.transaction().setIsolationLevel('read committed').execute(async (trx) => {
    await sql`DELETE FROM explorer_snapshot_wealth_day WHERE day = ${day}`.execute(trx);
    const rows = await run(trx, sql`
      INSERT INTO explorer_snapshot_wealth_day
        (day, taken_at, supply_personal, supply_business, supply_government, supply_system,
         personal_holders, gini, top1_share, top10_share, holder_buckets)
      WITH supply AS (
        SELECT a.account_type, COALESCE(SUM(abm.balance), 0.00) AS total
        FROM accounts a JOIN account_balances_mat abm ON abm.account_id = a.account_id
        WHERE a.is_archived = 0
        GROUP BY a.account_type
      ),
      holders AS (
        SELECT abm.balance AS x,
               ROW_NUMBER() OVER (ORDER BY abm.balance, abm.account_id) AS asc_rank,
               ROW_NUMBER() OVER (ORDER BY abm.balance DESC, abm.account_id) AS desc_rank,
               COUNT(*) OVER () AS n
        FROM account_balances_mat abm JOIN accounts a ON a.account_id = abm.account_id
        WHERE a.is_archived = 0 AND a.account_type = 'PERSONAL' AND abm.balance > 0
      ),
      concentration AS (
        SELECT COUNT(*) AS n, COALESCE(SUM(x), 0) AS total,
               COALESCE(SUM(asc_rank * x), 0) AS weighted,
               COALESCE(SUM(CASE WHEN desc_rank <= GREATEST(1, CEIL(n * 0.01)) THEN x ELSE 0 END), 0) AS top1,
               COALESCE(SUM(CASE WHEN desc_rank <= GREATEST(1, CEIL(n * 0.10)) THEN x ELSE 0 END), 0) AS top10
        FROM holders
      ),
      buckets AS (
        SELECT ${BUCKET} AS bucket, COUNT(*) AS n
        FROM account_balances_mat abm JOIN accounts a ON a.account_id = abm.account_id
        WHERE a.is_archived = 0 AND a.account_type = 'PERSONAL' AND abm.balance <> 0
        GROUP BY bucket
      )
      SELECT ${day}, CURRENT_TIMESTAMP,
             COALESCE((SELECT total FROM supply WHERE account_type = 'PERSONAL'), 0),
             COALESCE((SELECT total FROM supply WHERE account_type = 'BUSINESS'), 0),
             COALESCE((SELECT total FROM supply WHERE account_type = 'GOVERNMENT'), 0),
             COALESCE((SELECT total FROM supply WHERE account_type = 'SYSTEM'), 0),
             c.n,
             CASE WHEN c.total = 0 THEN 0 ELSE ROUND((2 * c.weighted) / (c.n * c.total) - (c.n + 1) / c.n, 6) END,
             CASE WHEN c.total = 0 THEN 0 ELSE ROUND(c.top1 / c.total, 6) END,
             CASE WHEN c.total = 0 THEN 0 ELSE ROUND(c.top10 / c.total, 6) END,
             COALESCE((SELECT JSON_OBJECTAGG(bucket, n) FROM buckets), '{}')
      FROM concentration c
    `);
    // Snapshots are never backfilled, so coverage spans first to latest
    // snapshot; a day the worker was down for simply has no row.
    await sql`
      INSERT INTO explorer_rollup_coverage (job, from_day, to_day, updated_at)
      VALUES ('wealth', ${day}, ${day}, CURRENT_TIMESTAMP(3))
      ON DUPLICATE KEY UPDATE from_day = LEAST(from_day, VALUES(from_day)),
        to_day = GREATEST(to_day, VALUES(to_day)), updated_at = CURRENT_TIMESTAMP(3)
    `.execute(trx);
    return rows;
  });
}

// ── housekeeping ──────────────────────────────────────────────────────────

/**
 * The pair_day rows that can be deleted: days of months whose pair_month rows
 * can no longer change. That is every month older than the previous calendar
 * month (the nightly 7-day recompute never reaches further back) that lies
 * entirely inside pair_month's coverage. A partly backfilled month keeps its
 * days until the backfill completes it. Returns [from, before) or null.
 */
export function pairDayPruneRange(today: string, coveredFrom: string | null): { from: string; before: string } | null {
  if (!coveredFrom) return null;
  const previousMonth = monthStart(addDays(monthStart(today), -1));
  const firstCompleteMonth = coveredFrom === monthStart(coveredFrom) ? coveredFrom : addDays(monthEnd(coveredFrom), 1);
  return firstCompleteMonth < previousMonth ? { from: firstCompleteMonth, before: previousMonth } : null;
}

export async function prunePairDays(db: Executor, range: { from: string; before: string }): Promise<number> {
  let total = 0;
  for (;;) {
    const n = affected(await sql`DELETE FROM explorer_rollup_pair_day
      WHERE day >= ${range.from} AND day < ${range.before} LIMIT 20000`.execute(db));
    total += n;
    if (n < 20000) return total;
  }
}

export async function pruneRuns(db: Executor, keepDays: number): Promise<number> {
  let total = 0;
  for (;;) {
    const n = affected(await sql`DELETE FROM explorer_rollup_run
      WHERE started_at < CURRENT_TIMESTAMP - INTERVAL ${keepDays} DAY LIMIT 20000`.execute(db));
    total += n;
    if (n < 20000) return total;
  }
}

/** First UTC day with source rows for a job, or null when the source is empty. */
export async function earliestDay(db: Executor, job: DayJob): Promise<string | null> {
  const r = job === 'item_day'
    ? await sql<{ d: string | null }>`SELECT DATE_FORMAT(MIN(occurred_at), '%Y-%m-%d') AS d FROM chestshop_sale`.execute(db)
    : await sql<{ d: string | null }>`SELECT DATE_FORMAT(MIN(settlement_time), '%Y-%m-%d') AS d FROM ledger_txns`.execute(db);
  return r.rows[0]?.d ?? null;
}

export interface CoverageRow { job: string; from_day: string; to_day: string; history_complete: number }

export async function readCoverage(db: Executor): Promise<Map<string, CoverageRow>> {
  const r = await sql<CoverageRow>`
    SELECT job, DATE_FORMAT(from_day, '%Y-%m-%d') AS from_day, DATE_FORMAT(to_day, '%Y-%m-%d') AS to_day,
           history_complete
    FROM explorer_rollup_coverage`.execute(db);
  return new Map(r.rows.map((row) => [row.job, { ...row, history_complete: Number(row.history_complete) }]));
}

/** Mark a job's history complete: its coverage reaches the first source day. */
export async function markHistoryComplete(db: Executor, job: DayJob): Promise<void> {
  await sql`UPDATE explorer_rollup_coverage SET history_complete = 1 WHERE job = ${job}`.execute(db);
}

export async function logRun(db: Executor, entry: {
  job: string; day: string | null; startedAt: Date; finishedAt: Date; rows: number; error: string | null;
}): Promise<void> {
  await sql`INSERT INTO explorer_rollup_run (job, day, started_at, finished_at, rows_written, error)
    VALUES (${entry.job}, ${entry.day}, ${entry.startedAt}, ${entry.finishedAt}, ${entry.rows},
            ${entry.error === null ? null : entry.error.slice(0, 1000)})`.execute(db);
}

/**
 * Jobs with a successful step for `day` that started at or after `since`
 * (the run log's started_at index bounds the read).
 */
export async function jobsRecomputedSince(db: Executor, day: string, since: Date): Promise<Set<string>> {
  const r = await sql<{ job: string }>`SELECT DISTINCT job FROM explorer_rollup_run
    WHERE started_at >= ${since} AND day = ${day} AND error IS NULL`.execute(db);
  return new Set(r.rows.map((row) => row.job));
}

export async function lastNightly(db: Executor): Promise<string | null> {
  const r = await sql<{ d: string | null }>`SELECT DATE_FORMAT(MAX(day), '%Y-%m-%d') AS d FROM explorer_rollup_run
    WHERE job = 'nightly' AND error IS NULL`.execute(db);
  return r.rows[0]?.d ?? null;
}
