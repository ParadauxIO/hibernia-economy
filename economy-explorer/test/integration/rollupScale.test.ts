import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';
import mysql from 'mysql2/promise';
import { sql } from 'kysely';

// Opt-in scale measurement for the rollup worker (PAR-532), not part of CI:
//
//   RUN_INTEGRATION=1 RUN_ROLLUP_SCALE=1 DB_*=... npx vitest run test/integration/rollupScale.test.ts
//
// Builds DB_NAME + '_scale' with SCALE_DAYS (default 30) days of synthetic
// ledger at SCALE_TXNS_PER_DAY (default 35,000, production's daily rate), shaped
// like production: ChestShop two- and three-leg sales, salaries, transfers,
// balance taxes and adjacent Vault bridge pairs, plus one ChestShop sale row per
// ChestShop transaction. Prints one day's recompute time per job, the fresh
// (minute) pass, the backfill rate and whether a concurrent ledger write waits.
vi.hoisted(() => {
  process.env.DB_NAME = `${process.env.DB_NAME ?? 'economy_test'}_scale`;
  // Generating millions of rows outlasts the request pool's 15 s backstop.
  process.env.DB_STMT_TIMEOUT_S = '0';
});

import { HAS_DB, resetDb, testDbOptions } from './db';
import { db } from '@/lib/db';
import { rollupDb, closeRollupDb } from '@/lib/rollup/db';
import { addDays, utcDay } from '@/lib/rollup/days';
import { LeaderLease } from '@/lib/rollup/lease';
import { ROLLUP_JOBS, recomputeDay, snapshotWealth } from '@/lib/rollup/steps';
import { RollupWorker } from '@/lib/rollup/worker';

const RUN = HAS_DB && process.env.RUN_ROLLUP_SCALE === '1';
const d = RUN ? describe : describe.skip;
const DAYS = Number(process.env.SCALE_DAYS ?? 30);
const PER_DAY = Number(process.env.SCALE_TXNS_PER_DAY ?? 35_000);
const TOTAL = DAYS * PER_DAY;
const TODAY = utcDay();
const FIRST = addDays(TODAY, -(DAYS - 1));
const BASE = 1_000_000; // txn ids above seed.sql's

const ms = (start: bigint) => Number(process.hrtime.bigint() - start) / 1e6;
const report: string[] = [];
const note = (line: string) => { report.push(line); console.log(`[scale] ${line}`); };

async function generate() {
  // Accounts: 10,000 personal, 500 business, 20 government, 1 SYSTEM bridge.
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name)
    SELECT 100000 + seq, 'PERSONAL', UNHEX(LPAD(HEX(100000 + seq), 32, '0')), NULL FROM seq_1_to_10000`.execute(db);
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name)
    SELECT 110000 + seq, 'BUSINESS', UNHEX(LPAD(HEX(100000 + seq), 32, '0')), CONCAT('Firm ', seq) FROM seq_1_to_500`.execute(db);
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name)
    SELECT 120000 + seq, 'GOVERNMENT', UNHEX(REPEAT('60', 16)), CONCAT('Dept ', seq) FROM seq_1_to_20`.execute(db);
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name)
    VALUES (130001, 'SYSTEM', UNHEX(REPEAT('13', 16)), 'Scale bridge')`.execute(db);
  await sql`INSERT INTO economy_players (player_uuid_bin, current_name, first_seen)
    SELECT UNHEX(LPAD(HEX(100000 + seq), 32, '0')), CONCAT('p', seq), ${FIRST} + INTERVAL (seq % ${DAYS}) DAY
    FROM seq_1_to_10000`.execute(db);

  // seq % 10: 0 three-leg ChestShop (5% to government), 1-4 ChestShop,
  // 5 salary, 6 Treasury-Core transfer, 7 balance tax, 8/9 a Vault bridge pair.
  // Settlement: 2 s apart within a day; a bridge deposit 1 s after its withdraw.
  const t = Date.now();
  await sql`
    INSERT INTO ledger_txns (txn_id, settlement_time, trade_time, message, initiator_uuid_bin, plugin_system)
    SELECT ${BASE} + seq, ts, ts,
           ELT(1 + seq % 10, 'purchase', 'purchase', 'purchase', 'purchase', 'purchase', 'Salary', 'pay',
               'Personal balance tax (24h 0m @ 1.0%/wk)', 'Vault withdraw (Essentials)', 'Vault deposit (Essentials)'),
           UNHEX(REPEAT('00', 16)),
           ELT(1 + seq % 10, 'ChestShop', 'ChestShop', 'ChestShop', 'ChestShop', 'ChestShop', 'treasury-salary',
               'Treasury-Core', 'treasury', 'Essentials', 'Essentials')
    FROM (SELECT seq, ${FIRST} + INTERVAL ((seq - 1) DIV ${PER_DAY}) DAY
                 + INTERVAL (CASE WHEN seq % 10 = 9 THEN ((seq - 2) % ${PER_DAY}) * 2 + 1 ELSE ((seq - 1) % ${PER_DAY}) * 2 END) SECOND AS ts
          FROM seq_1_to_${sql.raw(String(TOTAL))}) s
    ORDER BY seq`.execute(db);
  note(`generated ${TOTAL.toLocaleString()} ledger_txns in ${((Date.now() - t) / 1000).toFixed(1)} s`);

  // One day per statement keeps the ordered UNION out of large on-disk temp tables.
  const p = Date.now();
  for (let day = 0; day < DAYS; day++) {
    const seqs = sql.raw(`seq_${day * PER_DAY + 1}_to_${(day + 1) * PER_DAY}`);
    await sql`
    INSERT INTO ledger_postings (txn_id, account_id, amount)
    SELECT txn_id, account_id, amount FROM (
      SELECT ${BASE} + seq AS txn_id, 0 AS leg,
             CASE WHEN seq % 10 = 5 THEN 120001 + seq % 20 WHEN seq % 10 = 9 THEN 130001
                  ELSE 100001 + (seq * 7919) % 10000 END AS account_id,
             -amt AS amount, seq
      FROM (SELECT seq, (100 + ((seq - (seq % 10 = 9)) * 37) % 500000) / 100 AS amt FROM ${seqs}) a
      UNION ALL
      SELECT ${BASE} + seq, 1,
             CASE WHEN seq % 10 IN (0, 2, 4) THEN 110001 + seq % 500
                  WHEN seq % 10 IN (1, 3) THEN 100001 + (seq * 7919 + 1 + seq % 9000) % 10000
                  WHEN seq % 10 = 6 THEN 100001 + (seq * 7919 + 1) % 10000
                  WHEN seq % 10 = 7 THEN 120001 + seq % 20
                  WHEN seq % 10 = 8 THEN 130001
                  ELSE 100001 + (seq * 7919) % 10000 END,
             CASE WHEN seq % 10 = 0 THEN amt - ROUND(amt * 0.05, 2) ELSE amt END, seq
      FROM (SELECT seq, (100 + ((seq - (seq % 10 = 9)) * 37) % 500000) / 100 AS amt FROM ${seqs}) b
      UNION ALL
      SELECT ${BASE} + seq, 2, 120001 + seq % 20, ROUND(((100 + (seq * 37) % 500000) / 100) * 0.05, 2), seq
      FROM ${seqs} WHERE seq % 10 = 0
    ) legs ORDER BY seq, leg`.execute(db);
  }
  note(`generated postings in ${((Date.now() - p) / 1000).toFixed(1)} s`);

  await sql`
    INSERT INTO chestshop_sale (occurred_at, direction, customer_uuid_bin, is_admin_shop, material, item_key, item_name,
                                item_custom, quantity, unit_price, total_price)
    SELECT t.settlement_time, IF(seq % 2, 'BUY', 'SELL'), UNHEX(REPEAT('00', 16)), 0,
           CONCAT('ITEM_', seq % 200), CONCAT('ITEM_', seq % 200), CONCAT('Item ', seq % 200), 0,
           1 + seq % 64, (100 + seq % 900) / 100, (1 + seq % 64) * (100 + seq % 900) / 100
    FROM seq_1_to_${sql.raw(String(TOTAL))} s JOIN ledger_txns t ON t.txn_id = ${BASE} + s.seq
    WHERE seq % 10 < 5`.execute(db);
  const counts = await sql<{ t: number; p: number; s: number }>`SELECT
    (SELECT COUNT(*) FROM ledger_txns) AS t, (SELECT COUNT(*) FROM ledger_postings) AS p,
    (SELECT COUNT(*) FROM chestshop_sale) AS s`.execute(db);
  const c = counts.rows[0]!;
  note(`dataset: ${Number(c.t).toLocaleString()} txns, ${Number(c.p).toLocaleString()} postings, ${Number(c.s).toLocaleString()} sales over ${DAYS} days`);
  await sql`ANALYZE TABLE ledger_txns, ledger_postings, accounts, chestshop_sale, ledger_txn_volume`.execute(db);
}

beforeAll(async () => {
  if (!RUN) return;
  const { database, ...server } = testDbOptions();
  const admin = await mysql.createConnection(server);
  try { await admin.query(`CREATE DATABASE IF NOT EXISTS \`${database}\``); }
  finally { await admin.end(); }
  // SCALE_REUSE=1 keeps a dataset generated by an earlier run (same size, same day).
  if (process.env.SCALE_REUSE === '1') {
    const existing = await sql<{ n: number; last: string | null }>`SELECT COUNT(*) AS n,
      DATE_FORMAT(MAX(settlement_time), '%Y-%m-%d') AS last FROM ledger_txns WHERE txn_id > ${BASE}`.execute(db).catch(() => null);
    const row = existing?.rows[0];
    if (row && Number(row.n) >= TOTAL && row.last === TODAY) { note('reusing the generated dataset'); return; }
  }
  await resetDb();
  await generate();
}, 3_600_000);

afterAll(async () => {
  if (!RUN) return;
  console.log(`\n[scale] summary\n${report.map((l) => `  ${l}`).join('\n')}`);
  await closeRollupDb();
});

d('rollup worker at production scale', () => {
  it('recomputes one full day per job', async () => {
    const day = addDays(TODAY, -Math.floor(DAYS / 2));
    for (const pass of ['cold', 'warm']) {
      let total = 0;
      const parts: string[] = [];
      for (const job of ROLLUP_JOBS) {
        const start = process.hrtime.bigint();
        const rows = await recomputeDay(rollupDb(), job, day);
        const took = ms(start);
        total += took;
        parts.push(`${job} ${took.toFixed(0)} ms (${rows.toLocaleString()} rows)`);
      }
      note(`one day (${PER_DAY.toLocaleString()} txns), ${pass}: ${total.toFixed(0)} ms total — ${parts.join(', ')}`);
    }
    const start = process.hrtime.bigint();
    await snapshotWealth(rollupDb(), TODAY);
    note(`wealth snapshot: ${ms(start).toFixed(0)} ms`);
  }, 600_000);

  it('does not block a concurrent ledger write during a recompute', async () => {
    const day = TODAY;
    await sql`DELETE FROM ledger_postings WHERE txn_id = ${BASE + TOTAL + 1}`.execute(db);
    await sql`DELETE FROM ledger_txns WHERE txn_id = ${BASE + TOTAL + 1}`.execute(db);
    const running = recomputeDay(rollupDb(), 'account_day', day);
    await new Promise((resolve) => setTimeout(resolve, 50));
    const start = process.hrtime.bigint();
    await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, plugin_system)
      VALUES (${BASE + TOTAL + 1}, NOW(), 'concurrent', UNHEX(REPEAT('00', 16)), 'Treasury-Core')`.execute(db);
    await sql`INSERT INTO ledger_postings (txn_id, account_id, amount)
      VALUES (${BASE + TOTAL + 1}, 100001, -1.00), (${BASE + TOTAL + 1}, 100002, 1.00)`.execute(db);
    const write = ms(start);
    const recompute = await running.then(() => 'finished');
    note(`concurrent ledger write during account_day recompute of today: ${write.toFixed(1)} ms (recompute ${recompute})`);
    expect(write).toBeLessThan(1000);
  }, 600_000);

  it('measures the minute pass and the backfill rate', async () => {
    for (const table of ['explorer_rollup_account_day', 'explorer_rollup_pair_month', 'explorer_rollup_pair_day',
      'explorer_rollup_economy_day', 'explorer_rollup_type_day', 'explorer_rollup_hour_heatmap', 'explorer_rollup_item_day',
      'explorer_snapshot_wealth_day', 'explorer_rollup_run', 'explorer_rollup_coverage']) {
      await sql`DELETE FROM ${sql.table(table)}`.execute(db);
    }
    const worker = new RollupWorker({
      db: rollupDb(), lease: new LeaderLease(null, 'scale', 'scale'), backfillPauseMs: 50,
      now: () => new Date(`${TODAY}T00:05:00Z`), log: { info: () => {}, warn: (...a: unknown[]) => console.warn(...a) },
    });
    let start = process.hrtime.bigint();
    const fresh = await worker.tick(0);
    note(`minute pass (yesterday + today + wealth): ${(ms(start) / 1000).toFixed(2)} s, errors ${fresh.errors}`);
    start = process.hrtime.bigint();
    let days = 0;
    while (await worker.backfillOne(TODAY)) days++;
    const seconds = ms(start) / 1000;
    note(`backfill: ${days} days in ${seconds.toFixed(1)} s = ${(seconds / days).toFixed(2)} s/day (${(days / seconds * 60).toFixed(1)} days/min)`);
    const sizes = await sql<{ t: string; r: number; mb: number }>`
      SELECT table_name AS t, table_rows AS r, ROUND((data_length + index_length) / 1048576, 1) AS mb
      FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'explorer_%roll%' OR
        (table_schema = DATABASE() AND table_name = 'explorer_snapshot_wealth_day') ORDER BY t`.execute(db);
    const exact = await sql<{ a: number; pm: number; pd: number }>`SELECT
      (SELECT COUNT(*) FROM explorer_rollup_account_day) AS a, (SELECT COUNT(*) FROM explorer_rollup_pair_month) AS pm,
      (SELECT COUNT(*) FROM explorer_rollup_pair_day) AS pd`.execute(db);
    const e = exact.rows[0]!;
    note(`rows for ${DAYS} days: account_day ${Number(e.a).toLocaleString()} (${Math.round(Number(e.a) / DAYS).toLocaleString()}/day), ` +
      `pair_day ${Number(e.pd).toLocaleString()}, pair_month ${Number(e.pm).toLocaleString()}`);
    note(`sizes: ${sizes.rows.map((s) => `${s.t} ${s.mb} MB`).join(', ')}`);
    expect(fresh.errors).toBe(0);
  }, 3_600_000);

  it('serves the read helpers from indexes', async () => {
    const ids = sql.join([100001, 100002, 100003]);
    const plans: [string, ReturnType<typeof sql>][] = [
      ['accountDaySeries', sql`SELECT day, SUM(inflow), SUM(outflow) FROM explorer_rollup_account_day
        WHERE account_id IN (${ids}) AND day BETWEEN ${FIRST} AND ${TODAY} GROUP BY day`],
      ['counterparties', sql`SELECT counterparty_id, SUM(inflow) FROM explorer_rollup_pair_month
        WHERE account_id IN (${ids}) AND month BETWEEN ${FIRST} AND ${TODAY} GROUP BY counterparty_id`],
      ['economyDays', sql`SELECT * FROM explorer_rollup_economy_day WHERE day BETWEEN ${FIRST} AND ${TODAY}`],
      ['itemDays', sql`SELECT * FROM explorer_rollup_item_day WHERE item_key IN ('ITEM_1') AND day BETWEEN ${FIRST} AND ${TODAY}`],
    ];
    for (const [name, query] of plans) {
      const r = await sql<{ table: string; type: string; key: string | null; rows: number }>`EXPLAIN ${query}`.execute(db);
      note(`EXPLAIN ${name}: ${r.rows.map((row) => `${row.table} type=${row.type} key=${row.key} rows=${row.rows}`).join('; ')}`);
      for (const row of r.rows) expect(row.type).not.toBe('ALL');
    }
  });
});
