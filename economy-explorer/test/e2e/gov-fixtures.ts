import Module from 'node:module';
import { resolve } from 'node:path';
import mysql from 'mysql2/promise';
import { Kysely, MysqlDialect } from 'kysely';
import { createPool } from 'mysql2';
import { testDbOptions } from '../integration/db';

// Government workspace fixtures for the e2e suite (PAR-539), loaded by
// global-setup after the shared seed. They live apart from seed.sql, and the
// payers have their own accounts, so the seed's balances and the integration
// suite's exact assertions don't move.
//
//   * OFFICIAL holds the `government` role and VIEWER access to two of the
//     Commerce unit's three accounts: a relationship-scoped viewer with a
//     partial unit. The e2e auth shim's viewers (Alice) get no relationship
//     here, so the shared pages they test are unchanged.
//   * Interior's account, Commerce Annex and the mint are out of OFFICIAL's
//     scope; the specs check that they never appear for OFFICIAL.
//   * Rollups are built with the worker's own step functions, so the pages
//     read exactly what production would.

export const GOV_OFFICIAL = '00000000-0000-0000-0000-00000000f00d';
export const GOV_OFFICIAL_NAME = 'Official';
export const GOV_ACCOUNTS = { commerce: 201, commerceReserve: 202, commerceAnnex: 203, interior: 204, mint: 205 } as const;
export const GOV_IN_SCOPE = [201, 202];
// Accounts that never touch OFFICIAL's accounts, so their names must never reach OFFICIAL's pages.
// (The mint is out of scope too, but it paid into Commerce, and a readable
// transaction shows its counterparty, as on the transaction page.)
export const GOV_OUT_OF_SCOPE_NAMES = ['Interior Ministry', 'Commerce Annex'];

const bin = (uuid: string) => Buffer.from(uuid.replaceAll('-', ''), 'hex');
const GOV_OWNER = Buffer.from('000000000000000000000000000060AA', 'hex');
const ALICE = bin('00000000-0000-0000-0000-00000000a1ce');
const CAROL = bin('00000000-0000-0000-0000-00000000ca01');
const MERCHANT = bin('00000000-0000-0000-0000-0000000fa101');
const TRADER = bin('00000000-0000-0000-0000-0000000fa102');
const MERCHANT_ACCOUNT = 211;
const TRADER_ACCOUNT = 212;
const ACME = 3;

/** Lets this Node process load lib/rollup/steps.ts, which imports Next's `server-only` marker. */
function allowServerOnly(): void {
  const mod = Module as unknown as { _resolveFilename: (request: string, ...rest: unknown[]) => string };
  const original = mod._resolveFilename;
  const stub = resolve(process.cwd(), 'test/stubs/server-only.ts');
  mod._resolveFilename = function (this: unknown, request: string, ...rest: unknown[]) {
    return request === 'server-only' ? stub : original.call(this, request, ...rest);
  };
}

const dayAgo = (n: number) => new Date(Date.now() - n * 86_400_000).toISOString().slice(0, 10);
const at = (n: number, time: string) => `${dayAgo(n)} ${time}`;

export async function seedGovFixtures(): Promise<void> {
  const conn = await mysql.createConnection(testDbOptions());
  let txnId = 9_100;
  const txn = async (when: string, plugin: string, message: string, legs: [number, string][], initiator: Buffer = ALICE, authorizer: Buffer | null = null) => {
    const id = ++txnId;
    await conn.query('INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, authorizer_uuid_bin, plugin_system) VALUES (?, ?, ?, ?, ?, ?)',
      [id, when, message, initiator, authorizer, plugin]);
    for (const [account, amount] of legs) await conn.query('INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (?, ?, ?)', [id, account, amount]);
    return id;
  };
  try {
    const a = GOV_ACCOUNTS;
    await conn.query('INSERT INTO economy_players (player_uuid_bin, current_name) VALUES (?, ?), (?, ?), (?, ?)',
      [bin(GOV_OFFICIAL), GOV_OFFICIAL_NAME, MERCHANT, 'Merchant', TRADER, 'Trader']);
    await conn.query(`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES
      (?, 'PERSONAL', ?, '00000000-0000-0000-0000-0000000fa101'), (?, 'PERSONAL', ?, '00000000-0000-0000-0000-0000000fa102'),
      (?, 'GOVERNMENT', ?, 'Commerce Department'), (?, 'GOVERNMENT', ?, 'Commerce Reserve'), (?, 'GOVERNMENT', ?, 'Commerce Annex'),
      (?, 'GOVERNMENT', ?, 'Interior Ministry'), (?, 'GOVERNMENT', ?, 'Gov Mint')`,
    [MERCHANT_ACCOUNT, MERCHANT, TRADER_ACCOUNT, TRADER, a.commerce, GOV_OWNER, a.commerceReserve, GOV_OWNER, a.commerceAnnex, GOV_OWNER, a.interior, GOV_OWNER, a.mint, GOV_OWNER]);
    await conn.query(`INSERT INTO account_balances_mat (account_id, balance) VALUES
      (?, 60000.00), (?, 60000.00), (?, 250000.00), (?, 90000.00), (?, 12000.00), (?, 40000.00), (?, -100000.00)`,
    [MERCHANT_ACCOUNT, TRADER_ACCOUNT, a.commerce, a.commerceReserve, a.commerceAnnex, a.interior, a.mint]);
    await conn.query("INSERT INTO explorer_role (player_uuid_bin, role) VALUES (?, 'government')", [bin(GOV_OFFICIAL)]);
    for (const id of GOV_IN_SCOPE) {
      await conn.query("INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin) VALUES (?, ?, 'VIEWER', ?)", [id, bin(GOV_OFFICIAL), ALICE]);
    }
    await conn.query(`INSERT INTO explorer_gov_unit (unit_id, slug, name, parent_unit_id, description) VALUES
      (1, 'commerce', 'Department of Commerce', NULL, 'Trade, licensing and markets'), (2, 'interior', 'Interior Ministry', NULL, NULL)`);
    await conn.query('INSERT INTO explorer_gov_unit_account (unit_id, account_id) VALUES (1, ?), (1, ?), (1, ?), (2, ?)',
      [a.commerce, a.commerceReserve, a.commerceAnnex, a.interior]);

    for (let day = 40; day >= 1; day--) {
      const tax = (20 + day).toFixed(2);
      await txn(at(day, '09:00:00'), 'ChestShop', 'ChestShop sales tax (diamond)', [[MERCHANT_ACCOUNT, `-${tax}`], [a.commerce, tax]]);
      await txn(at(day, '09:30:00'), 'business', 'Weekly Corporate Balance Tax', [[ACME, '-35.00'], [a.commerce, '35.00']]);
      await txn(at(day, '10:00:00'), 'treasury', 'Personal balance tax (24h 0m @ 1.0%/wk)', [[TRADER_ACCOUNT, '-12.50'], [a.interior, '12.50']]);
      if (day % 3 === 0) {
        for (const time of ['12:00:00', '12:15:00', '12:30:00']) {
          await txn(at(day, time), 'treasury-salary', 'Salary', [[a.commerce, '-150.00'], [day % 2 ? MERCHANT_ACCOUNT : TRADER_ACCOUNT, '150.00']]);
        }
      }
    }
    await txn(at(6, '14:00:00'), 'Treasury-Core', 'Admin transfer: market stall grant', [[a.commerce, '-2500.00'], [ACME, '2500.00']], ALICE, CAROL);
    await txn(at(5, '15:00:00'), 'treasury', 'reserve top-up', [[a.commerce, '-10000.00'], [a.commerceReserve, '10000.00']], bin(GOV_OFFICIAL));
    await txn(at(4, '16:00:00'), 'Treasury-Core', 'mint', [[a.mint, '-75000.00'], [a.commerce, '75000.00']]);
    await txn(at(3, '11:00:00'), 'treasury', 'Interior expenses', [[a.interior, '-900.00'], [ACME, '900.00']]);
    const fines: [number, Buffer, string, string, boolean][] = [
      [a.commerce, TRADER, '250.00', 'Selling without a licence', false],
      [a.commerce, MERCHANT, '75.00', 'Unlicensed stall at spawn', true],
      [a.commerceReserve, TRADER, '1200.00', 'Price fixing', false],
      [a.interior, MERCHANT, '450.00', 'Trespass in the Interior archive', false],
    ];
    for (const [i, [account, player, amount, reason, revoked]] of fines.entries()) {
      const when = at(2 + i, '18:00:00');
      const payer = player === TRADER ? TRADER_ACCOUNT : MERCHANT_ACCOUNT;
      const id = await txn(when, 'treasury', `Fine: ${reason}`, [[payer, `-${amount}`], [account, amount]]);
      await conn.query(`INSERT INTO government_fines (player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin, issued_at, revoked)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`, [player, payer, account, amount, reason, id, bin(GOV_OFFICIAL), when, revoked ? 1 : 0]);
    }
  } finally {
    await conn.end();
  }

  allowServerOnly();
  const { ROLLUP_JOBS, recomputeDay, markHistoryComplete } = await import('../../lib/rollup/steps');
  // Like the worker's pool: UTC sessions, DECIMAL as strings.
  const pool = createPool({ ...testDbOptions(), timezone: 'Z', decimalNumbers: false });
  pool.on('connection', (c) => { c.query("SET time_zone = '+00:00'", () => {}); });
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const rollup = new Kysely<any>({ dialect: new MysqlDialect({ pool: pool as any }) });
  try {
    for (let day = 0; day <= 45; day++) for (const job of ROLLUP_JOBS) await recomputeDay(rollup, job, dayAgo(day));
    for (const job of ROLLUP_JOBS) await markHistoryComplete(rollup, job);
  } finally {
    await rollup.destroy();
  }
}
