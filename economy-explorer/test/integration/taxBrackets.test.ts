import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import mysql from 'mysql2/promise';
import { sql } from 'kysely';

vi.hoisted(() => { process.env.DB_NAME = `${process.env.DB_NAME ?? 'economy_test'}_brackets`; });
import { HAS_DB, resetDb, testDbOptions } from './db';
import { db } from '@/lib/db';
import { closeRollupDb, rollupDb } from '@/lib/rollup/db';
import { recomputeDay } from '@/lib/rollup/steps';
import { selectTaxBracketRevenue } from '@/lib/sql/government';

const day = '2026-06-15';
let txnId = 9500;
async function receipt(kind: 'personal' | 'corporate', tag: string, amount: string, source = 1, destination = 5, date = day, plugin?: string) {
  const id = ++txnId;
  const message = (kind === 'personal' ? 'Personal balance tax (1d 0h @ 1%/wk)' : 'Weekly Corporate Balance Tax') + tag;
  await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, plugin_system)
    VALUES (${id}, ${date + ' 12:00:00'}, ${message}, UNHEX(REPEAT('00', 16)), ${plugin ?? (kind === 'personal' ? 'treasury' : 'business')})`.execute(db);
  await sql`INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (${id}, ${source}, ${'-' + amount}), (${id}, ${destination}, ${amount})`.execute(db);
}

beforeAll(async () => {
  if (!HAS_DB) return;
  const { database, ...server } = testDbOptions();
  const conn = await mysql.createConnection(server);
  try { await conn.query(`CREATE DATABASE IF NOT EXISTS \`${database}\``); } finally { await conn.end(); }
  await resetDb();
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name)
    VALUES (90, 'GOVERNMENT', UNHEX(REPEAT('90', 16)), 'Other treasury')`.execute(db);
  await receipt('personal', ' [bracket:100000:200000:0.01]', '0.10');
  await receipt('personal', ' [bracket:100000:200000:0.01]', '0.20');
  // Same rate, different exact bracket; changed historical rate, same bounds.
  await receipt('personal', ' [bracket:200000:300000:0.01]', '2.00');
  await receipt('personal', ' [bracket:100000:200000:0.012]', '3.00');
  await receipt('corporate', ' [bracket:500000:*:0.018]', '4.00', 3);
  await receipt('corporate', ' [bracket:500000:*:0.018]', '5.00', 4);
  await receipt('personal', '', '6.00');
  await receipt('corporate', '', '7.00', 3);
  await receipt('personal', ' [bracket:malformed]', '8.00');
  await receipt('personal', ' [bracket:100000:200000:0.01]', '100.00', 1, 90);
  await receipt('corporate', ' [bracket:500000:*:0.018]', '200.00', 90); // government transfer
  await receipt('personal', ' [bracket:100000:200000:0.01]', '300.00', 5, 1); // refund/debit
  await receipt('personal', ' [bracket:100000:200000:0.01]', '400.00', 1, 5, '2026-06-14');
  await receipt('personal', ' [bracket:100000:200000:0.01]', '500.00', 1, 5, day, 'other');
  await recomputeDay(rollupDb(), 'tax_bracket_day', day);
  await recomputeDay(rollupDb(), 'tax_bracket_day', '2026-06-14');
}, 120_000);

afterAll(async () => { if (HAS_DB) await closeRollupDb(); });

(HAS_DB ? describe : describe.skip)('exact bracket receipts', () => {
  it('retains exact cents, historical brackets and legacy revenue within account/date scope', async () => {
    expect(await selectTaxBracketRevenue([5], day, day)).toEqual([
      { kind: 'corporate', bracket: '', collected: '7.00' },
      { kind: 'corporate', bracket: '500000:*:0.018', collected: '9.00' },
      { kind: 'personal', bracket: '', collected: '14.00' },
      { kind: 'personal', bracket: '100000:200000:0.01', collected: '0.30' },
      { kind: 'personal', bracket: '100000:200000:0.012', collected: '3.00' },
      { kind: 'personal', bracket: '200000:300000:0.01', collected: '2.00' },
    ]);
    expect(await selectTaxBracketRevenue([], day, day)).toEqual([]);
    expect(await selectTaxBracketRevenue([90], day, day)).toEqual([{ kind: 'personal', bracket: '100000:200000:0.01', collected: '100.00' }]);
    expect(await selectTaxBracketRevenue([5], '2026-06-14', '2026-06-14')).toEqual([{ kind: 'personal', bracket: '100000:200000:0.01', collected: '400.00' }]);
    expect(await selectTaxBracketRevenue([5], '2026-06-16', '2026-06-16')).toEqual([]);
  });
  it('recomputes idempotently without duplicating collections', async () => {
    const before = await selectTaxBracketRevenue([5], day, day);
    await recomputeDay(rollupDb(), 'tax_bracket_day', day);
    expect(await selectTaxBracketRevenue([5], day, day)).toEqual(before);
  });
});
