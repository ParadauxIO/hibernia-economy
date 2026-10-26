// Search, transaction explorer, sales feed and export queries (PAR-540) against
// the seeded MariaDB. Registered from sql.test.ts so it shares that file's single
// resetDb() and runs serially with it. Fixtures this suite adds are removed in
// afterAll; txn ids 9100+ and sale ids 9100+ are this suite's.
import { sql } from 'kysely';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { db, uuidToBin } from '@/lib/db';
import {
  countAccountsContaining,
  countFirmsContaining,
  countItemsContaining,
  countPlayersByPrefix,
  findAccountById,
  findAccountLabels,
  findAccountsByPrefix,
  findFirmsByPrefix,
  findItemNames,
  findItemsByPrefix,
  findPlayerByUuid,
  findPlayerNames,
  findPlayersByPrefix,
  findPlayerUuidsByName,
  searchAccountsContaining,
  searchFirmsContaining,
  searchItemsContaining,
} from '@/lib/sql/search';
import {
  countExplorerTransactions,
  countTxnsByPlugin,
  findFirstTxnAtOrAfter,
  findLastTxnAtOrBefore,
  findPostingsForTxns,
  listAccountMovements,
  listExplorerTransactions,
  listLedgerPlugins,
  type TxnExplorerQuery,
} from '@/lib/sql/ledger';
import { countSalesPage, findFirstSaleAtOrAfter, listSalesPage, type SalesFeedQuery } from '@/lib/sql/market';
import { inPageOrder, type KeysetPage } from '@/lib/sql/keyset';
import { searchEverything, suggest, suggestEntities } from '@/lib/services/search';
import { followTheMoney, getTransactionDetail, getTransactionsPage, resolveTxnQuery } from '@/lib/services/transactions';
import { getSalesPage } from '@/lib/services/sales';
import { parseTableState, withCursor } from '@/lib/tables/core';
import { parseFeedNav } from '@/lib/tables/feed';
import { transactionsTable } from '@/lib/tables/transactions';
import { salesTable } from '@/lib/tables/sales';
import { resetCacheForTests } from '@/lib/cache';
import { ALICE, BOB, CAROL } from './db';

const T0 = new Date('2026-03-10T12:00:00Z');
const at = (minutes: number) => new Date(T0.getTime() + minutes * 60_000);
const ids = (rows: { txn_id: number }[]) => rows.map((r) => r.txn_id);

async function page(q: TxnExplorerQuery, p: Partial<KeysetPage> = {}) {
  const kp: KeysetPage = { cursor: null, direction: 'next', dir: 'desc', limit: 50, ...p };
  return inPageOrder(await listExplorerTransactions(q, kp), kp);
}

// Fixture: a "power" plugin with five txns a minute apart, Carol → Acme business
// (#3) for 10, 20, …, 50; one with no plugin; one on "power-b". Two players play
// both roles on 9105 to prove de-duplication.
async function seedPower(): Promise<void> {
  for (let i = 1; i <= 5; i++) {
    await sql`INSERT INTO ledger_txns (txn_id, settlement_time, trade_time, message, initiator_uuid_bin, plugin_system)
      VALUES (${9100 + i}, ${at(i)}, ${at(i)}, ${`power ${i}`}, ${uuidToBin(CAROL)}, 'power')`.execute(db);
    await sql`INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (${9100 + i}, 6, ${-10 * i}), (${9100 + i}, 3, ${10 * i})`.execute(db);
  }
  await sql`UPDATE ledger_txns SET authorizer_uuid_bin = ${uuidToBin(CAROL)} WHERE txn_id = 9105`.execute(db);
  await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, plugin_system) VALUES
    (9110, ${at(10)}, 'no plugin 50% off_sale', ${uuidToBin(BOB)}, NULL),
    (9111, ${at(11)}, 'second plugin', ${uuidToBin(BOB)}, 'power-b')`.execute(db);
  await sql`INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (9110, 2, -7.00), (9110, 5, 7.00), (9111, 5, -1.00), (9111, 2, 1.00)`.execute(db);
  // A taxed transaction (V38 tax_kind = personal), before the window the other tests use.
  await sql`INSERT INTO ledger_txns (txn_id, settlement_time, message, initiator_uuid_bin, plugin_system)
    VALUES (9112, ${at(-5)}, 'Personal balance tax (weekly)', ${uuidToBin(ALICE)}, 'treasury')`.execute(db);
  await sql`INSERT INTO ledger_postings (txn_id, account_id, amount) VALUES (9112, 1, -3.00), (9112, 5, 3.00)`.execute(db);
  await sql`UPDATE economy_players SET last_login_epoch = 100 WHERE current_name = 'Alice'`.execute(db);
  await sql`INSERT INTO economy_players (player_uuid_bin, current_name, last_login_epoch) VALUES
    (UNHEX('00000000000000000000000000A11CE2'), 'Alicea', 300),
    (UNHEX('00000000000000000000000000A11CE3'), 'Alicez', 200),
    (UNHEX('0000000000000000000000000000AAAA'), '0000000000000000000000000000AAAA', 999)`.execute(db);
  await sql`INSERT INTO firm (firm_id, display_name, proprietor_uuid_bin, is_archived) VALUES (9100, 'Acme Gone', UNHEX('0000000000000000000000000000A1CE'), 1)`.execute(db);
  await sql`INSERT INTO chestshop_sale (sale_id, txn_id, occurred_at, direction, customer_uuid_bin, shop_account_id, shop_account_type, shop_firm_id, shop_owner_uuid_bin, is_admin_shop, material, item_key, item_name, item_custom, quantity, unit_price, total_price) VALUES
    (9101, NULL, ${at(1)}, 'BUY', UNHEX('0000000000000000000000000000CA01'), 3, 'BUSINESS', 1, NULL, 0, 'DIAMOND_AXE', 'Diamond Axe#7Gk', 'Fancy Axe', 1, 1, 500.0000, 500.00),
    (9102, NULL, ${at(2)}, 'SELL', UNHEX('0000000000000000000000000000CA01'), 3, 'BUSINESS', 1, NULL, 0, 'DIAMOND', 'DIAMOND', 'Diamond', 0, 2, 4.0000, 8.00)`.execute(db);
  resetCacheForTests();
}

async function cleanPower(): Promise<void> {
  await sql`DELETE FROM chestshop_sale WHERE sale_id >= 9100`.execute(db);
  await sql`DELETE FROM firm WHERE firm_id = 9100`.execute(db);
  await sql`DELETE FROM economy_players WHERE player_uuid_bin IN (UNHEX('00000000000000000000000000A11CE2'), UNHEX('00000000000000000000000000A11CE3'), UNHEX('0000000000000000000000000000AAAA'))`.execute(db);
  await sql`UPDATE economy_players SET last_login_epoch = NULL WHERE current_name = 'Alice'`.execute(db);
  await sql`DELETE FROM ledger_postings WHERE txn_id >= 9100`.execute(db);
  await sql`DELETE FROM ledger_txns WHERE txn_id >= 9100`.execute(db);
}

export function powerSuite(d: (name: string, fn: () => void) => void): void {
 d('search, transaction explorer and sales feed (PAR-540)', () => {
  beforeAll(seedPower);
  afterAll(cleanPower);
  describe('search: typeahead prefix lookups (PAR-540)', () => {
    it('ranks players exact, then most recently logged in, and skips hex placeholders', async () => {
      expect((await findPlayersByPrefix('ali', 10)).map((r) => r.label)).toEqual(['Alicea', 'Alicez', 'Alice']);
      expect((await findPlayersByPrefix('alice', 10)).map((r) => r.label)).toEqual(['Alice', 'Alicea', 'Alicez']);
      expect((await findPlayersByPrefix('ALICE', 1))[0]).toMatchObject({ kind: 'player', match: 'exact', href: '/accounts/1', player_uuid: ALICE });
      expect(await findPlayersByPrefix('0000', 10)).toEqual([]);
      expect(await countPlayersByPrefix('ali')).toBe(3);
      expect((await findPlayerByUuid(BOB))[0]).toMatchObject({ label: 'Bob', href: '/accounts/2', match: 'exact' });
    });

    it('treats LIKE wildcards in the text literally', async () => {
      expect(await findPlayersByPrefix('%', 10)).toEqual([]);
      expect(await findPlayersByPrefix('_lice', 10)).toEqual([]);
      expect(await findAccountsByPrefix('%', 10)).toEqual([]);
    });

    it('finds accounts by their own name across every type, never by a UUID name', async () => {
      expect((await findAccountsByPrefix('city', 5)).map((r) => [r.account_id, r.label, r.match])).toEqual([[5, 'City Hall', 'prefix']]);
      expect((await findAccountsByPrefix('pen', 5)).map((r) => r.account_id)).toEqual([9]); // a named personal account
      expect(await findAccountsByPrefix('0000', 5)).toEqual([]);
      expect((await findAccountById(5))[0]).toMatchObject({ label: 'City Hall', account_type: 'GOVERNMENT', href: '/accounts/5' });
      expect(await findAccountById(424242)).toEqual([]);
    });

    it('finds active firms and traded items by prefix', async () => {
      expect((await findFirmsByPrefix('acme', 5)).map((r) => [r.label, r.href])).toEqual([['Acme Corp', '/firms/Acme%20Corp']]);
      const items = await findItemsByPrefix('dia', 5);
      // Most recently traded first: the seed's DIAMOND sales are at NOW().
      expect(items.map((i) => [i.item_key, i.label])).toEqual([['DIAMOND', 'Diamond'], ['Diamond Axe#7Gk', 'Fancy Axe']]);
      expect(items[1]).toMatchObject({ href: '/chestshop/items/Diamond%20Axe%237Gk', secondary: 'Diamond Axe#7Gk' });
    });
  });

  describe('search: /search contains matches (PAR-540)', () => {
    it('matches non-personal account names, firms and item keys anywhere in the name', async () => {
      expect((await searchAccountsContaining('hall', 10, 0)).map((r) => r.account_id)).toEqual([5]);
      expect((await searchAccountsContaining('corp', 10, 0)).map((r) => r.account_id)).toEqual([3]);
      expect(await countAccountsContaining('a')).toBe(3); // Acme Corporate Account, Treasury, City Hall
      expect((await searchFirmsContaining('co', 10, 0)).map((r) => r.label)).toEqual(['Acme Corp', 'TaxFree Co']);
      expect(await countFirmsContaining('acme')).toBe(1); // the archived "Acme Gone" is excluded
      expect((await searchItemsContaining('axe', 10, 0)).map((r) => r.item_key)).toEqual(['Diamond Axe#7Gk']);
      expect(await countItemsContaining('i')).toBe(3);
    });

    it('groups, counts and ranks in the service', async () => {
      const r = await searchEverything('acme', { tab: 'all', limit: 50, offset: 0, preview: 5 });
      expect(r.accounts.rows.map((a) => a.account_id)).toEqual([3]);
      expect(r.firms.rows.map((f) => f.label)).toEqual(['Acme Corp']);
      expect(r.players.total).toBe(0);
      const byNumber = await searchEverything('#5', { tab: 'all', limit: 50, offset: 0, preview: 5 });
      expect(byNumber.accounts.rows.map((a) => [a.account_id, a.match])).toEqual([[5, 'exact']]);
      expect((await searchEverything(BOB, { tab: 'all', limit: 50, offset: 0, preview: 5 })).players.rows.map((p) => p.label)).toEqual(['Bob']);
    });

    it('suggests nothing under two characters and keeps the preview to the three original kinds', async () => {
      expect(await suggest('a')).toEqual([]);
      const preview = await suggestEntities('ac', 4);
      expect(preview.map((h) => h.kind)).toEqual(['account', 'firm']);
      const withItems = await suggest('di', { kinds: ['item'], limit: 1 });
      expect(withItems.map((h) => h.kind)).toEqual(['item']);
      expect((await suggest('#3')).map((h) => h.kind === 'account' && h.account_id)).toEqual([3]);
    });

    it('resolves chip labels and player names', async () => {
      expect((await findPlayerUuidsByName(['ALICE', 'nobody'])).get('alice')).toBe(ALICE);
      expect((await findPlayerNames([BOB])).get(BOB)).toBe('Bob');
      expect((await findAccountLabels([2, 5])).get(5)).toBe('City Hall #5');
      expect((await findAccountLabels([2])).get(2)).toBe('Bob #2');
      expect((await findItemNames(['DIAMOND'])).get('DIAMOND')).toBe('Diamond');
    });
  });

  describe('transaction explorer queries (PAR-540)', () => {
    const window = { from: at(0), to: at(60) };

    it('pages newest first by (settlement_time, txn_id) with a probe row', async () => {
      const first = await page({ ...window }, { limit: 3 });
      expect(ids(first.rows)).toEqual([9111, 9110, 9105]);
      expect(first.more).toBe(true);
      const last = first.rows[2]!;
      const second = await page({ ...window }, { limit: 3, cursor: { at: last.settlement_time, id: last.txn_id } });
      expect(ids(second.rows)).toEqual([9104, 9103, 9102]);
      const back = await page({ ...window }, { limit: 3, direction: 'prev', cursor: { at: second.rows[0]!.settlement_time, id: second.rows[0]!.txn_id } });
      expect(ids(back.rows)).toEqual([9111, 9110, 9105]);
      expect(back.more).toBe(false);
      const oldest = await page({ ...window }, { limit: 2, dir: 'asc' });
      expect(ids(oldest.rows)).toEqual([9101, 9102]);
    });

    it('drives each plugin from its own index branch, including no plugin', async () => {
      expect(ids((await page({ plugins: ['power-b', null] })).rows)).toEqual([9111, 9110]);
      expect(ids((await page({ plugins: ['power'] }, { limit: 2 })).rows)).toEqual([9105, 9104]);
    });

    it('drives an account filter from its postings and pages on txn_id', async () => {
      expect(ids((await page({ accounts: [9] })).rows)).toEqual([15, 14, 13, 12, 11, 10]);
      const p1 = await page({ accounts: [9, 6] }, { limit: 7 });
      expect(ids(p1.rows)).toEqual([9105, 9104, 9103, 9102, 9101, 15, 14]);
      const next = await page({ accounts: [9, 6] }, { limit: 7, cursor: { at: new Date(0), id: 14 } });
      expect(ids(next.rows)).toEqual([13, 12, 11, 10]);
    });

    it('matches a player as initiator or authorizer, once per transaction', async () => {
      const carol = await page({ players: [CAROL] });
      expect(ids(carol.rows)).toEqual([9105, 9104, 9103, 9102, 9101]);
      expect(carol.rows[0]).toMatchObject({ initiator_name: 'Carol', authorizer_name: 'Carol', authorizer_uuid: CAROL });
      expect(ids((await page({ players: [BOB], plugins: [null] })).rows)).toEqual([9110]);
    });

    it('filters amount exactly on the transaction volume, and account types on either side', async () => {
      expect(ids((await page({ ...window, amount: [{ op: 'gte', value: '30.00' }] })).rows)).toEqual([9105, 9104, 9103]);
      expect(ids((await page({ ...window, amount: [{ op: 'gte', value: '7.00' }, { op: 'lte', value: '7.00' }] })).rows)).toEqual([9110]);
      // Amount drives from ledger_txn_volume, joining ledger_txns only for other filters, and pages.
      expect(ids((await page({ ...window, amount: [{ op: 'gte', value: '20.00' }], message: 'power' })).rows)).toEqual([9105, 9104, 9103, 9102]);
      expect(ids((await page({ ...window, amount: [{ op: 'gte', value: '20.00' }] }, { limit: 2, cursor: { at: at(4), id: 9104 } })).rows)).toEqual([9103, 9102]);
      expect(ids((await page({ ...window, amount: [{ op: 'gte', value: '20.00' }] }, { limit: 2, dir: 'asc' })).rows)).toEqual([9102, 9103]);
      expect(ids((await page({ ...window, toTypes: ['GOVERNMENT'] })).rows)).toEqual([9110]);
      expect(ids((await page({ ...window, fromTypes: ['GOVERNMENT'], toTypes: ['PERSONAL'] })).rows)).toEqual([9111]);
      expect(ids((await page({ ...window, fromTypes: ['SYSTEM'] })).rows)).toEqual([]);
    });

    it('matches message text literally and a #txn exactly', async () => {
      expect(ids((await page({ ...window, message: '50%' })).rows)).toEqual([9110]);
      expect(ids((await page({ ...window, message: 'f_s' })).rows)).toEqual([9110]);
      expect(ids((await page({ ...window, message: 'power ' })).rows)).toHaveLength(5);
      expect(ids((await page({ txnId: 9103 })).rows)).toEqual([9103]);
      expect(ids((await page({ txnId: 9103, plugins: ['power-b'] })).rows)).toEqual([]);
    });

    it('filters by category: plugin branches for untaxed rows, tax-kind branches for Taxes', async () => {
      const recent = { from: at(-60), to: at(60) };
      expect(ids((await page({ ...recent, category: { plugins: ['power'], taxes: false } })).rows)).toEqual([9105, 9104, 9103, 9102, 9101]);
      expect(ids((await page({ category: { plugins: [], taxes: true } })).rows)).toEqual([9112]);
      // treasury is Transfers, but its taxed row is Taxes only.
      expect(ids((await page({ ...recent, category: { plugins: ['treasury'], taxes: false } })).rows)).toEqual([]);
      expect(ids((await page({ ...recent, category: { plugins: ['power', 'power-b'], taxes: true }, plugins: ['power-b', 'treasury'] })).rows)).toEqual([9111, 9112]);
      // As a residual under another driver.
      expect(ids((await page({ players: [CAROL], category: { plugins: ['power-b'], taxes: false } })).rows)).toEqual([]);
      expect(ids((await page({ players: [ALICE], category: { plugins: [], taxes: true } })).rows)).toEqual([9112]);
    });

    it('counts no further than the cap', async () => {
      expect(await countExplorerTransactions({ plugins: ['power'] }, 3)).toEqual({ count: 3, capped: true });
      expect(await countExplorerTransactions({ plugins: ['power'] }, 5)).toEqual({ count: 5, capped: false });
      expect(await countExplorerTransactions({ players: [CAROL] }, 100)).toEqual({ count: 5, capped: false });
    });

    it('reports volume, postings, plugins and window bounds', async () => {
      const [row] = (await page({ txnId: 9104 })).rows;
      expect(row).toMatchObject({ volume: '40.00', plugin_system: 'power', initiator_name: 'Carol' });
      const postings = await findPostingsForTxns([9104]);
      expect(postings.map((p) => [p.account_id, p.amount, p.account_type])).toEqual([[6, '-40.00', 'PERSONAL'], [3, '40.00', 'BUSINESS']]);
      expect((await listLedgerPlugins()).map((p) => p.plugin_system)).toEqual(expect.arrayContaining(['power', 'power-b', 'treasury', null]));
      const counts = await countTxnsByPlugin(['power', null, 'missing'], at(0));
      expect([counts.get('power'), counts.get(null), counts.get('missing')]).toEqual([5, 1, 0]);
      expect(await findFirstTxnAtOrAfter(at(2))).toMatchObject({ txn_id: 9102 });
      expect(await findLastTxnAtOrBefore(at(4))).toMatchObject({ txn_id: 9104 });
    });

    it('follows the money on the receiving account inside a txn_id window', async () => {
      const before = await listAccountMovements({ accountId: 3, txnId: 9103, direction: 'before', from: at(-60), to: at(60), limit: 10 });
      expect(before.map((m) => [m.txn_id, m.amount])).toEqual([[9102, '20.00'], [9101, '10.00']]);
      const after = await listAccountMovements({ accountId: 3, txnId: 9103, direction: 'after', from: at(-60), to: at(60), limit: 10 });
      expect(after.map((m) => m.txn_id)).toEqual([9104, 9105]);
      const detail = await getTransactionDetail(9103);
      expect(detail?.volume).toBe('30.00');
      const followed = await followTheMoney(detail!, 1, async (accountIds) => new Set(accountIds));
      expect(followed.map((f) => [f.account_id, f.received, f.before.length, f.after.length])).toEqual([[3, '30.00', 2, 2]]);
      expect(await followTheMoney(detail!, 1, async () => new Set())).toEqual([]);
    });
  });

  describe('transaction explorer service (PAR-540)', () => {
    const table = transactionsTable(['power', 'power-b', null]);
    const state = (qs: string) => parseTableState(new URLSearchParams(qs), table);

    it('resolves names, plugins and bounds per-row filters to 31 days', async () => {
      const now = new Date('2026-03-20T00:00:00Z');
      const r = await resolveTxnQuery(state('f=player:in:carol&f=plugin:in:_none,power&f=amount:gt:5'), now);
      expect(r.query.players).toEqual([CAROL]);
      expect(r.query.plugins).toEqual(['power', null]); // option order
      expect(r.query.amount).toEqual([{ op: 'gt', value: '5.00' }]);
      expect(r.window).toMatchObject({ bounded: true, implicit: true });
      expect(r.query.from?.toISOString()).toBe('2026-02-17T00:00:00.000Z');
      expect((await resolveTxnQuery(state('f=player:in:nobody'), now)).empty).toBe(true);
      expect((await resolveTxnQuery(state('q=%239103'), now)).query.txnId).toBe(9103);
      expect((await resolveTxnQuery(state('f=settled:eq:2026-03'), now)).window.bounded).toBe(false);
    });

    it('builds a page with sides, a capped count, chip labels and a working cursor', async () => {
      const now = new Date('2026-03-20T00:00:00Z');
      const s = { ...state('f=plugin:in:power'), limit: 2 };
      const p1 = await getTransactionsPage(s, parseFeedNav({}), null, now);
      expect(ids(p1.rows)).toEqual([9105, 9104]);
      expect(p1.rows[0]).toMatchObject({ from: [{ account_id: 6, amount: '-50.00' }], to: [{ account_id: 3, label: 'Acme Corporate Account' }], posting_count: 2 });
      expect(p1.position).toMatchObject({ hasPrev: false, hasNext: true });
      expect(p1.count).toEqual({ count: 5, capped: false });
      const p2 = await getTransactionsPage(withCursor(s, p1.position.last), parseFeedNav({}), p1.position.last, now);
      expect(ids(p2.rows)).toEqual([9103, 9102]);
      expect(p2.position.hasPrev).toBe(true);
      const labelled = await getTransactionsPage(state('f=account:in:5&f=player:in:' + BOB), parseFeedNav({}), null, now);
      expect(labelled.entityLabels).toEqual({ 'account:5': 'City Hall #5', [`player:${BOB}`]: 'Bob' });
      expect(labelled.mode).toBe('id');
      const jumped = await getTransactionsPage(state('f=plugin:in:power'), parseFeedNav({ at: '2026-03-09' }), null, now);
      expect(jumped.rows).toEqual([]);
    });
  });

  describe('sales feed queries (PAR-540)', () => {
    async function sales(q: SalesFeedQuery, p: Partial<KeysetPage> = {}) {
      const kp: KeysetPage = { cursor: null, direction: 'next', dir: 'desc', limit: 50, ...p };
      return inPageOrder(await listSalesPage(q, kp), kp).rows.map((s) => s.sale_id).filter((id) => id >= 9100);
    }
    const window = { from: at(0), to: at(60) };

    it('drives entity filters off their indexes and keeps feed order', async () => {
      expect(await sales({ customers: [CAROL] })).toEqual([9102, 9101]);
      expect(await sales({ items: ['diamond axe#7gk'] })).toEqual([9101]);
      expect(await sales({ items: ['DIAMOND', 'Diamond Axe#7Gk'], firms: [1] })).toEqual([9102, 9101]);
      expect(await sales({ owners: [ALICE], ...window })).toEqual([]);
    });

    it('filters direction, exact price and item names, and pages', async () => {
      expect(await sales({ ...window, direction: 'BUY' })).toEqual([9101]);
      expect(await sales({ ...window, price: [{ op: 'gt', value: '8.00' }] })).toEqual([9101]);
      expect(await sales({ ...window, itemName: 'fancy' })).toEqual([9101]);
      expect(await sales({ ...window }, { limit: 1 })).toEqual([9102]);
      expect(await sales({ ...window }, { limit: 1, cursor: { at: at(2), id: 9102 } })).toEqual([9101]);
      expect(await countSalesPage({ ...window }, 1)).toEqual({ count: 1, capped: true });
      expect(await findFirstSaleAtOrAfter(at(2))).toMatchObject({ sale_id: 9102 });
    });

    it('resolves the page state, including legacy ?customer= links', async () => {
      const now = new Date('2026-03-20T00:00:00Z');
      const s = parseTableState(new URLSearchParams(`customer=${CAROL}&f=occurred:eq:2026-03`), salesTable);
      const p = await getSalesPage(s, parseFeedNav({}), null, now);
      expect(p.rows.map((r) => r.sale_id)).toEqual([9102, 9101]);
      expect(p.rows[0]).toMatchObject({ customer_name: 'Carol', owner_name: 'Acme Corp' });
      expect(p.entityLabels).toEqual({ [`customer:${CAROL}`]: 'Carol' });
    });
  });
 });
}
