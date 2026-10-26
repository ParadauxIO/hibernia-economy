import { describe, it, expect, beforeAll, afterAll, beforeEach, vi } from 'vitest';
import mysql from 'mysql2/promise';
import { sql } from 'kysely';

// Economy pages on the rollups (PAR-541): the SQL behind money flow and
// taxation, and the services' coverage handling, including a fresh deploy with
// no rollup rows at all. Own database (DB_NAME + '_econ'), like rollup.test.ts,
// because sql.test.ts resets the shared one in parallel.
vi.hoisted(() => {
  process.env.DB_NAME = `${process.env.DB_NAME ?? 'economy_test'}_econ`;
});

import { HAS_DB, resetDb, testDbOptions } from './db';
import { db } from '@/lib/db';
import { rollupDb, closeRollupDb } from '@/lib/rollup/db';
import { addDays, eachDay, utcDay } from '@/lib/rollup/days';
import { ROLLUP_JOBS, markHistoryComplete, recomputeDay, snapshotWealth, type DayJob } from '@/lib/rollup/steps';
import { resetCacheForTests } from '@/lib/cache';
import { selectTypeFlows } from '@/lib/sql/moneyFlow';
import { getTaxRevenue } from '@/lib/sql/taxation';
import { summarizeTaxRevenue } from '@/lib/taxation';
import { economyDays } from '@/lib/services/rollup';
import {
  clampRange, coveredPart, jobCoverage, moneyFlowMatrix, overviewActivity, overviewHeatmap, overviewSources,
  supplyChange, wealthHistory, wealthNow,
} from '@/lib/services/economy';
import { getTaxationDashboard } from '@/lib/services/taxation';

const d = HAS_DB ? describe : describe.skip;
const TODAY = utcDay();
const last = (n: number) => ({ from: addDays(TODAY, -(n - 1)), to: TODAY });

const ROLLUP_TABLES = [
  'explorer_rollup_account_day', 'explorer_rollup_pair_month', 'explorer_rollup_pair_day', 'explorer_rollup_economy_day',
  'explorer_rollup_type_day', 'explorer_rollup_hour_heatmap', 'explorer_rollup_item_day', 'explorer_snapshot_wealth_day',
  'explorer_rollup_run', 'explorer_rollup_coverage',
];

async function clearRollups() {
  for (const table of ROLLUP_TABLES) await sql`DELETE FROM ${sql.table(table)}`.execute(db);
  resetCacheForTests();
}

async function recompute(days: string[], jobs: readonly DayJob[] = ROLLUP_JOBS) {
  for (const day of days) for (const job of jobs) await recomputeDay(rollupDb(), job, day);
  resetCacheForTests();
}

/** Rows visited by a query, from ANALYZE FORMAT=JSON (independent of machine speed). */
async function rowsVisited(run: () => Promise<unknown>): Promise<number> {
  const executed = vi.spyOn(db.getExecutor(), 'executeQuery');
  let query;
  try { await run(); query = executed.mock.calls.at(-1)![0]; } finally { executed.mockRestore(); }
  const analyzed = await db.executeQuery<{ ANALYZE: string }>({ ...query, sql: 'ANALYZE FORMAT=JSON ' + query.sql });
  const visited = (node: unknown): number => {
    if (!node || typeof node !== 'object') return 0;
    const entry = node as Record<string, unknown>;
    const reads = 'table_name' in entry ? Number(entry.r_loops ?? 0) * Number(entry.r_rows ?? 0) : 0;
    return reads + Object.values(entry).reduce<number>((sum, child) => sum + visited(child), 0);
  };
  return visited(JSON.parse(analyzed.rows[0]!.ANALYZE));
}

beforeAll(async () => {
  if (!HAS_DB) return;
  const { database, ...server } = testDbOptions();
  const admin = await mysql.createConnection(server);
  try { await admin.query(`CREATE DATABASE IF NOT EXISTS \`${database}\``); } finally { await admin.end(); }
  await resetDb();
}, 120_000);

afterAll(async () => {
  if (!HAS_DB) return;
  await closeRollupDb();
});

d('a fresh deploy with no rollup rows', () => {
  beforeAll(clearRollups);

  it('returns empty, uncovered results from every economy service without touching the ledger', async () => {
    const range = last(30);
    const executed = vi.spyOn(db.getExecutor(), 'executeQuery');
    try {
      const activity = await overviewActivity(range, { from: addDays(range.from, -30), to: addDays(range.from, -1) });
      expect(activity.coverage).toMatchObject({ covered: false, coveredFrom: null, updatedAt: null });
      expect(activity.days).toEqual([]);
      expect(activity.totals).toEqual({ txnCount: 0, volume: '0.00', avgActivePlayers: null, newPlayers: 0, days: 0 });
      expect(activity.previous).toBeNull();
      expect((await overviewHeatmap(range)).cells).toEqual([]);
      expect((await overviewSources(range)).sources).toEqual([]);
      expect((await moneyFlowMatrix(range)).flows).toEqual([]);
      expect((await supplyChange(range)).byType.every((t) => t.net === '0.00')).toBe(true);
      expect((await wealthHistory(range)).snapshots).toEqual([]);
      const tax = await getTaxationDashboard(range);
      expect(tax.coverage.covered).toBe(false);
      expect(tax.totals).toEqual({ collected: '0.00', refunded: '0.00', net: '0.00' });
      expect(tax.daily).toEqual([]);
      const statements = executed.mock.calls.map((call) => call[0].sql);
      expect(statements.filter((s) => /\bledger_(txns|postings)\b|ledger_txn_volume/.test(s))).toEqual([]);
    } finally { executed.mockRestore(); }
  });
});

d('overview, wealth and money flow on populated rollups', () => {
  beforeAll(async () => {
    await clearRollups();
    // seed.sql settles every fixture transaction NOW() (today).
    await recompute([addDays(TODAY, -1), TODAY]);
    await snapshotWealth(rollupDb(), TODAY);
    resetCacheForTests();
  });

  it('sums economy_day for the range and leaves the previous period out until it is covered', async () => {
    const range = last(2);
    const activity = await overviewActivity(range, { from: addDays(TODAY, -3), to: addDays(TODAY, -2) });
    const { data } = await economyDays(range);
    expect(activity.coverage.covered).toBe(true);
    expect(activity.totals.txnCount).toBe(data.reduce((n, day) => n + day.txnCount, 0));
    expect(activity.totals.txnCount).toBeGreaterThan(0);
    expect(activity.previous).toBeNull();
    // A 7-day range is only partly covered: the covered days come back, flagged.
    const week = await overviewActivity(last(7), last(14));
    expect(week.coverage).toMatchObject({ covered: false, coveredFrom: addDays(TODAY, -1) });
    expect(week.days.map((day) => day.day)).toEqual([addDays(TODAY, -1), TODAY]);
  });

  it('counts the previous period once backfill reaches it, and treats completed history as covered', async () => {
    for (const job of ROLLUP_JOBS) await markHistoryComplete(rollupDb(), job);
    resetCacheForTests();
    const week = await overviewActivity(last(7), { from: addDays(TODAY, -13), to: addDays(TODAY, -7) });
    expect(week.coverage.covered).toBe(true);
    expect(week.previous).toEqual({ txnCount: 0, volume: '0.00', avgActivePlayers: null, newPlayers: 0, days: 0 });
    await sql`UPDATE explorer_rollup_coverage SET history_complete = 0`.execute(db);
    resetCacheForTests();
  });

  it('groups outflows by payer and counterparty type, and the matrix uses the day index', async () => {
    // Txn 1: Alice → Acme 500. Txn 3: Bob pays 300 split 100 Alice / 200 Acme; the
    // counterparty is the largest leg (Acme). Txn 2 and Penny's: personal → personal.
    expect(await selectTypeFlows(TODAY, TODAY)).toEqual([
      { from_type: 'PERSONAL', to_type: 'BUSINESS', amount: '800.00' },
      { from_type: 'PERSONAL', to_type: 'PERSONAL', amount: '101.20' },
    ]);
    expect(await selectTypeFlows(addDays(TODAY, -9), addDays(TODAY, -2))).toEqual([]);
    const plan = (await sql<{ table: string; key: string | null }>`EXPLAIN SELECT a.account_type, d.counterparty_type, SUM(d.outflow)
      FROM explorer_rollup_account_day d FORCE INDEX (idx_erad_day) STRAIGHT_JOIN accounts a ON a.account_id = d.account_id
      WHERE d.day BETWEEN ${TODAY} AND ${TODAY} GROUP BY a.account_type, d.counterparty_type`.execute(db)).rows;
    expect(plan.map((row) => [row.table, row.key])).toEqual([['d', 'idx_erad_day'], ['a', 'PRIMARY']]);
    const matrix = await moneyFlowMatrix(last(2));
    expect(matrix.total).toBe('901.20');
    expect(matrix.coverage.covered).toBe(true);
  });

  it('leaves out transfers between an owner’s own accounts', async () => {
    // Account 10: a second City Hall account (same owner as #5). A move between them is internal.
    await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES (10, 'GOVERNMENT', UNHEX('0000000000000000000000000000601C'), 'City Hall reserve')`.execute(db);
    await sql`INSERT INTO account_balances_mat (account_id, balance) VALUES (10, 0)`.execute(db);
    await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, plugin_system) VALUES (700, NOW(), 'savings', UNHEX(REPEAT('00',16)), 'Treasury-Core')`.execute(db);
    await sql`INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (700, 5, -40.00), (700, 10, 40.00)`.execute(db);
    try {
      await recompute([TODAY], ['account_day', 'type_day']);
      expect((await selectTypeFlows(TODAY, TODAY)).find((f) => f.from_type === 'GOVERNMENT')).toBeUndefined();
      expect(await sql<{ n: number }>`SELECT COUNT(*) AS n FROM explorer_rollup_account_day WHERE account_id = 10 AND is_internal = 1`.execute(db).then((r) => Number(r.rows[0]!.n))).toBe(1);
    } finally {
      await sql`DELETE FROM ledger_postings WHERE txn_id = 700`.execute(db);
      await sql`DELETE FROM ledger_txns WHERE txn_id = 700`.execute(db);
      await sql`DELETE FROM account_balances_mat WHERE account_id = 10`.execute(db);
      await sql`DELETE FROM accounts WHERE account_id = 10`.execute(db);
      await recompute([TODAY], ['account_day', 'type_day']);
    }
  });

  it('nets supply change per type to zero and ranks sources by money paid in', async () => {
    const change = await supplyChange(last(2));
    const total = change.byType.reduce((sum, t) => sum + BigInt(t.net.replace('.', '')), 0n);
    expect(total).toBe(0n);
    expect(change.byType.find((t) => t.type === 'BUSINESS')?.net).toBe('700.00');
    expect(change.daily.map((day) => day.date)).toEqual([addDays(TODAY, -1), TODAY]);
    expect(change.bySource[0]).toMatchObject({ pluginKey: 'treasury', BUSINESS: '700.00' });
    const sources = await overviewSources(last(2), 1);
    expect(sources.sources).toEqual([{ pluginKey: 'treasury', volume: '901.20' }]);
    expect(sources.other).toBe('0.00');
  });

  it('serves the heatmap and wealth history from the rollups, and current wealth from balances', async () => {
    const heat = await overviewHeatmap(last(2));
    expect(heat.cells.reduce((n, c) => n + c.txn_count, 0)).toBeGreaterThan(0);
    expect(heat.cells.every((c) => c.dow0 >= 0 && c.dow0 <= 6 && c.hr >= 0 && c.hr <= 23)).toBe(true);
    const history = await wealthHistory(last(30));
    expect(history.snapshots.map((s) => s.day)).toEqual([TODAY]);
    expect(history.coverage).toMatchObject({ covered: false, coveredFrom: TODAY });
    const now = await wealthNow();
    // The ledger as it is: the SYSTEM mint holds a negative balance and is listed.
    expect(now.byType.find((t) => t.account_type === 'SYSTEM')?.total_balance).toMatch(/^-/);
    expect(now.gini).toBeGreaterThan(0);
  });
});

d('taxation revenue from the account_day rollup', () => {
  beforeEach(clearRollups);

  it('reads only government accounts’ rows, whatever else happened that day', async () => {
    await sql`INSERT INTO accounts (account_id, account_type, display_name, owner_uuid_bin)
      VALUES (9301, 'GOVERNMENT', 'Tax performance fixture', UNHEX(REPEAT('00',16)))`.execute(db);
    try {
      // 5,000 old taxes, 5,000 recent ordinary transfers to personal accounts, one recent tax.
      await sql`INSERT INTO ledger_txns (txn_id, message, plugin_system, initiator_uuid_bin, settlement_time)
        SELECT 930000 + seq, IF(seq <= 5000 OR seq = 10001, 'Personal balance tax (fixture)', 'Ordinary transfer'),
          'treasury', UNHEX(REPEAT('00',16)),
          IF(seq <= 5000, UTC_TIMESTAMP() - INTERVAL 60 DAY, UTC_TIMESTAMP() - INTERVAL 1 DAY)
        FROM seq_1_to_10001`.execute(db);
      await sql`INSERT INTO ledger_postings (txn_id, account_id, amount)
        SELECT 930000 + seq, IF(seq <= 5000 OR seq = 10001, 9301, 7), 1.00 FROM seq_1_to_10001`.execute(db);
      await sql`INSERT INTO ledger_postings (txn_id, account_id, amount)
        SELECT 930000 + seq, 2, -1.00 FROM seq_1_to_10001`.execute(db);
      await recompute([addDays(TODAY, -60), addDays(TODAY, -1), TODAY], ['account_day']);
      const range = last(30);
      const rows = await getTaxRevenue(range.from, range.to);
      expect(rows.filter((row) => row.account_id === 9301)).toEqual([expect.objectContaining({ kind: 'personal', collected: '1.00', refunded: '0.00' })]);
      expect(await rowsVisited(() => getTaxRevenue(range.from, range.to))).toBeLessThan(200);
    } finally {
      await sql`DELETE FROM ledger_postings WHERE txn_id BETWEEN 930001 AND 940001`.execute(db);
      await sql`DELETE FROM ledger_txns WHERE txn_id BETWEEN 930001 AND 940001`.execute(db);
      await sql`DELETE FROM accounts WHERE account_id = 9301`.execute(db);
    }
  });

  it('counts government credits once, identifies writer tags, and dates fine refunds by settlement', async () => {
    await sql`INSERT INTO accounts (account_id, account_type, display_name, is_archived, owner_uuid_bin)
      VALUES (9201, 'GOVERNMENT', 'Archived tax office', 1, UNHEX(REPEAT('00',16))), (9202, 'PERSONAL', 'Private payer', 0, UNHEX(REPEAT('00',16)))`.execute(db);
    const transfer = async (id: number, plugin: string, message: string, amount: string, age: number, reverse = false) => {
      await sql`INSERT INTO ledger_txns (txn_id, message, plugin_system, initiator_uuid_bin, settlement_time)
        VALUES (${id}, ${message}, ${plugin}, UNHEX(REPEAT('00', 16)), UTC_TIMESTAMP() - INTERVAL ${age} DAY)`.execute(db);
      await sql`INSERT INTO ledger_postings (txn_id, account_id, amount, memo) VALUES
        (${id}, 9201, ${reverse ? '-' + amount : amount}, ${message}),
        (${id}, 9202, ${reverse ? amount : '-' + amount}, ${message})`.execute(db);
    };
    try {
      await transfer(9201, 'treasury', 'Personal balance tax (1d @ 1%/wk)', '0.10', 1);
      await transfer(9202, 'business', 'Weekly Corporate Balance Tax', '0.20', 1);
      await transfer(9203, 'treasury', 'Income tax on deposit from Jobs ($10)', '0.30', 1);
      await transfer(9204, 'ChestShop', 'ChestShop sales tax (5% of 10) — private memo', '0.40', 1);
      await transfer(9205, 'treasury', 'Fine: private reason', '5.00', 10);
      await transfer(9206, 'treasury', 'Fine revoked: private reason', '5.00', 1, true);
      await transfer(9207, 'other', 'Personal balance tax (spoof)', '99.00', 1);
      await transfer(9208, 'treasury', 'Fine: no fine record', '99.00', 1);
      await transfer(9209, 'ChestShop', 'ordinary sale', '99.00', 1);
      await transfer(9210, 'treasury', 'Personal balance tax (old)', '99.00', 31);
      await transfer(9211, 'treasury', 'Personal balance tax (not a government credit)', '99.00', 1, true);
      // A second fine record sharing the one refund txn: the refund counts once.
      await sql`INSERT INTO ledger_txns (txn_id, message, plugin_system, initiator_uuid_bin, settlement_time)
        VALUES (9212, 'Fine: duplicate historic record', 'treasury', UNHEX(REPEAT('00', 16)), UTC_TIMESTAMP() - INTERVAL 10 DAY)`.execute(db);
      await sql`INSERT INTO government_fines
        (fine_id, player_uuid_bin, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin, revoked, revoke_txn_id)
        VALUES (9201, UNHEX(REPEAT('00',16)), 9201, 5, 'private reason', 9205, UNHEX(REPEAT('00',16)), 1, 9206),
               (9202, UNHEX(REPEAT('00',16)), 9201, 5, 'duplicate historic record', 9212, UNHEX(REPEAT('00',16)), 1, 9206)`.execute(db);
      await recompute(eachDay({ from: addDays(TODAY, -31), to: TODAY }), ['account_day']);
      const week = last(7);
      const rows = (await getTaxRevenue(week.from, week.to)).filter((row) => row.account_id === 9201);
      const summary = summarizeTaxRevenue(rows, week);
      expect(summary.totals).toEqual({ collected: '1.00', refunded: '5.00', net: '-4.00' });
      expect(summary.byType.map((row) => row.net)).toEqual(['0.10', '0.20', '0.30', '0.40', '-5.00']);
      expect(summary.accounts[0]?.name).toBe('Archived tax office');
      expect(summary.daily).toHaveLength(7);
      const month = last(30);
      expect(summarizeTaxRevenue((await getTaxRevenue(month.from, month.to)).filter((row) => row.account_id === 9201), month).totals)
        .toEqual({ collected: '6.00', refunded: '5.00', net: '1.00' });
      expect(Object.keys(rows[0] ?? {}).sort()).toEqual(['account_id', 'collected', 'date', 'display_name', 'kind', 'refunded']);
      // The service reports coverage and sums only covered days.
      const dashboard = await getTaxationDashboard(month);
      expect(dashboard.coverage).toMatchObject({ covered: true });
      expect(dashboard.accounts.find((a) => a.id === 9201)?.net).toBe('1.00');
    } finally {
      await sql`DELETE FROM government_fines WHERE fine_id BETWEEN 9201 AND 9202`.execute(db);
      await sql`DELETE FROM ledger_postings WHERE txn_id BETWEEN 9201 AND 9212`.execute(db);
      await sql`DELETE FROM ledger_txns WHERE txn_id BETWEEN 9201 AND 9212`.execute(db);
      await sql`DELETE FROM accounts WHERE account_id IN (9201, 9202)`.execute(db);
    }
  });
});

describe('coverage helpers', () => {
  const record = (from: string, to: string, complete = 0) => ({ account_day: { job: 'account_day', from_day: from, to_day: to, history_complete: complete, updated_at: '2026-10-26T12:05:00.000Z' } });

  it('reports the covered part of a range', () => {
    const range = { from: '2026-10-01', to: '2026-10-26' };
    const partial = jobCoverage(record('2026-10-20', '2026-10-26'), 'account_day', range);
    expect(partial).toMatchObject({ covered: false, coveredFrom: '2026-10-20' });
    expect(coveredPart(partial, range)).toEqual({ from: '2026-10-20', to: '2026-10-26' });
    expect(jobCoverage(record('2026-10-20', '2026-10-26', 1), 'account_day', range).covered).toBe(true);
    expect(coveredPart(jobCoverage({}, 'account_day', range), range)).toBeNull();
    expect(coveredPart(partial, { from: '2026-09-01', to: '2026-09-30' })).toBeNull();
  });

  it('clamps long ranges to their most recent days', () => {
    expect(clampRange({ from: '2026-01-01', to: '2026-10-26' }, 92)).toEqual({ from: '2026-07-27', to: '2026-10-26' });
    const short = { from: '2026-10-20', to: '2026-10-26' };
    expect(clampRange(short, 92)).toBe(short);
  });
});
