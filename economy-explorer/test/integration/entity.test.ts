import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';
import mysql from 'mysql2/promise';
import { sql } from 'kysely';

// Entity pages (PAR-537): account history, players, firms, market. Real queries
// against a real MariaDB. Its own database (DB_NAME + '_entity'), because
// sql.test.ts resets the shared one and Vitest runs files in parallel.
vi.hoisted(() => {
  process.env.DB_NAME = `${process.env.DB_NAME ?? 'economy_test'}_entity`;
});
vi.mock('@/lib/redis', () => ({ redis: () => null, tenantKey: (key: string) => key }));

import { HAS_DB, resetDb, testDbOptions, ALICE, BOB, CAROL, DAVE, uuid } from './db';
import { db, uuidToBin } from '@/lib/db';
import { closeRollupDb, rollupDb } from '@/lib/rollup/db';
import { addDays, eachDay, utcDay } from '@/lib/rollup/days';
import { ROLLUP_JOBS, recomputeDay } from '@/lib/rollup/steps';
import { parseMoney, toDbString } from '@/lib/money';
import { encodeCursor } from '@/lib/pagination/cursor';
import {
  findAccountLabels, findNewerCursor, findScanEdge, findTxnBounds, listAccountHistory, listTransactionLegs, sumPostingsSince,
  type HistoryWindow,
} from '@/lib/sql/accountHistory';
import { findAccount } from '@/lib/sql/ledger';
import { countPlayers, findPlayerByName, findPlayerByUuid, listOwnedAccounts, listPlayerFirmRoles, listPlayerShops, listPlayers } from '@/lib/sql/players';
import { countFirmDirectory, findFirmForAccount, findFirmSummary, listFirmDirectory, listFirmItems, selectMonthlyFlows } from '@/lib/sql/firm';
import {
  countCatalogue, countShopDirectory, findItemIdentities, findShopItemsByPrefix, listCatalogue, listNewestShops, listShopDirectory,
  selectItemMovers, selectItemWindow, selectMarketDays,
} from '@/lib/sql/market';
import { getActivity, getAccountContext, getTransactionsPage, effectiveState } from '@/lib/services/account';
import { getFirmFinancials, monthlyStatements } from '@/lib/services/firm';
import { getPreview } from '@/lib/services/preview';
import { getExport, resolveTable } from '@/lib/export/registry';
import '@/lib/export/datasets/account-transactions';
import '@/lib/export/datasets/firm-transactions';
import { accountTxnsTable } from '@/lib/tables/accountTxns';
import { playersTable } from '@/lib/tables/players';
import { firmsTable } from '@/lib/tables/firms';
import { itemsTable } from '@/lib/tables/items';
import { shopsTable } from '@/lib/tables/shops';
import { parseTableState, type AnyTable } from '@/lib/tables/core';

const d = HAS_DB ? describe : describe.skip;

const ERIN = uuid('0000000000000000000000000000E41E');
const ACCOUNT = 40;
const TODAY = utcDay();
const D10 = addDays(TODAY, -10);
const D5 = addDays(TODAY, -5);
const D2 = addDays(TODAY, -2);

/** Table state from a query string, as the page parses it. */
function state<T extends AnyTable>(table: T, qs = '') {
  return parseTableState(new URLSearchParams(qs), table);
}
function txnState(qs = '') {
  return state(accountTxnsTable, qs);
}

const kinds: Record<number, string> = {};

async function txn(id: number, when: string, plugin: string | null, message: string, legs: [number, string, string?][]) {
  await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, plugin_system)
    VALUES (${id}, ${when}, ${message}, ${uuidToBin(ERIN)}, ${plugin})`.execute(db);
  for (const [account, amount, memo] of legs) {
    await sql`INSERT INTO ledger_postings (txn_id, account_id, amount, memo) VALUES (${id}, ${account}, ${amount}, ${memo ?? null})`.execute(db);
  }
}

async function sale(when: string, item: string, direction: 'BUY' | 'SELL', qty: number, unit: string, firmId: number | null = 1) {
  const total = toDbString(parseMoney(unit) * BigInt(qty));
  await sql`INSERT INTO chestshop_sale (occurred_at, direction, customer_uuid_bin, shop_account_id, shop_account_type, shop_firm_id,
      is_admin_shop, material, item_key, item_name, item_custom, quantity, unit_price, total_price)
    VALUES (${when}, ${direction}, ${uuidToBin(BOB)}, 3, 'BUSINESS', ${firmId}, 0, ${item}, ${item}, ${item.charAt(0) + item.slice(1).toLowerCase()}, 0, ${qty}, ${unit}, ${total})`.execute(db);
}

/**
 * Erin's account #40: thirty transactions over three days, txn ids rising with
 * time. Odd: transfers to Bob (#2, -5.00). Even: wages from City Hall (#5,
 * +10.00). Special: 7015 a three-leg shop purchase with a memo, 7016 a balance
 * tax, 7017 a fine, 7018 an unknown plugin.
 */
async function seedHistory() {
  await sql`INSERT INTO economy_players (player_uuid_bin, current_name, first_seen, last_seen)
    VALUES (${uuidToBin(ERIN)}, 'Erin', '2024-01-02 03:04:05', '2025-06-01 00:00:00')`.execute(db);
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES (${ACCOUNT}, 'PERSONAL', ${uuidToBin(ERIN)}, ${ERIN})`.execute(db);
  await sql`INSERT INTO account_balances_mat (account_id, balance) VALUES (${ACCOUNT}, 1000.00)`.execute(db);
  for (let i = 1; i <= 30; i++) {
    const id = 7000 + i;
    const when = i <= 10 ? `${D10} 10:${String(i).padStart(2, '0')}:00` : i <= 20 ? `${D5} 10:${String(i).padStart(2, '0')}:00` : `${D2} 10:${String(i).padStart(2, '0')}:00`;
    if (i === 15) { kinds[id] = 'shop_purchases'; await txn(id, when, 'ChestShop', 'Bought diamonds', [[ACCOUNT, '-20.00', 'diamond pick'], [3, '19.00'], [5, '1.00']]); }
    else if (i === 16) { kinds[id] = 'taxes'; await txn(id, when, 'treasury', 'Personal balance tax (24h 0m @ 1.0%/wk)', [[ACCOUNT, '-1.50'], [5, '1.50']]); }
    else if (i === 17) {
      kinds[id] = 'fines';
      await txn(id, when, 'treasury', 'Fine: littering', [[ACCOUNT, '-50.00'], [5, '50.00']]);
      await sql`INSERT INTO government_fines (player_uuid_bin, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin)
        VALUES (${uuidToBin(ERIN)}, 5, 50.00, 'littering', ${id}, ${uuidToBin(ALICE)})`.execute(db);
    }
    else if (i === 18) { kinds[id] = 'other'; await txn(id, when, 'MysteryPlugin', 'mystery', [[4, '-2.00'], [ACCOUNT, '2.00']]); }
    else if (i % 2 === 1) { kinds[id] = 'transfers'; await txn(id, when, 'Treasury-Core', `Payment from Erin to Bob #${i}`, [[ACCOUNT, '-5.00'], [2, '5.00']]); }
    else { kinds[id] = 'wages'; await txn(id, when, 'treasury-salary', 'Salary', [[5, '-10.00'], [ACCOUNT, '10.00']]); }
  }
  // Acme pays Bob (an employee) and moves money to its second account.
  await sql`INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES (41, 'BUSINESS', ${uuidToBin(ALICE)}, 'Acme Payroll')`.execute(db);
  await sql`INSERT INTO account_balances_mat (account_id, balance) VALUES (41, 0.00)`.execute(db);
  await sql`INSERT INTO firm_accounts (firm_id, account_id) VALUES (1, 41)`.execute(db);
  await txn(7100, `${D2} 12:00:00`, 'BusinessPlugin', 'Payroll', [[3, '-100.00'], [2, '100.00']]);
  await txn(7101, `${D2} 12:30:00`, 'BusinessPlugin', 'Move funds', [[3, '-40.00'], [41, '40.00']]);
}

async function seedMarket() {
  // Erin's own shop and a firm shop she happens to own the sign of.
  await sql`INSERT INTO chestshop_shop (world, sign_x, sign_y, sign_z, material, item_key, item_name, buy_price, sell_price, batch_qty, current_stock, stock_at, shop_account_id, shop_account_type, shop_firm_id, shop_owner_uuid_bin)
    VALUES ('world', 300, 64, 300, 'EMERALD', 'EMERALD', 'Emerald', 32.00, 16.00, 16, 4, NOW(), ${ACCOUNT}, 'PERSONAL', NULL, ${uuidToBin(ERIN)}),
           ('world', 301, 64, 300, 'EMERALD', 'EMERALD', 'Emerald', 40.00, NULL, 16, 64, NOW(), 3, 'BUSINESS', 1, ${uuidToBin(ERIN)})`.execute(db);
  // Emerald: 5 trades at 2.00 ten days ago... last week, and 5 at 3.00 two days ago.
  for (let i = 0; i < 5; i++) await sale(`${addDays(TODAY, -9)} 09:0${i}:00`, 'EMERALD', 'BUY', 2, '2.0000');
  for (let i = 0; i < 5; i++) await sale(`${D2} 09:0${i}:00`, 'EMERALD', 'BUY', 2, '3.0000');
  // A customer buying from Acme's shop is Acme's revenue.
  await sale(`${D2} 11:00:00`, 'DIAMOND', 'BUY', 4, '7.0000');
}

beforeAll(async () => {
  if (!HAS_DB) return;
  const { database, ...server } = testDbOptions();
  const admin = await mysql.createConnection(server);
  try { await admin.query(`CREATE DATABASE IF NOT EXISTS \`${database}\``); } finally { await admin.end(); }
  await resetDb();
  await seedHistory();
  await seedMarket();
  for (const day of eachDay({ from: D10, to: TODAY })) for (const job of ROLLUP_JOBS) await recomputeDay(rollupDb(), job, day);
  // Nothing is older than D10, so the backfill is complete (as the worker would mark it).
  await sql`UPDATE explorer_rollup_coverage SET history_complete = 1`.execute(db);
}, 180_000);

afterAll(async () => {
  if (HAS_DB) await closeRollupDb();
});

const win = (over: Partial<HistoryWindow> = {}): HistoryWindow => ({ accountId: ACCOUNT, beforeTxnId: null, minTxnId: null, limit: 5, scan: 5000, ...over });
const ids = (rows: { txn_id: number }[]) => rows.map((r) => r.txn_id);
const range = (a: number, b: number) => Array.from({ length: a - b + 1 }, (_, i) => a - i);

d('account history: bounded scans', () => {
  it('pages newest first by txn_id, one row per transaction', async () => {
    const rows = await listAccountHistory(win(), txnState());
    expect(ids(rows)).toEqual(range(7030, 7026));
    expect(rows[0]).toMatchObject({ amount: '10.00', newer: '0.00', message: 'Salary', plugin_system: 'treasury-salary' });
    // newer is the sum of every newer row in the window.
    expect(rows[1]?.newer).toBe('10.00');
    expect(rows[2]?.newer).toBe('5.00');
    const after = await listAccountHistory(win({ beforeTxnId: 7026 }), txnState());
    expect(ids(after)).toEqual(range(7025, 7021));
  });

  it('maps a date range to txn bounds with one probe per bound', async () => {
    expect(await findTxnBounds(new Date(`${D5}T00:00:00Z`), new Date(`${addDays(D5, 1)}T00:00:00Z`))).toEqual({ first: 7011, after: 7021 });
    expect(await findTxnBounds(null, null)).toEqual({ first: null, after: null });
  });

  it.each([
    ['direction:in:out', (id: number) => kinds[id] !== 'wages' && kinds[id] !== 'other'],
    ['category:in:wages', (id: number) => kinds[id] === 'wages'],
    ['category:in:transfers', (id: number) => kinds[id] === 'transfers'],
    ['category:in:taxes', (id: number) => kinds[id] === 'taxes'],
    ['category:in:fines', (id: number) => kinds[id] === 'fines'],
    ['category:in:shop_purchases', (id: number) => kinds[id] === 'shop_purchases'],
    ['category:in:other', (id: number) => kinds[id] === 'other'],
    ['counterparty:in:2', (id: number) => kinds[id] === 'transfers'],
    ['amount:gte:20', (id: number) => id === 7015 || id === 7017],
  ])('filters %s within the window', async (f, expected) => {
    const rows = await listAccountHistory(win({ limit: 50 }), txnState(`f=${f}`));
    expect(ids(rows)).toEqual(range(7030, 7001).filter(expected));
  });

  it('searches the message and this account’s memo', async () => {
    expect(ids(await listAccountHistory(win({ limit: 50 }), txnState('q=diamond')))).toEqual([7015]);
    expect(ids(await listAccountHistory(win({ limit: 50 }), txnState('f=memo:contains:littering')))).toEqual([7017]);
    expect(ids(await listAccountHistory(win({ limit: 50 }), txnState('f=memo:contains:nothing-like-this')))).toEqual([]);
    expect(ids(await listAccountHistory(win({ limit: 50 }), txnState('f=memo:contains:Fine')))).toEqual([7017]);
  });

  it('never looks past the scan window and reports where it stopped', async () => {
    const window = win({ limit: 4, scan: 5 });
    expect(await listAccountHistory(window, txnState('f=category:in:fines'))).toEqual([]);
    const edge = await findScanEdge(window);
    expect(edge).toMatchObject({ scanned: 5, oldest: 7026 });
    expect(edge.oldestAt).toBeInstanceOf(Date);
    const rows = await listAccountHistory(win({ limit: 4, scan: 5 }), txnState('f=category:in:wages'));
    expect(ids(rows)).toEqual([7030, 7028, 7026]);
    expect(rows[0]).toMatchObject({ scanned: 5, oldest_scanned: 7026 });
  });

  it('sums later postings for the running balance, up to a cap', async () => {
    expect(await sumPostingsSince(ACCOUNT, 7029, 10)).toBe('5.00');
    expect(await sumPostingsSince(ACCOUNT, 7001, 3)).toBeNull();
  });

  it('finds the cursor for the newer page', async () => {
    expect(await findNewerCursor(ACCOUNT, 7011, 10)).toBe(7021);
    expect(await findNewerCursor(ACCOUNT, 7021, 10)).toBeNull();
  });

  it('reads every leg of a page’s transactions, with names and firms', async () => {
    const legs = await listTransactionLegs([7015]);
    expect(legs.map((l) => [l.account_id, l.amount, l.memo])).toEqual([[ACCOUNT, '-20.00', 'diamond pick'], [3, '19.00', null], [5, '1.00', null]]);
    expect(legs.find((l) => l.account_id === 3)).toMatchObject({ display_name: 'Acme Corporate Account', firm_id: 1, owner_name: 'Alice' });
    expect((await findAccountLabels([2, 999])).map((a) => a.owner_name)).toEqual(['Bob']);
  });
});

d('account history: the page service', () => {
  it('places a running balance on every row and continues it across pages', async () => {
    const account = (await findAccount(ACCOUNT))!;
    const first = await getTransactionsPage(account, null, txnState('limit=10'));
    expect(first.runningBalance).toBe(true);
    expect(first.rows[0]?.balanceAfter).toBe(account.balance);
    for (let i = 1; i < first.rows.length; i++) {
      const prev = first.rows[i - 1]!;
      expect(first.rows[i]!.balanceAfter).toBe(toDbString(parseMoney(prev.balanceAfter!) - parseMoney(prev.amount)));
    }
    expect(first.next?.id).toBe(7021);
    expect(first.newer).toBeNull();
    const second = await getTransactionsPage(account, null, txnState(`limit=10&cursor=${encodeCursor(first.next!)}`));
    const last = first.rows.at(-1)!;
    expect(second.rows[0]?.txnId).toBe(7020);
    expect(second.rows[0]?.balanceAfter).toBe(toDbString(parseMoney(last.balanceAfter!) - parseMoney(last.amount)));
    expect(second.newer).toBe('first');
  });

  it('labels categories and the counterparty as the largest opposite leg', async () => {
    const account = (await findAccount(ACCOUNT))!;
    const page = await getTransactionsPage(account, null, txnState('limit=50'));
    const byId = new Map(page.rows.map((r) => [r.txnId, r]));
    for (const [id, kind] of Object.entries(kinds)) expect(byId.get(Number(id))?.category).toBe(kind);
    expect(byId.get(7015)).toMatchObject({ memo: 'diamond pick', otherLegs: 1, counterparty: { accountId: 3, name: 'Acme Corporate Account' } });
    expect(byId.get(7001)?.counterparty).toMatchObject({ accountId: 2, name: 'Bob', accountType: 'PERSONAL' });
  });

  it('limits text search without a date to recent days, and resolves chip names', async () => {
    expect(effectiveState(txnState('q=diamond')).implicitDays).toBe(30);
    expect(effectiveState(txnState('q=diamond&f=date:gte:2026')).implicitDays).toBeNull();
    const account = (await findAccount(ACCOUNT))!;
    const page = await getTransactionsPage(account, null, txnState('f=counterparty:in:2&limit=3'));
    expect(page.entityLabels).toEqual({ 'counterparty:2': 'Bob' });
    expect(page.rows.every((r) => r.counterparty?.accountId === 2)).toBe(true);
  });

  it('applies a date filter through txn bounds', async () => {
    const account = (await findAccount(ACCOUNT))!;
    const page = await getTransactionsPage(account, null, txnState(`f=date:eq:${D5}&limit=50`));
    expect(page.rows.map((r) => r.txnId)).toEqual(range(7020, 7011));
  });
});

d('players', () => {
  it('finds a player by name in any case', async () => {
    expect((await findPlayerByName('erin'))?.uuid).toBe(ERIN);
    expect((await findPlayerByName('ERIN'))?.name).toBe('Erin');
    expect(await findPlayerByName('nobody')).toBeNull();
    expect((await findPlayerByUuid(ALICE))?.name).toBe('Alice');
  });

  it('lists firm roles: proprietors without an employee row, and employees with theirs', async () => {
    expect(await listPlayerFirmRoles(ALICE)).toEqual([expect.objectContaining({ firm_name: 'Acme Corp', proprietor: true, role_name: 'Owner' })]);
    expect(await listPlayerFirmRoles(DAVE)).toEqual([expect.objectContaining({ firm_name: 'TaxFree Co', proprietor: true, role_name: null })]);
    expect(await listPlayerFirmRoles(BOB)).toEqual([expect.objectContaining({ firm_name: 'Acme Corp', proprietor: false, role_name: 'Finance' })]);
  });

  it('lists personal accounts and only personal shops', async () => {
    expect((await listOwnedAccounts(ALICE, 'PERSONAL')).map((a) => a.account_id)).toEqual([1]);
    expect((await listOwnedAccounts(ALICE, 'PERSONAL', { excludeId: 1 }))).toEqual([]);
    const shops = await listPlayerShops(ERIN, 10);
    expect(shops.total).toBe(1);
    expect(shops.rows.map((s) => s.sign_x)).toEqual([300]);
  });

  it('filters the directory by firm membership and last seen, newest first', async () => {
    const all = await listPlayers(state(playersTable), 50, 0);
    expect(all.find((p) => p.name === 'Erin')?.firm_count).toBe(0);
    expect(all.find((p) => p.name === 'Alice')?.firm_count).toBe(1);
    const inFirm = state(playersTable, 'f=hasFirm:eq:true');
    expect((await listPlayers(inFirm, 50, 0)).map((p) => p.name).sort()).toEqual(['Alice', 'Bob', 'Carol']);
    expect(await countPlayers(inFirm)).toBe(3);
    const recent = await listPlayers(state(playersTable, 'f=lastSeen:gte:-7d'), 50, 0);
    expect(recent.map((p) => p.name)).not.toContain('Erin');
    expect((await listPlayers(state(playersTable, 'q=er&sort=name'), 50, 0)).map((p) => p.name)).toEqual(['Erin']);
  });
});

d('firms', () => {
  it('summarises a firm without money, and finds the firm holding an account', async () => {
    expect(await findFirmForAccount(3)).toMatchObject({ firm_id: 1, display_name: 'Acme Corp', proprietor_name: 'Alice', staff_count: 3, account_count: 2 });
    expect(await findFirmForAccount(1)).toBeNull();
    expect(await findFirmSummary(2)).toMatchObject({ staff_count: 0, account_count: 0 });
  });

  it('filters and sorts the directory over the firm table', async () => {
    const rows = await listFirmDirectory(state(firmsTable), 50, 0);
    expect(rows.map((f) => f.display_name)).toEqual(['Acme Corp', 'TaxFree Co']);
    expect(rows[0]).toMatchObject({ staff_count: 3, account_count: 2 });
    expect((await listFirmDirectory(state(firmsTable, 'f=staff:gt:1'), 50, 0)).map((f) => f.firm_id)).toEqual([1]);
    expect(await countFirmDirectory(state(firmsTable, 'q=taxfree'))).toBe(1);
  });

  it('counts a customer buying from the firm’s shop as revenue', async () => {
    const items = await listFirmItems(1, new Date(Date.now() - 30 * 86_400_000), new Date(Date.now() + 60_000), 10);
    expect(items.find((i) => i.item_key === 'DIAMOND')).toMatchObject({ trades: 2, sold_volume: '28.00', bought_volume: '30.00' });
  });

  it('builds monthly statements from the rollups without internal transfers', async () => {
    const rows = await selectMonthlyFlows([3, 41], D10, TODAY);
    expect(rows.some((r) => r.is_internal === 1)).toBe(true);
    const { statements } = await monthlyStatements([3, 41]);
    const all = statements.flatMap((s) => [...s.revenue, ...s.costs]);
    expect(all.some((l) => l.category === 'internal')).toBe(false);
    expect(all.find((l) => l.category === 'business')?.amount).toBe('100.00');
  });

  it('lists payroll to current staff from the rollups', async () => {
    const fin = await getFirmFinancials(1, [3, 41], { from: D10, to: TODAY });
    expect(fin.payroll).toEqual([expect.objectContaining({ uuid: BOB, role: 'Finance', amount: '100.00' })]);
    expect(fin.payroll.some((p) => p.uuid === CAROL)).toBe(false);
  });
});

d('market', () => {
  it('totals items over a window and ranks movers', async () => {
    const week = await selectItemWindow(addDays(TODAY, -6), TODAY, 10, 'trades');
    expect(week.find((w) => w.item_key === 'EMERALD')).toMatchObject({ trades: 5, qty: 10, volume: '30.00', avg_price: '3.0000' });
    const movers = await selectItemMovers(addDays(TODAY, -13), addDays(TODAY, -7), addDays(TODAY, -6), TODAY, 5);
    expect(movers).toEqual([expect.objectContaining({ item_key: 'EMERALD', before_price: '2.0000', after_price: '3.0000' })]);
    expect((await selectMarketDays(D2, D2))[0]).toMatchObject({ trades: 6, items: 2 });
  });

  it('names items from their latest sale', async () => {
    expect(await findItemIdentities(['DIAMOND', 'NOPE'])).toEqual([
      expect.objectContaining({ item_key: 'DIAMOND', item_name: 'Diamond' }),
      expect.objectContaining({ item_key: 'NOPE', item_name: null }),
    ]);
  });

  it('pages the catalogue over the rollup window', async () => {
    const from = addDays(TODAY, -29);
    const rows = await listCatalogue(state(itemsTable), from, TODAY, 50, 0);
    expect(rows[0]?.item_key).toBe('EMERALD');
    expect(await countCatalogue(state(itemsTable), from, TODAY)).toBe(3);
    expect((await listCatalogue(state(itemsTable, 'q=iron ingot'), from, TODAY, 50, 0)).map((r) => r.item_key)).toEqual(['IRON_INGOT']);
    expect((await listCatalogue(state(itemsTable, 'f=price:gt:4'), from, TODAY, 50, 0)).map((r) => r.item_key)).toEqual(['DIAMOND']);
  });

  it('finds shops by side, stock and owner, cheapest first', async () => {
    const buy = await listShopDirectory(state(shopsTable, 'f=item:in:DIAMOND'), 10, 0);
    expect(buy.map((s) => s.buy_price)).toEqual(['64.00', '2.00']);
    const sell = await listShopDirectory(state(shopsTable, 'f=item:in:EMERALD&f=direction:in:sell'), 10, 0);
    expect(sell.map((s) => s.sign_x)).toEqual([300]);
    expect(await countShopDirectory(state(shopsTable, 'f=item:in:EMERALD&f=inStock:eq:true'))).toBe(1);
    expect((await listShopDirectory(state(shopsTable, 'f=owner:in:player'), 10, 0)).map((s) => s.sign_x)).toEqual([300]);
    expect((await listNewestShops(1))[0]?.sign_x).toBe(301);
    expect(await findShopItemsByPrefix('emer', 5)).toEqual([{ item_key: 'EMERALD', item_name: 'Emerald' }]);
  });
});

d('export datasets', () => {
  const viewer = (uuid: string, permissions: string[]) => ({
    anon: false as const, keycloakSub: `t-${uuid.slice(-4)}`, minecraftUuid: uuid, minecraftName: null, linked: true,
    role: 'player' as const, capabilities: ['player'], permissions,
  }) as unknown as Parameters<NonNullable<ReturnType<typeof getExport>>['authorize']>[0];

  it('exports an account’s filtered history to the people who can read it', async () => {
    const def = getExport('account-transactions')!;
    const table = await resolveTable(def);
    const s = parseTableState(new URLSearchParams(`f=account:in:${ACCOUNT}&f=category:in:wages`), table);
    expect(await def.authorize(viewer(ERIN, ['account.read']), s)).toBe(true);
    expect(await def.authorize(viewer(BOB, ['account.read']), s)).toBe(false);
    expect(await def.authorize(viewer(ERIN, ['account.read']), parseTableState(new URLSearchParams(''), table))).toBe(false);
    const first = await def.rows(s, null, { limit: 5, viewer: viewer(ERIN, ['account.read']) });
    expect(first.rows.map((r: { txnId: number }) => r.txnId)).toEqual([7030, 7028, 7026, 7024, 7022]);
    const second = await def.rows(s, first.next, { limit: 50, viewer: viewer(ERIN, ['account.read']) });
    expect(second.rows.every((r: { category: string }) => r.category === 'wages')).toBe(true);
    expect(second.next).toBeNull();
  });

  it('exports a firm’s accounts one after another, for its finance staff', async () => {
    const def = getExport('firm-transactions')!;
    const s = parseTableState(new URLSearchParams('f=firm:in:1'), await resolveTable(def));
    expect(await def.authorize(viewer(BOB, ['firm.financials.read']), s)).toBe(true);
    expect(await def.authorize(viewer(CAROL, ['firm.financials.read']), s)).toBe(false);
    const rows: { accountId: number; txnId: number }[] = [];
    let cursor = null;
    do {
      const batch = await def.rows(s, cursor, { limit: 3, viewer: viewer(BOB, ['firm.financials.read']) });
      rows.push(...batch.rows);
      cursor = batch.next;
    } while (cursor);
    expect(rows.map((r) => r.accountId)).toEqual([...rows.filter((r) => r.accountId === 3), ...rows.filter((r) => r.accountId === 41)].map((r) => r.accountId));
    expect(rows.filter((r) => r.accountId === 41).map((r) => r.txnId)).toEqual([7101]);
    expect(rows.some((r) => r.accountId === 3 && r.txnId === 7015)).toBe(true);
  });
});

d('public context and previews', () => {
  it('relates a business account to its firm and a personal one to its owner’s firms', async () => {
    const biz = await getAccountContext((await findAccount(3))!);
    expect(biz.firm?.display_name).toBe('Acme Corp');
    expect(biz.firmAccounts.map((a) => a.account_id)).toEqual([3, 41]);
    const personal = await getAccountContext((await findAccount(1))!);
    expect(personal.ownerFirms.map((f) => f.firm_name)).toEqual(['Acme Corp']);
  });

  it('summarises 30 days of activity from the rollups', async () => {
    const activity = await getActivity([ACCOUNT]);
    expect(activity.coverage.covered).toBe(true);
    expect(activity.last30.txnCount).toBe(30);
  });

  it('previews only public facts', async () => {
    const personal = await getPreview('account', '1');
    expect(personal?.rows?.map((r) => r.label)).toEqual(['Balance', 'Owner']);
    const business = await getPreview('account', '3');
    expect(business?.rows?.map((r) => r.label)).toEqual(['Balance', 'Held by', 'In, 30 days', 'Out, 30 days']);
    expect((await getPreview('player', 'alice'))?.title).toBe('Alice');
    expect(await getPreview('account', '99999')).toBeNull();
    expect((await getPreview('item', 'EMERALD'))?.rows).toContainEqual({ label: 'Average price, 7 days', value: '$3.00' });
  });
});
