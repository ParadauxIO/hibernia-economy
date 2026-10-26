import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';
import mysql from 'mysql2/promise';
import { createPool } from 'mysql2';
import { Kysely, MysqlDialect, sql } from 'kysely';

// Vitest runs test files in parallel and sql.test.ts resets the shared test
// database, so this suite builds its own: DB_NAME + '_rollup'. Set before
// lib/db.ts and lib/rollup/db.ts read it at import time.
vi.hoisted(() => {
  process.env.DB_NAME = `${process.env.DB_NAME ?? 'economy_test'}_rollup`;
});

import { HAS_DB, resetDb, testDbOptions } from './db';
import { db } from '@/lib/db';
import { rollupDb, closeRollupDb, ROLLUP_SESSION_SQL } from '@/lib/rollup/db';
import { addDays, eachDay, monthEnd, monthStart, utcDay } from '@/lib/rollup/days';
import { LeaderLease } from '@/lib/rollup/lease';
import { ROLLUP_JOBS, recomputeDay, snapshotWealth, type DayJob } from '@/lib/rollup/steps';
import { RollupWorker } from '@/lib/rollup/worker';
import { metricsText } from '@/lib/metrics';
import { getDailyVolume, getActivityComparison } from '@/lib/sql/health';
import { getPersonalBalances, getBalanceDistribution } from '@/lib/sql/stats';
import { gini, topShare } from '@/lib/derived';
import {
  accountDaySeries, balanceSeries, categoryTotals, counterparties, economyDays, heatmap, isCovered, itemDays,
  rollupCoverage, rollupHealth, typeDays, wealthSnapshots,
} from '@/lib/services/rollup';

const d = HAS_DB ? describe : describe.skip;

// Fixture days: complete UTC days inside every rolling window used below.
const TODAY = utcDay();
const D1 = addDays(TODAY, -5);
const D2 = addDays(TODAY, -4);
const OLD = addDays(TODAY, -40); // a previous month, for backfill and pair_month
const at = (day: string, time: string) => `${day} ${time}`;

let nextTxn = 5000;
async function txn(when: string, plugin: string | null, message: string, legs: [number, string][]): Promise<number> {
  const id = ++nextTxn;
  await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, plugin_system)
    VALUES (${id}, ${when}, ${message}, UNHEX(REPEAT('00', 16)), ${plugin})`.execute(db);
  for (const [account, amount] of legs) {
    await sql`INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (${id}, ${account}, ${amount})`.execute(db);
  }
  return id;
}

async function sale(when: string, item: string, direction: 'BUY' | 'SELL', qty: number, unit: string, total: string) {
  await sql`INSERT INTO chestshop_sale (occurred_at, direction, customer_uuid_bin, shop_account_id, shop_account_type,
      is_admin_shop, material, item_key, item_name, item_custom, quantity, unit_price, total_price)
    VALUES (${when}, ${direction}, UNHEX(REPEAT('0B', 16)), 3, 'BUSINESS', 0, ${item}, ${item}, ${item}, 0, ${qty}, ${unit}, ${total})`.execute(db);
}

async function seedFixtures() {
  // 20: Acme's second firm account; 21: a SYSTEM Vault bridge; 22: a second
  // City Hall account (same owner as #5).
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES
    (20, 'BUSINESS', UNHEX('0000000000000000000000000000A1CE'), 'Acme Payroll'),
    (21, 'SYSTEM', UNHEX(REPEAT('21', 16)), 'Essentials bridge'),
    (22, 'GOVERNMENT', UNHEX('0000000000000000000000000000601C'), 'City Hall Reserve')`.execute(db);
  await sql`INSERT INTO account_balances_mat (account_id, balance) VALUES (20, 0), (21, 0), (22, 0)`.execute(db);
  await sql`INSERT INTO firm_accounts (firm_id, account_id) VALUES (1, 20)`.execute(db);

  // D1
  await txn(at(D1, '09:15:00'), 'treasury', 'Personal balance tax (24h 0m @ 1.0%/wk)', [[1, '-12.34'], [5, '12.34']]);
  const fine = await txn(at(D1, '10:00:00'), 'treasury', 'Fine: speeding', [[2, '-50.00'], [5, '50.00']]);
  await sql`INSERT INTO government_fines (player_uuid_bin, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin)
    VALUES (UNHEX('00000000000000000000000000000B0B'), 5, 50.00, 'speeding', ${fine}, UNHEX('0000000000000000000000000000A1CE'))`.execute(db);
  // A three-leg ChestShop sale: the buyer's counterparty is the larger leg.
  await txn(at(D1, '11:30:00'), 'ChestShop', 'sale', [[2, '-100.00'], [3, '95.00'], [5, '5.00']]);
  await txn(at(D1, '12:00:00'), 'BusinessPlugin', 'move funds', [[3, '-40.00'], [20, '40.00']]);
  await txn(at(D1, '12:30:00'), 'Treasury-Core', 'Admin transfer: reward', [[5, '-25.00'], [1, '25.00']]);
  await txn(at(D1, '13:00:00'), 'Treasury-Core', 'pay bob', [[1, '-7.50'], [2, '7.50']]);
  await txn(at(D1, '13:30:00'), 'treasury', 'reserve', [[5, '-30.00'], [22, '30.00']]);
  await txn(at(D1, '14:00:00'), 'WeirdPlugin', 'mystery', [[4, '-1.00'], [1, '1.00']]);
  await txn(at(D1, '23:10:00'), null, 'Payment from Alice to Bob', [[1, '-3.00'], [2, '3.00']]);
  // A bridge pair (Essentials /pay) inside D1, and one straddling midnight:
  // its incoming leg settles in D1's last second, its outgoing leg in D2's first.
  await txn(at(D1, '15:00:00'), 'Essentials', 'Vault withdraw (Essentials)', [[1, '-8.00'], [21, '8.00']]);
  await txn(at(D1, '15:00:01'), 'Essentials', 'Vault deposit (Essentials)', [[21, '-8.00'], [2, '8.00']]);
  await txn(at(D1, '23:59:59'), 'Essentials', 'Vault withdraw (Essentials)', [[2, '-4.25'], [21, '4.25']]);
  await txn(at(D2, '00:00:00'), 'Essentials', 'Vault deposit (Essentials)', [[21, '-4.25'], [1, '4.25']]);
  // D2
  await txn(at(D2, '08:00:00'), 'treasury-salary', 'Salary', [[5, '-60.00'], [1, '60.00']]);
  await txn(at(D2, '18:45:00'), 'ChestShop', 'sale', [[1, '-20.00'], [3, '20.00']]);
  // OLD, a previous month
  await txn(at(OLD, '12:00:00'), 'Treasury-Core', 'pay', [[2, '-11.00'], [1, '11.00']]);
  await txn(at(addDays(OLD, 1), '12:00:00'), 'Treasury-Core', 'pay', [[1, '-2.00'], [2, '2.00']]);

  await sale(at(D1, '10:00:00'), 'DIAMOND', 'BUY', 4, '5.0000', '20.00');
  await sale(at(D1, '11:00:00'), 'DIAMOND', 'BUY', 2, '7.5000', '15.00');
  await sale(at(D1, '12:00:00'), 'DIAMOND', 'BUY', 1, '6.2500', '6.25');
  await sale(at(D1, '12:00:00'), 'DIAMOND', 'SELL', 3, '4.0000', '12.00');
  await sale(at(D2, '09:00:00'), 'IRON_INGOT', 'BUY', 10, '1.0000', '10.00');

  await sql`INSERT INTO economy_players (player_uuid_bin, current_name, first_seen)
    VALUES (UNHEX(REPEAT('5E', 16)), 'Newbie', ${at(D1, '16:00:00')})`.execute(db);
}

const ROLLUP_TABLES = [
  'explorer_rollup_account_day', 'explorer_rollup_pair_month', 'explorer_rollup_pair_day', 'explorer_rollup_economy_day',
  'explorer_rollup_type_day', 'explorer_rollup_hour_heatmap', 'explorer_rollup_item_day', 'explorer_snapshot_wealth_day',
  'explorer_rollup_run', 'explorer_rollup_coverage',
];

async function clearRollups() {
  for (const table of ROLLUP_TABLES) await sql`DELETE FROM ${sql.table(table)}`.execute(db);
}

async function recompute(days: string[], jobs: readonly DayJob[] = ROLLUP_JOBS) {
  for (const day of days) for (const job of jobs) await recomputeDay(rollupDb(), job, day);
}

async function dump(table: string) {
  return (await sql`SELECT * FROM ${sql.table(table)}`.execute(db)).rows
    .map((row) => JSON.stringify(row)).sort();
}

/** Every day from the oldest fixture to today (seed.sql settles at NOW()), contiguous so coverage spans them. */
const ALL_DAYS = eachDay({ from: OLD, to: TODAY });

beforeAll(async () => {
  if (!HAS_DB) return;
  const { database, ...server } = testDbOptions();
  const admin = await mysql.createConnection(server);
  try { await admin.query(`CREATE DATABASE IF NOT EXISTS \`${database}\``); }
  finally { await admin.end(); }
  await resetDb();
  await seedFixtures();
}, 120_000);

afterAll(async () => {
  if (HAS_DB) await closeRollupDb();
});

d('rollup recompute equals direct aggregation over the ledger', () => {
  beforeAll(async () => {
    await clearRollups();
    await recompute(ALL_DAYS);
  });

  it('account_day: gross flows per account and day, and the categorisation keys', async () => {
    const direct = await sql<{ account_id: number; day: string; inflow: string; outflow: string }>`
      SELECT p.account_id, DATE_FORMAT(t.settlement_time, '%Y-%m-%d') AS day,
             SUM(CASE WHEN p.amount > 0 THEN p.amount ELSE 0 END) AS inflow,
             SUM(CASE WHEN p.amount < 0 THEN -p.amount ELSE 0 END) AS outflow
      FROM ledger_txns t JOIN ledger_postings p ON p.txn_id = t.txn_id
      GROUP BY p.account_id, day ORDER BY p.account_id, day`.execute(db);
    const rolled = await sql<{ account_id: number; day: string; inflow: string; outflow: string }>`
      SELECT account_id, DATE_FORMAT(day, '%Y-%m-%d') AS day, SUM(inflow) AS inflow, SUM(outflow) AS outflow
      FROM explorer_rollup_account_day GROUP BY account_id, day ORDER BY account_id, day`.execute(db);
    expect(rolled.rows).toEqual(direct.rows);

    const keys = await sql<Record<string, unknown>>`
      SELECT account_id, plugin_key, tax_kind, counterparty_type, is_internal, inflow, outflow, txn_count
      FROM explorer_rollup_account_day WHERE day = ${D1} ORDER BY account_id, plugin_key, tax_kind, counterparty_type`.execute(db);
    const row = (account: number, plugin: string) => keys.rows.filter((r) => r.account_id === account && r.plugin_key === plugin);
    expect(row(1, 'treasury')).toEqual([expect.objectContaining({ tax_kind: 'personal', counterparty_type: 'GOVERNMENT', outflow: '12.34' })]);
    expect(row(2, 'treasury')).toEqual([expect.objectContaining({ tax_kind: 'fine', counterparty_type: 'GOVERNMENT', outflow: '50.00' })]);
    expect(row(2, 'ChestShop')).toEqual([expect.objectContaining({ counterparty_type: 'BUSINESS', is_internal: 0, outflow: '100.00' })]);
    expect(row(3, 'BusinessPlugin')).toEqual([expect.objectContaining({ counterparty_type: 'BUSINESS', is_internal: 1, outflow: '40.00' })]);
    expect(row(5, 'treasury').find((r) => r.outflow === '30.00')).toEqual(expect.objectContaining({ counterparty_type: 'GOVERNMENT', is_internal: 1 }));
    expect(row(1, 'Treasury-Core').map((r) => [r.counterparty_type, r.is_internal])).toEqual([['GOVERNMENT', 0], ['PERSONAL', 0]]);
  });

  it('type_day and hour_heatmap match grouping the raw rows', async () => {
    const directType = await sql`
      SELECT DATE_FORMAT(t.settlement_time, '%Y-%m-%d') AS day, a.account_type, COALESCE(t.plugin_system, '') AS plugin_key,
             SUM(CASE WHEN p.amount > 0 THEN p.amount ELSE 0 END) AS inflow,
             SUM(CASE WHEN p.amount < 0 THEN -p.amount ELSE 0 END) AS outflow
      FROM ledger_txns t JOIN ledger_postings p ON p.txn_id = t.txn_id JOIN accounts a ON a.account_id = p.account_id
      GROUP BY day, a.account_type, plugin_key ORDER BY day, a.account_type, plugin_key`.execute(db);
    const rolledType = await sql`
      SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, account_type, plugin_key, inflow, outflow
      FROM explorer_rollup_type_day ORDER BY day, account_type, plugin_key`.execute(db);
    expect(rolledType.rows).toEqual(directType.rows);

    const directHeat = await sql<Record<string, string | number>>`
      SELECT DATE_FORMAT(settlement_time, '%Y-%m-%d') AS day, WEEKDAY(settlement_time) AS weekday,
             HOUR(settlement_time) AS hour, COUNT(*) AS txn_count
      FROM ledger_txns GROUP BY day, weekday, hour ORDER BY day, hour`.execute(db);
    const rolledHeat = await sql<Record<string, string | number>>`
      SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, weekday, hour, txn_count
      FROM explorer_rollup_hour_heatmap ORDER BY day, hour`.execute(db);
    const cells = (rows: Record<string, string | number>[]) => rows.map((r) => [r.day, Number(r.weekday), Number(r.hour), Number(r.txn_count)]);
    expect(cells(rolledHeat.rows)).toEqual(cells(directHeat.rows));
  });

  it('item_day matches the sales, with open/close by trade order', async () => {
    const rolled = await sql<Record<string, string | number>>`
      SELECT item_key, DATE_FORMAT(day, '%Y-%m-%d') AS day, direction, qty, volume, trades,
             min_unit_price, max_unit_price, open_unit_price, close_unit_price
      FROM explorer_rollup_item_day ORDER BY item_key, day, direction`.execute(db);
    const row = (item: string, day: string, direction: string, qty: number, volume: string, trades: number, prices: [string, string, string, string]) =>
      ({ item_key: item, day, direction, qty, volume, trades, min_unit_price: prices[0], max_unit_price: prices[1], open_unit_price: prices[2], close_unit_price: prices[3] });
    expect(rolled.rows.map((r) => ({ ...r, qty: Number(r.qty), trades: Number(r.trades) }))).toEqual([
      // 4 @ 5.00 (10:00), 2 @ 7.50 (11:00), 1 @ 6.25 (12:00): opens at 5, closes at 6.25.
      row('DIAMOND', D1, 'BUY', 7, '41.25', 3, ['5.0000', '7.5000', '5.0000', '6.2500']),
      row('DIAMOND', D1, 'SELL', 3, '12.00', 1, ['4.0000', '4.0000', '4.0000', '4.0000']),
      // seed.sql's two DIAMOND sales at NOW(): 10 @ 5 then 5 @ 6 (same second; sale_id orders them).
      row('DIAMOND', TODAY, 'SELL', 15, '80.00', 2, ['5.0000', '6.0000', '5.0000', '6.0000']),
      row('IRON_INGOT', D2, 'BUY', 10, '10.00', 1, ['1.0000', '1.0000', '1.0000', '1.0000']),
      row('IRON_INGOT', TODAY, 'SELL', 20, '20.00', 1, ['1.0000', '1.0000', '1.0000', '1.0000']),
    ]);
  });

  it('pair_month matches pairing every opposite-signed leg per month', async () => {
    const direct = await sql`
      SELECT me.account_id, other.account_id AS counterparty_id,
             DATE_FORMAT(t.settlement_time, '%Y-%m-01') AS month,
             SUM(CASE WHEN me.amount > 0 THEN LEAST(me.amount, -other.amount) ELSE 0 END) AS inflow,
             SUM(CASE WHEN me.amount < 0 THEN LEAST(-me.amount, other.amount) ELSE 0 END) AS outflow,
             COUNT(DISTINCT me.txn_id) AS txn_count
      FROM ledger_txns t JOIN ledger_postings me ON me.txn_id = t.txn_id
      JOIN ledger_postings other ON other.txn_id = t.txn_id AND other.account_id <> me.account_id
        AND SIGN(other.amount) = -SIGN(me.amount)
      GROUP BY me.account_id, other.account_id, month ORDER BY 1, 2, 3`.execute(db);
    const rolled = await sql`
      SELECT account_id, counterparty_id, DATE_FORMAT(month, '%Y-%m-%d') AS month, inflow, outflow, txn_count
      FROM explorer_rollup_pair_month ORDER BY 1, 2, 3`.execute(db);
    const norm = (rows: unknown[]) => rows.map((r) => ({ ...(r as object), txn_count: Number((r as { txn_count: number }).txn_count) }));
    expect(norm(rolled.rows)).toEqual(norm(direct.rows));
  });

  it('economy_day: raw totals match ledger_txn_volume, players match economy_players and postings', async () => {
    const rows = await sql<{ day: string; raw_txn_count: number; raw_volume: string; new_players: number; active_players: number }>`
      SELECT DATE_FORMAT(day, '%Y-%m-%d') AS day, raw_txn_count, raw_volume, new_players, active_players
      FROM explorer_rollup_economy_day WHERE day IN (${D1}, ${D2}) ORDER BY day`.execute(db);
    const direct = await sql<{ day: string; n: number; v: string }>`
      SELECT DATE_FORMAT(settlement_time, '%Y-%m-%d') AS day, COUNT(*) AS n, SUM(volume) AS v
      FROM ledger_txn_volume WHERE settlement_time >= ${D1} AND settlement_time < ${addDays(D2, 1)}
      GROUP BY day ORDER BY day`.execute(db);
    expect(rows.rows.map((r) => [r.day, Number(r.raw_txn_count), r.raw_volume]))
      .toEqual(direct.rows.map((r) => [r.day, Number(r.n), r.v]));
    expect(rows.rows.map((r) => Number(r.new_players))).toEqual([1, 0]);
    // D1: Alice (#1) and Bob (#2) post; D2: only Alice.
    expect(rows.rows.map((r) => Number(r.active_players))).toEqual([2, 1]);
  });
});

d('bridge-adjusted volume equals getDailyVolume / getActivityComparison', () => {
  beforeAll(async () => {
    await clearRollups();
    await recompute(ALL_DAYS);
  });

  it('matches getDailyVolume on every complete day, including a pair that straddles midnight', async () => {
    const live = await getDailyVolume(30);
    const rolled = await economyDays({ from: addDays(TODAY, -29), to: addDays(TODAY, -1) });
    const complete = live.filter((p) => p.date > addDays(TODAY, -30) && p.date < TODAY);
    expect(complete.map((p) => p.date)).toEqual([D1, D2]);
    expect(rolled.data.filter((r) => r.rawTxnCount > 0).map((r) => ({ date: r.day, txn_count: r.txnCount, total_volume: r.volume })))
      .toEqual(complete);
    // The midnight pair counts once, on its outgoing (D2) leg; D1 keeps 1 of its 2 legs for the inner pair.
    const byDay = Object.fromEntries(rolled.data.map((r) => [r.day, r]));
    expect([byDay[D1]!.bridgePairs, byDay[D2]!.bridgePairs]).toEqual([2, 0]);
    expect(byDay[D1]!.rawTxnCount - byDay[D1]!.txnCount).toBe(2);
  });

  it('sums to getActivityComparison over a window of whole days', async () => {
    const live = await getActivityComparison(7);
    const rolled = await economyDays({ from: addDays(TODAY, -6), to: TODAY });
    const txns = rolled.data.reduce((n, r) => n + r.txnCount, 0);
    const volume = rolled.data.reduce((n, r) => n + Math.round(Number(r.volume) * 100), 0) / 100;
    expect(live.current).toEqual({ transactions: txns, volume });
  });
});

d('recompute is idempotent and follows settlement_time changes', () => {
  it('produces identical tables when run twice', async () => {
    await clearRollups();
    await recompute(ALL_DAYS);
    const first = await Promise.all(ROLLUP_TABLES.slice(0, 7).map(dump));
    await recompute(ALL_DAYS);
    expect(await Promise.all(ROLLUP_TABLES.slice(0, 7).map(dump))).toEqual(first);
  });

  it('moves a transaction between days when its settlement_time changes in the window', async () => {
    await clearRollups();
    await recompute([D1, D2]);
    const moved = await txn(at(D1, '20:00:00'), 'Treasury-Core', 'late', [[1, '-9.99'], [2, '9.99']]);
    try {
      await recompute([D1, D2]);
      const flows = async () => (await accountDaySeries([2], { from: D1, to: D2 })).data.map((p) => p.inflow);
      const pair = async () => (await sql<{ month: string; inflow: string }>`
        SELECT DATE_FORMAT(month, '%Y-%m-%d') AS month, inflow FROM explorer_rollup_pair_month
        WHERE account_id = 2 AND counterparty_id = 1 ORDER BY month`.execute(db)).rows;
      const before = await flows();
      await sql`UPDATE ledger_txns SET settlement_time = ${at(D2, '01:00:00')} WHERE txn_id = ${moved}`.execute(db);
      await recompute([D1, D2]);
      const after = await flows();
      expect(after.map((v) => Math.round(Number(v) * 100))).toEqual([
        Math.round(Number(before[0]) * 100) - 999, Math.round(Number(before[1]) * 100) + 999,
      ]);
      // Only D1 and D2 are rolled up, so the month row covers exactly those days.
      const direct = await sql<{ month: string; inflow: string }>`
        SELECT DATE_FORMAT(t.settlement_time, '%Y-%m-01') AS month, SUM(LEAST(me.amount, -other.amount)) AS inflow
        FROM ledger_txns t
        JOIN ledger_postings me ON me.txn_id = t.txn_id AND me.account_id = 2 AND me.amount > 0
        JOIN ledger_postings other ON other.txn_id = t.txn_id AND other.account_id = 1 AND other.amount < 0
        WHERE t.settlement_time >= ${D1} AND t.settlement_time < ${addDays(D2, 1)}
        GROUP BY month ORDER BY month`.execute(db);
      expect(await pair()).toEqual(direct.rows);
    } finally {
      await sql`DELETE FROM ledger_postings WHERE txn_id = ${moved}`.execute(db);
      await sql`DELETE FROM ledger_txns WHERE txn_id = ${moved}`.execute(db);
      await recompute([D1, D2]);
    }
  });
});

d('worker backfill and coverage', () => {
  // 00:05 UTC: before the nightly pass is due, so a tick is the fresh pass alone.
  let clock = new Date(`${TODAY}T00:05:00Z`);
  const worker = () => new RollupWorker({
    db: rollupDb(), lease: new LeaderLease(null, 'test', 'test'), now: () => clock, config: { backfillPauseMs: 0, wealthEveryMs: 0 },
    log: { info: () => {}, warn: () => {} },
  });

  it('starts from yesterday, resumes newest-first after a restart, and completes the history', async () => {
    await clearRollups();
    clock = new Date(`${TODAY}T00:05:00Z`);
    const first = worker();
    const tick = await first.tick(0); // a deadline in the past: the fresh pass only
    expect(tick.freshDays).toEqual([addDays(TODAY, -1), TODAY]);
    expect(tick.backfilled).toEqual([]);
    expect((await rollupCoverage()).account_day).toEqual(expect.objectContaining({ from_day: addDays(TODAY, -1), to_day: TODAY }));
    expect(await isCovered('account_day', { from: D1, to: TODAY })).toBe(false);

    expect((await first.backfillOne(TODAY))?.day).toBe(addDays(TODAY, -2));
    expect((await first.backfillOne(TODAY))?.day).toBe(addDays(TODAY, -3));

    // A new process picks up where coverage says, not from yesterday again.
    const restarted = worker();
    expect((await restarted.backfillOne(TODAY))?.day).toBe(addDays(TODAY, -4));
    const steps = await sql<{ n: number }>`SELECT COUNT(*) AS n FROM explorer_rollup_run
      WHERE job = 'account_day' AND day = ${addDays(TODAY, -2)}`.execute(db);
    expect(Number(steps.rows[0]!.n)).toBe(1);

    const done = await restarted.tick(Date.parse(`${TODAY}T13:00:00Z`));
    expect(done.backfilled[0]).toBe(addDays(TODAY, -5));
    expect(done.backfilled.at(-1)).toBe(OLD);
    expect(done.errors).toBe(0);
    const coverage = await rollupCoverage();
    for (const job of ROLLUP_JOBS.filter((j) => j !== 'item_day')) {
      expect(coverage[job]).toEqual(expect.objectContaining({ from_day: OLD, to_day: TODAY, history_complete: 1 }));
    }
    expect(coverage.item_day).toEqual(expect.objectContaining({ from_day: D1, history_complete: 1 }));
    expect(coverage.wealth).toEqual(expect.objectContaining({ from_day: TODAY, to_day: TODAY }));
    expect(await isCovered('account_day', { from: addDays(OLD, -100), to: TODAY })).toBe(true);
    expect(await restarted.backfillOne(TODAY)).toBeNull();

    // The backfilled tables equal a straight recompute of every day.
    const backfilled = await Promise.all(ROLLUP_TABLES.slice(0, 7).map(dump));
    await recompute(eachDay({ from: OLD, to: TODAY }));
    expect(await Promise.all(ROLLUP_TABLES.slice(0, 7).map(dump))).toEqual(backfilled);
  });

  it('runs the nightly 7-day recompute once per UTC day, after 00:15', async () => {
    clock = new Date(`${TODAY}T00:20:00Z`);
    await sql`DELETE FROM explorer_rollup_run`.execute(db);
    const nightly = worker();
    const first = await nightly.tick(0);
    expect(first.nightly).toBe(true);
    const days = await sql<{ day: string }>`SELECT DISTINCT DATE_FORMAT(day, '%Y-%m-%d') AS day FROM explorer_rollup_run
      WHERE job = 'account_day' ORDER BY day`.execute(db);
    expect(days.rows.map((r) => r.day)).toEqual(eachDay({ from: addDays(TODAY, -8), to: TODAY }));
    expect((await nightly.tick(0)).nightly).toBe(false);
    clock = new Date(`${TODAY}T00:05:00Z`);
  });

  it('fills days missed while no worker ran before advancing coverage', async () => {
    await sql`UPDATE explorer_rollup_coverage SET to_day = ${D1} WHERE job <> 'wealth'`.execute(db);
    const tick = await worker().tick(0);
    expect(tick.freshDays).toEqual(eachDay({ from: D2, to: TODAY }));
    expect((await rollupCoverage()).account_day?.to_day).toBe(TODAY);
  });

  it('logs a failing step, keeps the other jobs running and does not extend its coverage', async () => {
    await sql`RENAME TABLE explorer_rollup_item_day TO explorer_rollup_item_day_away`.execute(db);
    try {
      await sql`DELETE FROM explorer_rollup_run`.execute(db);
      await sql`UPDATE explorer_rollup_coverage SET to_day = ${addDays(TODAY, -1)} WHERE job IN ('item_day', 'account_day')`.execute(db);
      const tick = await worker().tick(0);
      expect(tick.errors).toBe(2); // item_day for yesterday and today
      const failed = await sql<{ job: string; error: string }>`SELECT job, error FROM explorer_rollup_run WHERE error IS NOT NULL`.execute(db);
      expect(failed.rows.map((r) => r.job)).toEqual(['item_day', 'item_day']);
      expect(failed.rows[0]!.error).toContain('explorer_rollup_item_day');
      const coverage = await rollupCoverage();
      expect(coverage.item_day?.to_day).toBe(addDays(TODAY, -1));
      expect(coverage.account_day?.to_day).toBe(TODAY);
      expect((await rollupHealth()).lastErrorAt).not.toBeNull();
      const exposition = (await metricsText()).body;
      expect(exposition).toMatch(/explorer_rollup_steps_total\{[^}]*job="item_day"[^}]*outcome="error"[^}]*\} 2/);
      expect(exposition).toMatch(/explorer_rollup_leader(\{[^}]*\})? 1/);
      expect(exposition).toMatch(/explorer_db_queries_total\{[^}]*fn="rollup:account_day"/);
    } finally {
      await sql`RENAME TABLE explorer_rollup_item_day_away TO explorer_rollup_item_day`.execute(db);
    }
    expect((await worker().tick(0)).errors).toBe(0);
    expect((await rollupCoverage()).item_day?.to_day).toBe(TODAY);
  });

  it('reports health from the run log', async () => {
    // The run log is stamped with the worker's (fake) clock.
    const last = (await rollupHealth()).lastSuccessAt;
    expect(last).not.toBeNull();
    const health = await rollupHealth(new Date(Date.parse(last!) + 9 * 60_000));
    expect(health.stale).toBe(false);
    expect(health.coverage.account_day).toEqual(expect.objectContaining({ to: TODAY, historyComplete: true }));
    expect((await rollupHealth(new Date(Date.parse(last!) + 11 * 60_000))).stale).toBe(true);
  });
});

d('read helpers', () => {
  beforeAll(async () => {
    await clearRollups();
    await recompute(eachDay({ from: OLD, to: TODAY }));
    await sql`UPDATE explorer_rollup_coverage SET history_complete = 1`.execute(db);
    await snapshotWealth(rollupDb(), TODAY);
  });

  it('balanceSeries: current balance minus later nets equals the ledger replayed to each day end', async () => {
    const range = { from: addDays(D1, -1), to: D2 };
    const series = await balanceSeries([1, 2], range);
    expect(series.coverage.covered).toBe(true);
    const direct = await Promise.all(eachDay(range).map(async (day) => {
      const r = await sql<{ b: string }>`
        SELECT (SELECT SUM(balance) FROM account_balances_mat WHERE account_id IN (1, 2))
             - COALESCE((SELECT SUM(p.amount) FROM ledger_postings p JOIN ledger_txns t ON t.txn_id = p.txn_id
                         WHERE p.account_id IN (1, 2) AND t.settlement_time >= ${addDays(day, 1)}), 0) AS b`.execute(db);
      return { day, balance: r.rows[0]!.b };
    }));
    expect(series.data).toEqual(direct);
  });

  it('balanceSeries is not covered until account_day reaches today', async () => {
    await sql`UPDATE explorer_rollup_coverage SET to_day = ${addDays(TODAY, -1)} WHERE job = 'account_day'`.execute(db);
    try {
      expect(await balanceSeries([1], { from: D1, to: D2 })).toEqual(expect.objectContaining({ data: [], coverage: expect.objectContaining({ covered: false }) }));
    } finally {
      await sql`UPDATE explorer_rollup_coverage SET to_day = ${TODAY} WHERE job = 'account_day'`.execute(db);
    }
  });

  it('accountDaySeries is dense and can exclude internal transfers', async () => {
    const all = await accountDaySeries([3, 20], { from: D1, to: D2 });
    expect(all.data.map((p) => [p.day, p.inflow, p.outflow])).toEqual([[D1, '135.00', '40.00'], [D2, '20.00', '0.00']]);
    const external = await accountDaySeries([3, 20], { from: D1, to: D2 }, { excludeInternal: true });
    expect(external.data.map((p) => [p.day, p.inflow, p.outflow, p.net])).toEqual([[D1, '95.00', '0.00', '95.00'], [D2, '20.00', '0.00', '20.00']]);
    const quiet = await accountDaySeries([3], { from: addDays(D1, -2), to: addDays(D1, -1) });
    expect(quiet.data).toEqual([
      { day: addDays(D1, -2), inflow: '0.00', outflow: '0.00', net: '0.00', txnCount: 0 },
      { day: addDays(D1, -1), inflow: '0.00', outflow: '0.00', net: '0.00', txnCount: 0 },
    ]);
  });

  it('categoryTotals maps flows through the taxonomy', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      const alice = await categoryTotals([1], { from: D1, to: D2 });
      expect(alice.data.map((c) => [c.category, c.taxKind, c.inflow, c.outflow])).toEqual([
        ['wages', null, '60.00', '0.00'],
        ['shop_purchases', null, '0.00', '20.00'],
        ['taxes', 'personal', '0.00', '12.34'],
        ['transfers', null, '4.25', '18.50'],
        ['other', null, '26.00', '0.00'],
      ]);
      const gov = await categoryTotals([5, 22], { from: D1, to: D1 });
      expect(gov.data.map((c) => [c.category, c.taxKind, c.inflow, c.outflow])).toEqual([
        ['shop_sales', null, '5.00', '0.00'],
        ['taxes', 'personal', '12.34', '0.00'],
        ['fines', null, '50.00', '0.00'],
        // Treasury-Core to a player is a Transfer from the paying side; the
        // player sees it as Other (a payout from a government account).
        ['transfers', null, '0.00', '25.00'],
        ['internal', null, '30.00', '30.00'],
      ]);
      expect(warn).toHaveBeenCalledWith(expect.stringContaining('weirdplugin'));
    } finally { warn.mockRestore(); }
  });

  it('counterparties widen to months and exclude the set itself', async () => {
    const top = await counterparties([1], { from: D1, to: D2 });
    expect(top.coverage.covered).toBe(true);
    // Whole months: from the first of D1's month through the end of D2's.
    const direct = await sql<{ id: number; inflow: string; outflow: string }>`
      SELECT other.account_id AS id,
             SUM(CASE WHEN me.amount > 0 THEN LEAST(me.amount, -other.amount) ELSE 0 END) AS inflow,
             SUM(CASE WHEN me.amount < 0 THEN LEAST(-me.amount, other.amount) ELSE 0 END) AS outflow
      FROM ledger_txns t JOIN ledger_postings me ON me.txn_id = t.txn_id AND me.account_id = 1
      JOIN ledger_postings other ON other.txn_id = t.txn_id AND other.account_id <> 1 AND SIGN(other.amount) = -SIGN(me.amount)
      WHERE t.settlement_time >= ${monthStart(D1)} AND t.settlement_time < ${addDays(monthEnd(D2), 1)}
      GROUP BY other.account_id ORDER BY SUM(LEAST(ABS(me.amount), ABS(other.amount))) DESC, other.account_id`.execute(db);
    expect(top.data.map((c) => [c.counterpartyId, c.inflow, c.outflow])).toEqual(direct.rows.map((r) => [r.id, r.inflow, r.outflow]));
    expect(top.data.find((c) => c.counterpartyId === 3)).toEqual(expect.objectContaining({ accountType: 'BUSINESS', displayName: 'Acme Corporate Account' }));
    const both = await counterparties([1, 2], { from: D1, to: D2 });
    expect(both.data.map((c) => c.counterpartyId)).not.toContain(1);
    expect(both.data.map((c) => c.counterpartyId)).not.toContain(2);
  });

  it('typeDays, heatmap and itemDays read their tables over the range', async () => {
    const types = await typeDays({ from: D2, to: D2 });
    expect(types.data).toContainEqual({ day: D2, accountType: 'PERSONAL', pluginKey: 'treasury-salary', inflow: '60.00', outflow: '0.00' });
    const cells = await heatmap({ from: D1, to: D2 });
    expect(cells.data.reduce((n, c) => n + c.txnCount, 0)).toBe(15);
    expect(cells.data).toContainEqual({ weekday: (new Date(`${D2}T00:00:00Z`).getUTCDay() + 6) % 7, hour: 0, txnCount: 1 });
    const items = await itemDays(['DIAMOND'], { from: D1, to: D2 });
    expect(items.data.map((i) => [i.day, i.direction, i.openUnitPrice, i.closeUnitPrice])).toEqual([[D1, 'BUY', '5.0000', '6.2500'], [D1, 'SELL', '4.0000', '4.0000']]);
  });

  it('wealthSnapshots match the live wealth figures', async () => {
    const snap = await wealthSnapshots({ from: TODAY, to: TODAY });
    const balances = await getPersonalBalances();
    expect(snap.data).toHaveLength(1);
    const s = snap.data[0]!;
    expect(Number(s.gini)).toBeCloseTo(gini(balances), 5);
    expect(Number(s.top10Share)).toBeCloseTo(topShare(balances, 0.1), 5);
    expect(Number(s.top1Share)).toBeCloseTo(topShare(balances, 0.01), 5);
    expect(s.personalHolders).toBe(balances.length);
    const buckets = await getBalanceDistribution();
    expect(s.holderBuckets).toEqual(Object.fromEntries(buckets.map((b) => [b.bucket, b.account_count])));
    const supply = await sql<{ t: string }>`SELECT SUM(abm.balance) AS t FROM account_balances_mat abm
      JOIN accounts a ON a.account_id = abm.account_id WHERE a.is_archived = 0 AND a.account_type = 'PERSONAL'`.execute(db);
    expect(s.supply.personal).toBe(supply.rows[0]!.t);
    expect((await wealthSnapshots({ from: addDays(TODAY, -3), to: TODAY })).coverage.covered).toBe(false);
  });
});

// Production's explorer DB user may only SELECT from the ledger and economy
// tables and write the explorer_* tables (PAR-532). pair_month once used a
// temporary table and failed there with "Access denied ... to database"; this
// suite runs the worker as a user with exactly those grants, so any statement
// that needs more (temporary tables, DDL, locks, writes outside explorer_*)
// fails here rather than on a deployment.
d('the worker needs only SELECT plus DML on explorer_* tables', () => {
  const ROLE = 'rollup_dml_only';
  const PASSWORD = 'rollup-dml-only';
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let restricted: Kysely<any>;

  const dropRole = async () => {
    const { database: _database, ...server } = testDbOptions();
    void _database;
    const admin = await mysql.createConnection(server);
    try { await admin.query(`DROP USER IF EXISTS '${ROLE}'@'%'`); }
    finally { await admin.end(); }
  };

  beforeAll(async () => {
    if (!HAS_DB) return;
    await dropRole();
    const { database, ...server } = testDbOptions();
    const admin = await mysql.createConnection(server);
    try {
      await admin.query(`CREATE USER '${ROLE}'@'%' IDENTIFIED BY '${PASSWORD}'`);
      await admin.query(`GRANT SELECT ON \`${database}\`.* TO '${ROLE}'@'%'`);
      const [tables] = await admin.query<mysql.RowDataPacket[]>(
        "SELECT table_name AS n FROM information_schema.tables WHERE table_schema = ? AND table_name LIKE 'explorer\\_%'", [database]);
      expect(tables.length).toBeGreaterThanOrEqual(ROLLUP_TABLES.length);
      for (const t of tables) await admin.query(`GRANT INSERT, UPDATE, DELETE ON \`${database}\`.\`${String(t.n)}\` TO '${ROLE}'@'%'`);
    } finally {
      await admin.end();
    }
    const pool = createPool({ ...testDbOptions(), user: ROLE, password: PASSWORD, connectionLimit: 2, timezone: 'Z', dateStrings: true });
    pool.on('connection', (conn) => { for (const statement of ROLLUP_SESSION_SQL) conn.query(statement, () => {}); });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    restricted = new Kysely({ dialect: new MysqlDialect({ pool: pool as any }) });
  }, 30_000);

  afterAll(async () => {
    if (!HAS_DB) return;
    await restricted.destroy();
    await dropRole();
  });

  it('really lacks the privileges the old pair_month needed', async () => {
    await expect(sql`CREATE TEMPORARY TABLE rollup_probe (id INT)`.execute(restricted)).rejects.toThrow(/denied/i);
    await expect(sql`CREATE TABLE rollup_probe (id INT)`.execute(restricted)).rejects.toThrow(/denied/i);
    await expect(sql`DELETE FROM ledger_postings WHERE txn_id = 0`.execute(restricted)).rejects.toThrow(/denied/i);
  });

  it('runs every step, the wealth snapshot, backfill and the nightly pass without errors', async () => {
    await clearRollups();
    const warnings: unknown[][] = [];
    const clock = new Date(`${TODAY}T12:00:00Z`);
    const worker = new RollupWorker({
      db: restricted, lease: new LeaderLease(null, 'test', 'test'), now: () => clock,
      config: { backfillPauseMs: 0, wealthEveryMs: 0 },
      log: { info: () => {}, warn: (...args: unknown[]) => { warnings.push(args); } },
    });
    const tick = await worker.tick(clock.getTime() + 600_000);
    expect(warnings).toEqual([]);
    expect(tick).toEqual(expect.objectContaining({ leader: true, wealth: true, nightly: true, errors: 0 }));
    // Backfill reached the oldest ledger day, so pair_month ran for every month.
    expect(tick.backfilled).toContain(OLD);

    const failed = await sql`SELECT job, day, error FROM explorer_rollup_run WHERE error IS NOT NULL`.execute(db);
    expect(failed.rows).toEqual([]);
    const jobs = await sql<{ job: string }>`SELECT DISTINCT job FROM explorer_rollup_run`.execute(db);
    expect(jobs.rows.map((r) => r.job).sort()).toEqual([...ROLLUP_JOBS, 'nightly', 'wealth'].sort());

    // The restricted run builds the same tables as the privileged one.
    const asRestricted = await Promise.all(ROLLUP_TABLES.slice(0, 7).map(dump));
    await clearRollups();
    await recompute(ALL_DAYS);
    expect(await Promise.all(ROLLUP_TABLES.slice(0, 7).map(dump))).toEqual(asRestricted);
  }, 120_000);

  it('pair_month re-sums touched accounts across several IN-list chunks, including accounts that left the day', async () => {
    const day = OLD;
    const FIRST = 100_000;
    const PAIRS = 1_100; // 2,200 accounts: three chunks of 1,000
    const month = monthStart(day);
    const txnSeq = sql.raw(`seq_${FIRST}_to_${FIRST + PAIRS - 1}`);
    const accountSeq = sql.raw(`seq_${FIRST}_to_${FIRST + 2 * PAIRS - 1}`);
    const monthRows = async () => (await sql`
      SELECT account_id, counterparty_id, DATE_FORMAT(month, '%Y-%m-%d') AS month, inflow, outflow, txn_count
      FROM explorer_rollup_pair_month WHERE account_id >= ${FIRST} ORDER BY 1, 2, 3`.execute(db)).rows;
    // Each transaction is one payer, one payee, so pairing is the plain amount.
    const direct = async () => (await sql`
      SELECT me.account_id, other.account_id AS counterparty_id, ${month} AS month,
             SUM(CASE WHEN me.amount > 0 THEN me.amount ELSE 0 END) AS inflow,
             SUM(CASE WHEN me.amount < 0 THEN -me.amount ELSE 0 END) AS outflow,
             COUNT(DISTINCT me.txn_id) AS txn_count
      FROM ledger_txns t JOIN ledger_postings me ON me.txn_id = t.txn_id
      JOIN ledger_postings other ON other.txn_id = t.txn_id AND other.account_id <> me.account_id
      WHERE me.account_id >= ${FIRST} AND t.settlement_time >= ${month} AND t.settlement_time < ${addDays(monthEnd(day), 1)}
      GROUP BY me.account_id, other.account_id ORDER BY 1, 2, 3`.execute(db)).rows;
    const normalise = (rows: unknown[]) => rows.map((r) => {
      const row = r as Record<string, string | number>;
      return [Number(row.account_id), Number(row.counterparty_id), row.month, Number(row.inflow), Number(row.outflow), Number(row.txn_count)];
    });

    try {
      await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name)
        SELECT seq, 'BUSINESS', UNHEX(LPAD(HEX(seq), 32, '0')), CONCAT('chunk-', seq) FROM ${accountSeq}`.execute(db);
      await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, plugin_system)
        SELECT seq, ${at(day, '03:00:00')}, 'chunk', UNHEX(REPEAT('00', 16)), 'ChunkTest' FROM ${txnSeq}`.execute(db);
      // Transaction FIRST+i moves 1.00 + i/100 from account FIRST+2i to FIRST+2i+1.
      await sql`INSERT INTO ledger_postings (txn_id, account_id, amount)
        SELECT seq, ${FIRST} + 2 * (seq - ${FIRST}), -(1 + (seq - ${FIRST}) / 100) FROM ${txnSeq}`.execute(db);
      await sql`INSERT INTO ledger_postings (txn_id, account_id, amount)
        SELECT seq, ${FIRST} + 2 * (seq - ${FIRST}) + 1, 1 + (seq - ${FIRST}) / 100 FROM ${txnSeq}`.execute(db);

      await clearRollups();
      await recomputeDay(restricted, 'pair_month', day);
      const built = await monthRows();
      expect(built).toHaveLength(2 * PAIRS);
      expect(normalise(built)).toEqual(normalise(await direct()));

      // Drop every other transaction: half the accounts are now touched only by
      // the old pair_day rows, and their month rows must go.
      await sql`DELETE FROM ledger_postings WHERE txn_id >= ${FIRST} AND MOD(txn_id, 2) = 0`.execute(db);
      await sql`DELETE FROM ledger_txns WHERE txn_id >= ${FIRST} AND MOD(txn_id, 2) = 0`.execute(db);
      await recomputeDay(restricted, 'pair_month', day);
      const shrunk = await monthRows();
      expect(shrunk).toHaveLength(PAIRS); // 550 transactions left, two month rows each
      expect(normalise(shrunk)).toEqual(normalise(await direct()));
    } finally {
      await sql`DELETE FROM ledger_postings WHERE txn_id >= ${FIRST}`.execute(db);
      await sql`DELETE FROM ledger_txns WHERE txn_id >= ${FIRST}`.execute(db);
      await sql`DELETE FROM accounts WHERE account_id >= ${FIRST}`.execute(db);
      await clearRollups();
    }
  }, 120_000);
});
