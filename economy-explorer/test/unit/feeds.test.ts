import { DummyDriver, Kysely, MysqlAdapter, MysqlIntrospector, MysqlQueryCompiler, sql } from 'kysely';
import { describe, expect, it } from 'vitest';
import { afterCursor, afterId, inPageOrder, orderByTime, readDir } from '@/lib/sql/keyset';
import { parseTableState, withCursor } from '@/lib/tables/core';
import { feedLinks, parseFeedNav } from '@/lib/tables/feed';
import { salesTable } from '@/lib/tables/sales';
import { scanWindow } from '@/lib/tables/scanWindow';
import { CATEGORY_OPTIONS, NO_PLUGIN, categoryPlugins, pluginCategory, pluginOptions, transactionsTable } from '@/lib/tables/transactions';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const db = new Kysely<any>({
  dialect: {
    createAdapter: () => new MysqlAdapter(),
    createDriver: () => new DummyDriver(),
    createIntrospector: (k) => new MysqlIntrospector(k),
    createQueryCompiler: () => new MysqlQueryCompiler(),
  },
});

const NOW = new Date('2026-10-26T15:30:00Z');
const AT = new Date('2026-10-20T10:00:00Z');

describe('keyset fragments', () => {
  it('writes the (time, id) seek out as a range the index can use, in either direction', () => {
    const c = afterCursor(sql`lt.settlement_time`, sql`lt.txn_id`, { at: AT, id: 77 }, 'desc').compile(db);
    expect(c.sql).toBe('(lt.settlement_time < ? OR (lt.settlement_time = ? AND lt.txn_id < ?))');
    expect(c.parameters).toEqual([AT, AT, 77]);
    expect(afterCursor(sql`t`, sql`i`, { at: AT, id: 1 }, 'asc').compile(db).sql).toBe('(t > ? OR (t = ? AND i > ?))');
    expect(afterId(sql`lp.txn_id`, { at: AT, id: 9 }, 'desc').compile(db)).toMatchObject({ sql: 'lp.txn_id < ?', parameters: [9] });
    expect(orderByTime(sql`t`, sql`i`, 'asc').compile(db).sql).toBe('ORDER BY t ASC, i ASC');
  });

  it('reads prev pages against the feed order and flips them back', () => {
    expect(readDir({ dir: 'desc', direction: 'next' })).toBe('desc');
    expect(readDir({ dir: 'desc', direction: 'prev' })).toBe('asc');
    expect(readDir({ dir: 'asc', direction: 'prev' })).toBe('desc');
    expect(inPageOrder([1, 2, 3, 4], { direction: 'next', limit: 3 })).toEqual({ rows: [1, 2, 3], more: true });
    expect(inPageOrder([5, 6], { direction: 'prev', limit: 3 })).toEqual({ rows: [6, 5], more: false });
  });
});

describe('scanWindow', () => {
  const settled = (qs: string) => parseTableState(new URLSearchParams(qs), transactionsTable([])).filters.find((f) => f.field === 'settled');

  it('passes the date filter through when no per-row filter needs a bound', () => {
    expect(scanWindow(undefined, false, 31, NOW)).toEqual({ from: null, to: null, bounded: false, implicit: false, days: 31 });
    expect(scanWindow(settled('f=settled:eq:2025'), false, 31, NOW)).toMatchObject({ from: new Date('2025-01-01T00:00:00Z'), to: new Date('2026-01-01T00:00:00Z'), bounded: false });
  });

  it('adds the last 31 days when there is no date filter', () => {
    expect(scanWindow(undefined, true, 31, NOW)).toEqual({ from: new Date('2026-09-25T15:30:00Z'), to: null, bounded: true, implicit: true, days: 31 });
  });

  it('keeps a short window and trims a long one to its last 31 days', () => {
    expect(scanWindow(settled('f=settled:eq:2026-02'), true, 31, NOW)).toEqual({ from: new Date('2026-02-01T00:00:00Z'), to: new Date('2026-03-01T00:00:00Z'), bounded: false, implicit: false, days: 31 });
    expect(scanWindow(settled('f=settled:eq:2025'), true, 31, NOW)).toEqual({ from: new Date('2025-12-01T00:00:00Z'), to: new Date('2026-01-01T00:00:00Z'), bounded: true, implicit: false, days: 31 });
    // An open-ended "since" runs to now.
    expect(scanWindow(settled('f=settled:gte:2026-01-01'), true, 31, NOW)).toMatchObject({ from: new Date('2026-09-25T15:30:00Z'), to: null, bounded: true });
  });
});

describe('feed navigation', () => {
  const table = transactionsTable([]);
  const state = parseTableState(new URLSearchParams('f=settled:gte:-7d'), table);

  it('parses back and jump params, ignoring nonsense', () => {
    expect(parseFeedNav({})).toEqual({ direction: 'next', jump: null });
    expect(parseFeedNav({ back: '1' }).direction).toBe('prev');
    expect(parseFeedNav(new URLSearchParams('at=2026-02-03'))).toEqual({
      direction: 'next',
      jump: { day: '2026-02-03', start: new Date('2026-02-03T00:00:00Z'), end: new Date('2026-02-04T00:00:00Z') },
    });
    expect(parseFeedNav({ at: '2026-02-30' }).jump).toBeNull();
    expect(parseFeedNav({ at: 'yesterday' }).jump).toBeNull();
    expect(parseFeedNav({ at: '2026-02-03', back: '1' }).direction).toBe('next');
  });

  it('links Older forward and Newer back on a newest-first feed', () => {
    const first = { at: new Date('2026-10-26T10:00:00Z'), id: 50 };
    const last = { at: new Date('2026-10-26T09:00:00Z'), id: 10 };
    const links = feedLinks('/transactions', state, table, { first, last, hasPrev: true, hasNext: true }, [['at', '2026-01-01'], ['txn', '5']]);
    const older = new URL(links.olderHref!, 'http://x');
    expect(older.searchParams.get('cursor')).toBe(withCursor(state, last).cursor);
    expect(older.searchParams.get('back')).toBeNull();
    expect(older.searchParams.get('at')).toBeNull();
    expect(older.searchParams.get('txn')).toBe('5');
    const newer = new URL(links.newerHref!, 'http://x');
    expect(newer.searchParams.get('cursor')).toBe(withCursor(state, first).cursor);
    expect(newer.searchParams.get('back')).toBe('1');
    expect(feedLinks('/transactions', state, table, { first, last, hasPrev: false, hasNext: false })).toEqual({ newerHref: null, olderHref: null });
    const asc = { ...state, sort: { field: 'settled' as const, dir: 'asc' as const } };
    expect(feedLinks('/transactions', asc, table, { first, last, hasPrev: false, hasNext: true }).newerHref).toContain('cursor=');
  });
});

describe('transactions and sales tables', () => {
  it('turns the ledger plugin list into enum options, with a value for no plugin', () => {
    expect(pluginOptions(['ChestShop', null, 'treasury-salary', 'chestshop', 'bad name!', ''])).toEqual([
      { value: 'ChestShop', label: 'ChestShop' },
      { value: NO_PLUGIN, label: 'No plugin' },
      { value: 'treasury-salary', label: 'treasury-salary' },
    ]);
    expect(pluginOptions([])).toEqual([{ value: NO_PLUGIN, label: 'No plugin' }]);
  });

  it('maps categories to the plugins whose untaxed transactions they cover', () => {
    expect(pluginCategory('ChestShop')).toBe('shop_sales');
    expect(pluginCategory('treasury-salary')).toBe('wages');
    expect(pluginCategory('Treasury-Core')).toBe('transfers');
    expect(pluginCategory(null)).toBe('transfers');
    const ledger = ['ChestShop', 'treasury-salary', 'treasury', null, 'realty', 'GriefPrevention'];
    expect(categoryPlugins(['transfers'], ledger)).toEqual({ plugins: ['treasury', null], taxes: false });
    expect(categoryPlugins(['property', 'taxes'], ledger)).toEqual({ plugins: ['realty', 'GriefPrevention'], taxes: true });
    expect(categoryPlugins(['taxes'], ledger)).toEqual({ plugins: [], taxes: true });
    expect(CATEGORY_OPTIONS.map((o) => o.value)).not.toContain('internal');
    expect(CATEGORY_OPTIONS.find((o) => o.value === 'shop_sales')?.label).toBe('Shop trades');
  });

  it('parses every chip and keeps cursor paging', () => {
    const table = transactionsTable(['ChestShop', null]);
    const s = parseTableState(new URLSearchParams('f=plugin:in:chestshop,_none&f=amount:between:1k,5k&f=toType:in:government&f=account:in:12&f=player:in:Alice&cursor=bogus&page=4'), table);
    expect(s.filters.map((f) => `${f.field}:${f.op}:${f.values.join(',')}`)).toEqual([
      'plugin:in:ChestShop,_none', 'amount:between:1000,5000', 'toType:in:government', 'account:in:12', 'player:in:Alice',
    ]);
    expect(s.cursor).toBeNull();
    expect(s.page).toBe(1);
  });

  it('accepts real ChestShop item keys and old ?customer= links on the sales feed', () => {
    const s = parseTableState(new URLSearchParams('customer=00000000-0000-0000-0000-00000000ca01&f=item:in:Diamond Axe%237Gk'), salesTable);
    expect(s.filters).toEqual([
      { field: 'customer', op: 'in', values: ['00000000-0000-0000-0000-00000000ca01'] },
      { field: 'item', op: 'in', values: ['DIAMOND AXE#7GK'] },
    ]);
    expect(parseTableState(new URLSearchParams('f=item:in:a,b'), salesTable).filters[0]?.values).toEqual(['A', 'B']);
  });
});
