import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';
import mysql from 'mysql2/promise';
import { sql } from 'kysely';

// Own database (DB_NAME + '_me'): sql.test.ts and rollup.test.ts reset theirs in
// parallel. Set before lib/db.ts and lib/rollup/db.ts read it at import time.
vi.hoisted(() => {
  process.env.DB_NAME = `${process.env.DB_NAME ?? 'economy_test'}_me`;
});

import { HAS_DB, resetDb, testDbOptions, ALICE, BOB, PENNY } from './db';
import { db, uuidToBin } from '@/lib/db';
import { rollupDb, closeRollupDb } from '@/lib/rollup/db';
import { addDays, eachDay, utcDay } from '@/lib/rollup/days';
import { recomputeDay } from '@/lib/rollup/steps';
import { PLUGIN_CATEGORIES, categorize, type AccountType } from '@/lib/categories';
import { findReadableAccountIds } from '@/lib/sql/ledger';
import { findPersonalBalances } from '@/lib/sql/nav';
import {
  categoryCaseSql, selectAccountRelations, selectApiKeysForOwner, selectFinesForPlayer, selectFirmRoles,
  selectMarketSummary, selectMarketTopItems, selectMarketTopPartners, selectMonthlyFlowGroups, selectPostings,
} from '@/lib/sql/me';
import { getMeOverview, meGate, myAccounts, subjectOf } from '@/lib/services/me';
import { exportMyTransactionsBatch, getMyTransactions } from '@/lib/services/meTransactions';
import { getStatement, getStatementIndex } from '@/lib/services/meStatements';
import { getMyTaxes } from '@/lib/services/meTaxes';
import { getMyFirms } from '@/lib/services/meFirms';
import { getMyMarket } from '@/lib/services/meMarket';
import { addMonths, monthFirstDay, monthOf } from '@/lib/services/meFlows';
import { parseTableState } from '@/lib/tables/core';
import { meTransactionsTable } from '@/lib/tables/me';
import { sum, toDbString } from '@/lib/money';
import { resetCacheForTests } from '@/lib/cache';
import { permissionsFor } from '../role-fixtures';
import type { Viewer } from '@/lib/auth/viewer';

const d = HAS_DB ? describe : describe.skip;

const TODAY = utcDay();
const THIS_MONTH = monthOf(TODAY);
const LAST_MONTH = addMonths(THIS_MONTH, -1);
const NOW_TS = `${TODAY} 00:00:01`;
const LAST_TS = `${monthFirstDay(LAST_MONTH)} 12:00:00`;

const alice: Viewer = {
  anon: false, keycloakSub: 'it-alice', minecraftUuid: ALICE, minecraftName: 'Alice', linked: true,
  role: 'player', capabilities: ['player'], permissions: permissionsFor('player'),
};

let nextTxn = 7000;
async function txn(when: string, plugin: string | null, message: string, legs: [number, string][], memo?: string): Promise<number> {
  const id = ++nextTxn;
  await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, plugin_system)
    VALUES (${id}, ${when}, ${message}, UNHEX(REPEAT('00', 16)), ${plugin})`.execute(db);
  for (const [account, amount] of legs) {
    await sql`INSERT INTO ledger_postings (txn_id, account_id, amount, memo) VALUES (${id}, ${account}, ${amount}, ${memo ?? null})`.execute(db);
  }
  return id;
}

/**
 * On top of seed.sql (Alice: personal #1, Acme's #3 as proprietor):
 *  (one PERSONAL account per player: uq_one_personal_per_player) #31 a TaxFree Co (firm 2) account where
 *  Alice is a Worker with VIEWER access but no finance role; MEMBER on Bob's
 *  #2; VIEWER on City Hall #5; a fresh LuckPerms group grant on #32 (government).
 */
async function seedFixtures() {
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES
    (31, 'BUSINESS', UNHEX('0000000000000000000000000000DA7E'), 'TaxFree Till'),
    (32, 'GOVERNMENT', UNHEX('0000000000000000000000000000601C'), 'Parks Department')`.execute(db);
  await sql`INSERT INTO account_balances_mat (account_id, balance) VALUES (31, 777.00), (32, 50.00)`.execute(db);
  await sql`INSERT INTO firm_accounts (firm_id, account_id) VALUES (2, 31)`.execute(db);
  await sql`INSERT INTO firm_role (role_id, firm_id, name, rank_order, is_proprietor_like, is_default) VALUES (10, 2, 'Worker', 1, 0, 1)`.execute(db);
  await sql`INSERT INTO firm_role_permission (role_id, permission) VALUES (10, 'DEFAULT'), (10, 'CHESTSHOP')`.execute(db);
  // A former spell at TaxFree Co (V39 history), then the current one.
  await sql`INSERT INTO firm_employee (firm_id, player_uuid_bin, role_id, joined_at, left_at, removed_by_uuid_bin) VALUES
    (2, ${uuidToBin(ALICE)}, 10, '2026-01-05 10:00:00', '2026-02-01 10:00:00', ${uuidToBin(BOB)}),
    (2, ${uuidToBin(ALICE)}, 10, '2026-03-01 10:00:00', NULL, NULL)`.execute(db);
  await sql`INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin) VALUES
    (31, ${uuidToBin(ALICE)}, 'VIEWER', ${uuidToBin(BOB)}),
    (2, ${uuidToBin(ALICE)}, 'MEMBER', ${uuidToBin(BOB)}),
    (5, ${uuidToBin(ALICE)}, 'VIEWER', ${uuidToBin(BOB)})`.execute(db);
  await sql`INSERT INTO account_group_access (account_id, lp_group, level, added_by_uuid_bin) VALUES (32, 'parks', 'VIEWER', ${uuidToBin(BOB)})`.execute(db);
  await sql`INSERT INTO explorer_lp_group_member (lp_group, player_uuid_bin, synced_at) VALUES ('parks', ${uuidToBin(ALICE)}, NOW())`.execute(db);

  // This month, Alice's personal accounts (#1, #30):
  await txn(NOW_TS, 'treasury-salary', 'Salary', [[5, '-60.00'], [1, '60.00']]);                     // wages in 60
  await txn(NOW_TS, 'treasury', 'move to savings', [[1, '-25.00'], [1, '25.00']], 'savings');        // internal (same account): excluded
  await txn(NOW_TS, 'treasury', 'pay acme', [[1, '-40.00'], [3, '40.00']]);                         // transfer out 40 (to a firm: counted)
  await txn(NOW_TS, 'BusinessPlugin', 'owner draw', [[3, '-15.00'], [1, '15.00']]);                // business in 15 (from a firm: counted)
  await txn(NOW_TS, 'treasury', 'Personal balance tax (24h 0m @ 1.0%/wk)', [[1, '-12.34'], [5, '12.34']]); // tax out 12.34
  const fine = await txn(NOW_TS, 'treasury', 'Fine: speeding', [[1, '-50.00'], [5, '50.00']]);       // fine out 50
  await sql`INSERT INTO government_fines (player_uuid_bin, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin, issued_at)
    VALUES (${uuidToBin(ALICE)}, 5, 50.00, 'speeding on the high street', ${fine}, ${uuidToBin(BOB)}, ${NOW_TS})`.execute(db);
  // Last month: a ChestShop sale.
  await txn(LAST_TS, 'ChestShop', 'sale', [[2, '-100.00'], [1, '100.00']]);
  // An API key and an issuer rate-limit override.
  await sql`INSERT INTO api_keys (key_type, account_id, owner_uuid_bin, jwt_id, issued_at, expires_at)
    VALUES ('PERSONAL', 1, ${uuidToBin(ALICE)}, 'jti-alice-1', ${NOW_TS}, ${`${addDays(TODAY, 180)} 00:00:00`})`.execute(db);
  await sql`INSERT INTO api_rate_limit_override (owner_uuid_bin, multiplier, note) VALUES (${uuidToBin(ALICE)}, 2.00, 'bot')`.execute(db);
  // ChestShop rows: Alice buys at Acme's shop; Bob buys at Alice's personal shop.
  await sql`INSERT INTO chestshop_sale (occurred_at, direction, customer_uuid_bin, shop_account_id, shop_account_type, shop_firm_id, shop_owner_uuid_bin, is_admin_shop, material, item_key, item_name, item_custom, quantity, unit_price, total_price) VALUES
    (${NOW_TS}, 'BUY', ${uuidToBin(ALICE)}, 3, 'BUSINESS', 1, ${uuidToBin(ALICE)}, 0, 'BREAD', 'BREAD', 'Bread', 0, 4, 1.2500, 5.00),
    (${NOW_TS}, 'BUY', ${uuidToBin(BOB)}, 1, 'PERSONAL', NULL, ${uuidToBin(ALICE)}, 0, 'APPLE', 'APPLE', 'Apple', 0, 2, 3.0000, 6.00)`.execute(db);
}

beforeAll(async () => {
  if (!HAS_DB) return;
  const { database, ...server } = testDbOptions();
  const admin = await mysql.createConnection(server);
  try { await admin.query(`CREATE DATABASE IF NOT EXISTS \`${database}\``); }
  finally { await admin.end(); }
  await resetDb();
  await seedFixtures();
  // Contiguous coverage from 100 days back through today.
  // account_day for the 90-day balance series; pair_month only for the months read.
  for (const day of eachDay({ from: addDays(TODAY, -91), to: TODAY })) {
    await recomputeDay(rollupDb(), 'account_day', day);
    if (day >= monthFirstDay(LAST_MONTH)) await recomputeDay(rollupDb(), 'pair_month', day);
  }
  resetCacheForTests();
}, 180_000);

afterAll(async () => {
  if (HAS_DB) await closeRollupDb();
});

d('relationship kinds', () => {
  it('labels every reason in one query and matches the canonical readable set exactly', async () => {
    const rows = await selectAccountRelations(ALICE);
    const kinds = (id: number) => rows.filter((r) => r.account_id === id).map((r) => `${r.kind}${r.detail ? `:${r.detail}` : ''}`).sort();
    expect(kinds(1)).toEqual(['owner']);
        expect(kinds(2)).toEqual(['member']);
    expect(kinds(5)).toEqual(['viewer']);
    expect(kinds(32)).toEqual(['group:parks']);
    expect(kinds(31)).toEqual(['viewer']);
    expect(kinds(3)).toEqual(['firm_finance:Owner', 'firm_finance:Proprietor', 'owner']);
    expect(rows.find((r) => r.account_id === 31)?.firm_name).toBe('TaxFree Co');

    // Same set as ownership ∪ account_read_access_web (what canViewAccount uses).
    const all = (await sql<{ account_id: number }>`SELECT account_id FROM accounts`.execute(db)).rows.map((r) => r.account_id);
    const canonical = (await findReadableAccountIds(all, ALICE)).sort((a, b) => a - b);
    expect([...new Set(rows.map((r) => r.account_id))].sort((a, b) => a - b)).toEqual(canonical);
  });

  it('drops a stale LuckPerms group grant, as the view does', async () => {
    await sql`UPDATE explorer_lp_group_member SET synced_at = NOW() - INTERVAL 2 HOUR WHERE lp_group = 'parks'`.execute(db);
    try {
      expect((await selectAccountRelations(ALICE)).some((r) => r.account_id === 32)).toBe(false);
    } finally {
      await sql`UPDATE explorer_lp_group_member SET synced_at = NOW() WHERE lp_group = 'parks'`.execute(db);
    }
  });

  it('lists firm roles with permissions and hire dates (current spell, first spell)', async () => {
    const roles = await selectFirmRoles(ALICE);
    const acme = roles.find((r) => r.firm_id === 1)!;
    const taxfree = roles.find((r) => r.firm_id === 2)!;
    expect(acme).toMatchObject({ is_proprietor: 1, role_name: 'Owner' });
    expect(acme.permissions).toBe('ADMIN,CHESTSHOP,DEFAULT,FINANCIAL');
    expect(taxfree).toMatchObject({ is_proprietor: 0, role_name: 'Worker', permissions: 'CHESTSHOP,DEFAULT' });
    expect(taxfree.joined_at?.toISOString().slice(0, 10)).toBe('2026-03-01');
    expect(taxfree.first_joined_at?.toISOString().slice(0, 10)).toBe('2026-01-05');
  });
});

d('/me overview money rules', () => {
  it('My balance is personal accounts only, identical to the account menu chip', async () => {
    const overview = await getMeOverview(alice, ALICE, { from: addDays(TODAY, -89), to: TODAY });
    const personal = await findPersonalBalances(ALICE);
    expect(overview.myBalance).toBe(toDbString(sum(personal)));
    const balances = (await sql<{ account_id: number; balance: string }>`SELECT account_id, balance FROM account_balances_mat WHERE account_id IN (1)`.execute(db)).rows;
    expect(overview.myBalance).toBe(toDbString(sum(balances.map((b) => b.balance))));
    expect(overview.personalCount).toBe(1);
    // Business and government money is listed in its own groups, never in "Mine".
    const mine = overview.groups.find((g) => g.kind === 'mine')!;
    expect(mine.accounts.map((a) => a.id).sort()).toEqual([1]);
    expect(mine.subtotal).toBe(overview.myBalance);
    const acme = overview.groups.find((g) => g.firmId === 1)!;
    expect(acme).toMatchObject({ kind: 'business', subtotalLabel: 'Acme Corp’s money', note: null });
    expect(acme.role).toBe('Proprietor · Owner');
    const taxfree = overview.groups.find((g) => g.firmId === 2)!;
    expect(taxfree.role).toBe('Worker');
    expect(taxfree.note).toMatch(/no finance role at TaxFree Co.*view access/);
    expect(overview.groups.find((g) => g.kind === 'shared')!.subtotal).toBeNull();
    expect(overview.groups.find((g) => g.kind === 'government')!.accounts.map((a) => a.id).sort()).toEqual([32, 5]);
  });

  it('groups only readable government accounts by unit without changing personal money', async () => {
    await sql`INSERT INTO explorer_gov_unit (unit_id, slug, name) VALUES (990, 'test-city', 'City department')`.execute(db);
    await sql`INSERT INTO explorer_gov_unit_account (unit_id, account_id) VALUES (990, 5), (990, 6)`.execute(db);
    resetCacheForTests();
    try {
      const o = await getMeOverview(alice, ALICE, { from: addDays(TODAY, -89), to: TODAY });
      const city = o.groups.find((g) => g.key === 'government-990')!;
      expect(city.title).toBe('City department');
      expect(city.accounts.map((a) => a.id)).toEqual([5]);
      expect(city.subtotal).toBe(city.accounts[0]!.balance);
      expect(o.groups.find((g) => g.key === 'government')!.accounts.map((a) => a.id)).toEqual([32]);
      expect(o.myBalance).toBe(toDbString(sum(await findPersonalBalances(ALICE))));
    } finally {
      await sql`DELETE FROM explorer_gov_unit WHERE unit_id = 990`.execute(db);
      resetCacheForTests();
    }
  });

  it('income and spending exclude internal transfers and count personal↔firm money', async () => {
    const o = await getMeOverview(alice, ALICE, { from: addDays(TODAY, -89), to: TODAY });
    const cur = o.month.current;
    // Seed: +100 split from Bob; -500 to Acme, -100 to Bob. Fixtures: +60 wages,
    // +15 owner draw from Acme, -40 to Acme, -12.34 tax, -50 fine; the 25.00
    // move between Alice's own accounts is in neither.
    expect(toDbString(cur.income)).toBe('175.00');
    expect(toDbString(cur.spending)).toBe('702.34');
    expect(toDbString(cur.internalIn)).toBe('25.00');
    expect(toDbString(cur.internalOut)).toBe('25.00');
    expect(toDbString(cur.taxPaid)).toBe('12.34');
    const cat = (c: string) => cur.categories.find((x) => x.category === c);
    expect(toDbString(cat('wages')!.inflow)).toBe('60.00');
    expect(toDbString(cat('business')!.inflow)).toBe('15.00');
    expect(toDbString(cat('fines')!.outflow)).toBe('50.00');
    expect(toDbString(o.month.previous.income)).toBe('100.00');
    expect(o.month.previous.categories[0]?.category).toBe('shop_sales');
  });

  it('sums money exactly (behaviour/0001): Penny’s 0.10 + 0.20 + 0.30 is 0.60, not a double', async () => {
    const penny: Viewer = { ...alice, keycloakSub: 'it-penny', minecraftUuid: PENNY, minecraftName: 'Penny' };
    const o = await getMeOverview(penny, PENNY, { from: addDays(TODAY, -89), to: TODAY });
    expect(o.myBalance).toBe('0.00');
    expect(toDbString(o.month.current.income)).toBe('0.60');
    expect(toDbString(o.month.current.spending)).toBe('0.60');
    expect(String([0.1, 0.2, 0.3].reduce((s, x) => s + x, 0))).not.toBe('0.6');
  });

  it('derives a balance series that ends at the current balance', async () => {
    const o = await getMeOverview(alice, ALICE, { from: addDays(TODAY, -89), to: TODAY });
    expect(o.balance.coverage.covered).toBe(true);
    expect(o.balance.points).toHaveLength(90);
    expect(o.balance.points.at(-1)!.balance).toBe(o.myBalance);
  });

  it('top sources and destinations come from pair_month, never the player’s own accounts', async () => {
    const o = await getMeOverview(alice, ALICE, { from: addDays(TODAY, -89), to: TODAY });
    expect(o.topDestinations[0]).toMatchObject({ accountId: 3, firmName: 'Acme Corp', amount: '540.00' });
    expect([...o.topSources, ...o.topDestinations].some((c) => c.accountId === 1)).toBe(false);
    expect(o.recent.length).toBeGreaterThan(0);
    expect(o.recent.length).toBeLessThanOrEqual(10);
  });

  it('shows nothing readable without account.read', async () => {
    const noRead: Viewer = { ...alice, permissions: alice.permissions.filter((p) => p !== 'account.read') };
    expect(await myAccounts(noRead, ALICE)).toEqual([]);
  });

  it('gates in order: permission, anonymous, unlinked', () => {
    expect(meGate({ ...alice, permissions: [] })).toEqual({ ok: false, kind: 'private' });
    expect(meGate({ anon: true, role: null, capabilities: [], permissions: [], keycloakSub: null, minecraftUuid: null, minecraftName: null, linked: false })).toEqual({ ok: false, kind: 'login' });
    expect(meGate({ ...alice, linked: false, minecraftUuid: null })).toEqual({ ok: false, kind: 'link' });
    expect(meGate(alice)).toMatchObject({ ok: true, uuid: ALICE });
  });
});

d('category SQL matches lib/categories', () => {
  it('categoryCaseSql agrees with categorize on every plugin, direction and counterparty', async () => {
    const plugins = [...Object.keys(PLUGIN_CATEGORIES), 'TreAsury-Core', ' ChestShop ', 'unknown-plugin'];
    const cases: { plugin: string; tax: string; amount: string; type: AccountType | null; internal: number }[] = [];
    for (const plugin of plugins) for (const amount of ['5.00', '-5.00']) for (const type of ['PERSONAL', 'BUSINESS', 'GOVERNMENT', 'SYSTEM', null] as const) {
      cases.push({ plugin, tax: '', amount, type, internal: 0 });
    }
    cases.push({ plugin: 'treasury', tax: 'personal', amount: '-1.00', type: 'GOVERNMENT', internal: 0 });
    cases.push({ plugin: 'treasury', tax: 'fine', amount: '-1.00', type: 'GOVERNMENT', internal: 0 });
    cases.push({ plugin: 'treasury', tax: '', amount: '-1.00', type: 'PERSONAL', internal: 1 });
    for (const c of cases) {
      const expr = categoryCaseSql({ pluginKey: sql`${c.plugin}`, taxKind: sql`${c.tax}`, amount: sql`CAST(${c.amount} AS DECIMAL(19,2))`, counterType: sql`${c.type}`, internal: sql`${c.internal}` });
      const r = await sql<{ c: string }>`SELECT ${expr} AS c`.execute(db);
      const expected = categorize({ pluginKey: c.plugin, taxKind: c.tax || null, counterpartyType: c.type, direction: c.amount.startsWith('-') ? 'out' : 'in', internal: c.internal === 1 });
      expect(r.rows[0]?.c, JSON.stringify(c)).toBe(expected);
    }
  });
});

d('/me/transactions', () => {
  const state = (qs: string) => parseTableState(new URLSearchParams(qs), meTransactionsTable);

  it('defaults to personal accounts, newest first, with categories and counterparties', async () => {
    const page = await getMyTransactions(alice, ALICE, state(''));
    expect(new Set(page.rows.map((r) => r.account.accountId))).toEqual(new Set([1]));
    expect(page.rows[0]!.txnId).toBeGreaterThan(page.rows.at(-1)!.txnId);
    const fine = page.rows.find((r) => r.description === 'Fine: speeding')!;
    expect(fine).toMatchObject({ category: 'fines', amount: '-50.00' });
    expect(fine.counterparty?.accountId).toBe(5);
    expect(page.rows.find((r) => r.description === 'savings')!.category).toBe('internal');
  });

  it('pages by cursor without gaps or repeats', async () => {
    const all = await getMyTransactions(alice, ALICE, state('limit=100'));
    const seen: number[] = [];
    let qs = 'limit=3';
    for (let i = 0; i < 20; i++) {
      const page = await getMyTransactions(alice, ALICE, state(qs));
      seen.push(...page.rows.map((r) => r.postingId));
      if (!page.nextCursor) break;
      qs = `limit=3&cursor=${page.nextCursor}`;
    }
    expect(seen).toEqual(all.rows.map((r) => r.postingId));
  });

  it('filters by category, direction, amount, memo and counterparty', async () => {
    const taxes = await getMyTransactions(alice, ALICE, state('f=category:in:taxes'));
    expect(taxes.rows.map((r) => r.amount)).toEqual(['-12.34']);
    expect(taxes.window).not.toBeNull();
    const inOver = await getMyTransactions(alice, ALICE, state('f=direction:in:in&f=amount:gte:60'));
    expect(inOver.rows.map((r) => r.amount).sort()).toEqual(['100.00', '100.00', '60.00']);
    const memo = await getMyTransactions(alice, ALICE, state('q=savings'));
    expect(memo.rows.map((r) => r.amount).sort()).toEqual(['-25.00', '25.00']);
    const acme = await getMyTransactions(alice, ALICE, state('f=counterparty:in:3'));
    expect(acme.rows.every((r) => r.counterparty?.accountId === 3 || r.description === 'split')).toBe(true);
    expect(acme.rows.length).toBeGreaterThanOrEqual(3);
  });

  it('export batches step past empty search windows and end with the history', async () => {
    const first = await exportMyTransactionsBatch(alice, ALICE, state('f=category:in:shop_sales'), null, 100);
    expect(first.rows.map((r) => r.amount)).toEqual(['100.00']);
    const all = await exportMyTransactionsBatch(alice, ALICE, state(''), null, 1000);
    expect(all.next).toBeNull();
    expect(all.rows.length).toBe((await getMyTransactions(alice, ALICE, state('limit=100'))).rows.length);
  });

  it('only reads accounts the player can read, and ignores others in the account chip', async () => {
    const shared = await getMyTransactions(alice, ALICE, state('f=account:in:2'));
    expect(new Set(shared.rows.map((r) => r.account.accountId))).toEqual(new Set([2]));
    const foreign = await getMyTransactions(alice, ALICE, state('f=account:in:7'));
    expect(foreign.rows).toEqual([]);
    expect(foreign.ignoredAccounts).toEqual([7]);
  });

  it('keeps every page plan on the (account_id, txn_id, amount) index', async () => {
    const accounts = (await myAccounts(alice, ALICE)).filter((a) => a.id === 1).map(subjectOf);
    // The SQL selectPostings sends, explained.
    const compiled = sql`EXPLAIN SELECT lp.posting_id FROM ledger_postings lp WHERE lp.account_id = ${1} AND lp.txn_id < ${99999} ORDER BY lp.txn_id DESC LIMIT 51`;
    const plan = (await compiled.execute(db)).rows as { key: string | null; Extra: string }[];
    expect(plan[0]?.key).toBe('idx_postings_account_txn_amount');
    expect(plan[0]?.Extra ?? '').not.toMatch(/filesort/i);
    expect((await selectPostings({ accounts, beforeTxnId: null, fromTxnId: null, conditions: [], categories: null, counterpartyIds: null, texts: [], order: 'recent', limit: 5 })).length).toBe(5);
  });
});

d('statements, taxes, firms, developer, market', () => {
  it('builds a statement that reconciles opening → flows → closing', async () => {
    const s = await getStatement(alice, ALICE, THIS_MONTH, null);
    expect(s.coverage.covered).toBe(true);
    const flows = s.steps.reduce((acc, step) => acc + BigInt(Math.round(Number(step.value) * 100)), 0n);
    expect(BigInt(Math.round(Number(s.opening) * 100)) + flows).toBe(BigInt(Math.round(Number(s.closing) * 100)));
    expect(s.closing).toBe((await getMeOverview(alice, ALICE, { from: addDays(TODAY, -89), to: TODAY })).myBalance);
    expect(s.taxPaid).toBe('12.34');
    // seed.sql's txns 1-3 settle this month but carry lower txn_ids than these
    // fixtures, so the month's txn_id window starts after them (real txn_ids are
    // monotonic). Last month's sale has a higher id but is excluded by time.
    expect(s.largest[0]!.amount).toBe('60.00');
    expect(s.largest.every((r) => r.at.toISOString().slice(0, 7) === THIS_MONTH)).toBe(true);
    // Combined statement: the 25.00 between Alice's own accounts nets to zero and is listed as such.
    expect(s.steps.filter((x) => x.key.startsWith('internal')).map((x) => x.value).sort()).toEqual(['-25.00', '25.00']);
  });

  it('builds a per-account statement and a 12-month index', async () => {
    const one = await getStatement(alice, ALICE, THIS_MONTH, 1);
    expect(one.account?.id).toBe(1);
    expect(toDbString(BigInt(Math.round(Number(one.closing) * 100)) - BigInt(Math.round(Number(one.opening) * 100)))).toBe('-527.34');
    await expect(getStatement(alice, ALICE, THIS_MONTH, 3)).rejects.toThrow();
    const index = await getStatementIndex(alice, ALICE);
    expect(index.months).toHaveLength(12);
    expect(index.months[0]!.month).toBe(THIS_MONTH);
  });

  it('lists tax paid by kind and month, fines with their transaction, and current rates', async () => {
    const t = await getMyTaxes(alice, ALICE);
    expect(t.byMonth.find((m) => m.month === THIS_MONTH)?.byKind.personal).toBe('12.34');
    expect(t.fines).toHaveLength(1);
    expect(t.fines[0]).toMatchObject({ amount: '50.00', reason: 'speeding on the high street', revoked: 0, gov_account_id: 5, issued_by_name: 'Bob' });
    expect(t.rates.balanceTax?.enabled).toBe(true);
    expect((await selectFinesForPlayer(BOB, 10)).every((f) => f.reason !== 'speeding on the high street')).toBe(true);
  });

  it('lists firms with role, permissions, hire date, and the firm’s own money only with finance access', async () => {
    const firms = await getMyFirms(alice, ALICE);
    const acme = firms.find((f) => f.firmId === 1)!;
    const taxfree = firms.find((f) => f.firmId === 2)!;
    expect(acme.finance).not.toBeNull();
    expect(acme.finance!.balance).toBe((await sql<{ b: string }>`SELECT balance AS b FROM account_balances_mat WHERE account_id = 3`.execute(db)).rows[0]!.b);
    expect(taxfree.finance).toBeNull();
    expect(taxfree.permissions).toEqual(['CHESTSHOP', 'DEFAULT']);
    expect(taxfree.hiredAt?.toISOString().slice(0, 10)).toBe('2026-03-01');
  });

  it('reads API keys without tokens, with the issuer limit', async () => {
    const keys = await selectApiKeysForOwner(ALICE);
    expect(keys).toHaveLength(1);
    expect(keys[0]).toMatchObject({ key_type: 'PERSONAL', account_id: 1, revoked: 0, rate_multiplier: '2.00' });
    expect(Object.keys(keys[0]!)).not.toContain('token');
  });

  it('sums market activity in SQL, both sides of the book, bounded by time', async () => {
    const summary = await selectMarketSummary(ALICE, null);
    const bought = summary.find((s) => s.bought === 1);
    const sold = summary.find((s) => s.bought === 0);
    // seed.sql: Bob SELLs 10 diamonds to Alice's personal shop (#1) for 50.00, so Alice bought;
    // fixtures: Alice buys bread at Acme (5.00) and Bob buys apples at Alice's shop (6.00).
    expect(bought).toMatchObject({ trades: 2, amount: '55.00' });
    expect(sold).toMatchObject({ trades: 1, amount: '6.00' });
    expect(await selectMarketSummary(ALICE, new Date(Date.now() + 86_400_000))).toEqual([]);
    const items = await selectMarketTopItems(ALICE, null, 1);
    expect(items.filter((i) => i.bought === 1).map((i) => i.item_key)).toEqual(['DIAMOND']);
    const partners = await selectMarketTopPartners(ALICE, null, 5);
    expect(partners.filter((p) => p.bought === 1).map((p) => p.partner_kind === 'firm' ? p.firm_name : p.player_name)).toEqual(['Bob', 'Acme Corp']);
    expect(partners.filter((p) => p.bought === 0).map((p) => p.player_name)).toEqual(['Bob']);
    const market = await getMyMarket(ALICE, { since: null });
    expect(market.net).toBe('-49.00');
  });

  it('monthly flow groups read only the requested days', async () => {
    const rows = await selectMonthlyFlowGroups([1], monthFirstDay(THIS_MONTH), TODAY);
    expect(new Set(rows.map((r) => r.month))).toEqual(new Set([THIS_MONTH]));
  });
});
