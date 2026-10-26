import { DummyDriver, Kysely, MysqlAdapter, MysqlIntrospector, MysqlQueryCompiler, sql, type RawBuilder } from 'kysely';
import { describe, expect, it } from 'vitest';
import { accountSearchSql, accountsColumnMap, buildAccountsWhere } from '@/lib/sql/ledger';
import { applyTableState, compileTableState, defineColumnMap, likeContains } from '@/lib/sql/tableState';
import { accountsTable } from '@/lib/tables/accounts';
import { defineTable, parseTableState, type FilterState, type TableState } from '@/lib/tables/core';

// Compile-only Kysely: no connection, MySQL dialect, so we can assert on the SQL
// text and its bound parameters.
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
const compile = (frag: RawBuilder<unknown>) => frag.compile(db);
const accounts = (qs: string) => parseTableState(new URLSearchParams(qs), accountsTable);
const where = (qs: string) => compile(buildAccountsWhere({ state: accounts(qs), now: NOW }).where);

describe('accounts mapping', () => {
  it('hides archived accounts unless the state asks for them', () => {
    expect(where('')).toMatchObject({ sql: 'WHERE a.is_archived = 0', parameters: [] });
    expect(where('f=archived:eq:true')).toMatchObject({ sql: 'WHERE a.is_archived = ?', parameters: [1] });
    expect(compile(buildAccountsWhere({ state: accounts(''), includeArchived: true }).where).sql).toBe('');
  });

  it('binds every filter value and maps enum values to the stored ENUM', () => {
    const c = where('f=type:in:business,government&f=balance:gt:10k&f=created:eq:2026&f=firm:in:3');
    expect(c.sql).toBe(
      'WHERE a.is_archived = 0 AND a.account_type IN (?, ?) AND COALESCE(abm.balance, 0.00) > CAST(? AS DECIMAL(19,2))'
      + ' AND (a.created_at >= ? AND a.created_at < ?)'
      + ' AND a.account_id IN (SELECT fa.account_id FROM firm_accounts fa WHERE fa.firm_id IN (?) AND fa.removed_at IS NULL)',
    );
    expect(c.parameters).toEqual(['BUSINESS', 'GOVERNMENT', '10000.00', new Date('2026-01-01T00:00:00Z'), new Date('2027-01-01T00:00:00Z'), 3]);
  });

  it('compares money exactly as DECIMAL, past double precision', () => {
    const c = where('f=balance:between:-90071992547409.93,90071992547409.93');
    expect(c.sql).toContain('BETWEEN CAST(? AS DECIMAL(19,2)) AND CAST(? AS DECIMAL(19,2))');
    expect(c.parameters).toEqual(['-90071992547409.93', '90071992547409.93']);
  });

  it('turns a full UUID into an exact indexed owner match, not a HEX() scan', () => {
    const c = compile(accountSearchSql('00000000-0000-0000-0000-00000000A1CE')!);
    expect(c.sql).toBe('a.owner_uuid_bin = ?');
    expect(c.parameters[0]).toEqual(Buffer.from('0000000000000000000000000000a1ce', 'hex'));
    expect(where('q=0000000000000000000000000000a1ce').sql).not.toMatch(/HEX/i);
  });

  it('treats #123 as an account number and plain digits as number-or-name', () => {
    expect(compile(accountSearchSql('#123')!)).toMatchObject({ sql: 'a.account_id = ?', parameters: [123] });
    expect(compile(accountSearchSql('42')!).sql).toBe('(a.account_id = ? OR (a.display_name LIKE ? OR fp.current_name LIKE ?))');
  });

  it('escapes LIKE wildcards in name searches', () => {
    expect(likeContains('50%_off\\')).toBe('%50\\%\\_off\\\\%');
    expect(compile(accountSearchSql('a%b')!).parameters).toEqual(['%a\\%b%', '%a\\%b%']);
  });

  it('sorts with a stable tiebreak, and only ever emits ASC or DESC', () => {
    const order = (qs: string) => compile(buildAccountsWhere({ state: accounts(qs) }).orderBy).sql;
    expect(order('')).toBe('ORDER BY COALESCE(abm.balance, 0.00) DESC, a.account_id DESC');
    expect(order('sort=created')).toBe('ORDER BY a.created_at ASC, a.account_id DESC');
    expect(order('sort=-created;DROP')).toBe('ORDER BY COALESCE(abm.balance, 0.00) DESC, a.account_id DESC');
  });

  it('reports which joins the WHERE needs so the count can skip the rest', () => {
    expect(buildAccountsWhere({ state: accounts('') })).toMatchObject({ needsBalance: false, needsPlayers: false, sortsByBalance: true });
    expect(buildAccountsWhere({ state: accounts('f=balance:gt:1&q=acme&sort=name') })).toMatchObject({ needsBalance: true, needsPlayers: true, sortsByName: true });
    expect(buildAccountsWhere({ state: accounts('q=%2312') }).needsPlayers).toBe(false);
  });
});

describe('compileTableState safety', () => {
  it('ignores filters that are not declared, even if a caller hand-builds the state', () => {
    const state = { ...accounts(''), filters: [{ field: 'is_admin', op: 'eq', values: ['1'] }, { field: '__proto__', op: 'eq', values: ['x'] }] } as unknown as TableState<typeof accountsTable>;
    expect(compileTableState(state, accountsColumnMap, NOW).conditions).toEqual([]);
  });

  it('drops values that do not survive re-validation instead of inlining them', () => {
    const state = { ...accounts(''), filters: [{ field: 'balance', op: 'gt', values: ['1; DROP TABLE accounts'] }] } as TableState<typeof accountsTable>;
    expect(compileTableState(state, accountsColumnMap, NOW).conditions).toEqual([]);
  });

  it('never puts a user value in the SQL text', () => {
    const hostile = "x' OR '1'='1";
    const state = accounts(`q=${encodeURIComponent(hostile)}`);
    const c = compile(buildAccountsWhere({ state }).where);
    expect(c.sql).not.toContain(hostile);
    expect(c.parameters).toContain(`%${hostile}%`);
  });
});

// A second table with text, number, boolean and entity columns, through the
// Kysely builder.
const feed = defineTable({
  id: 'feed',
  filters: {
    memo: { label: 'Memo', kind: 'text' },
    qty: { label: 'Quantity', kind: 'number', integer: true },
    admin: { label: 'Admin shop', kind: 'boolean' },
    item: { label: 'Item', kind: 'entity', entity: 'item' },
  },
  sorts: { at: 'Time', qty: 'Quantity' },
  defaultSort: '-at',
});
const feedMap = defineColumnMap({
  table: feed,
  filters: {
    memo: { column: sql`s.memo` },
    qty: { column: sql`s.quantity` },
    admin: { column: sql`s.is_admin_shop` },
    item: { column: sql`s.item_key` },
  },
  sorts: { at: sql`s.occurred_at`, qty: sql`s.quantity` },
  tiebreak: { column: sql`s.sale_id` },
});

describe('applyTableState', () => {
  it('adds one where per filter and the order to a select builder', () => {
    const state = parseTableState(new URLSearchParams('f=memo:contains:50%&f=qty:between:10,2&f=admin:eq:no&f=item:in:diamond,iron_ingot&sort=qty'), feed);
    const q = applyTableState(db.selectFrom('chestshop_sale as s').select('s.sale_id'), state, feedMap, NOW).compile();
    expect(q.sql).toBe(
      'select `s`.`sale_id` from `chestshop_sale` as `s` where s.memo LIKE ? and s.quantity BETWEEN CAST(? AS DECIMAL(21,6)) AND CAST(? AS DECIMAL(21,6))'
      + ' and s.is_admin_shop = ? and s.item_key IN (?, ?) order by s.quantity asc, s.sale_id desc',
    );
    expect(q.parameters).toEqual(['%50\\%%', '2', '10', 0, 'DIAMOND', 'IRON_INGOT']);
  });

  it('compiles text equality and every comparison operator', () => {
    const ops: FilterState['op'][] = ['eq', 'gt', 'gte', 'lt', 'lte'];
    const sqls = ops.map((op) => compile(compileTableState({ ...parseTableState(new URLSearchParams(''), feed), filters: [{ field: 'qty', op, values: ['5'] }] }, feedMap).where).sql);
    expect(sqls).toEqual(['=', '>', '>=', '<', '<='].map((o) => `WHERE s.quantity ${o} CAST(? AS DECIMAL(21,6))`));
    const eq = parseTableState(new URLSearchParams('f=memo:eq:refund'), feed);
    expect(compile(compileTableState(eq, feedMap).where)).toMatchObject({ sql: 'WHERE s.memo = ?', parameters: ['refund'] });
  });
});
