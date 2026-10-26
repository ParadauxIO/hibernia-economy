import { describe, expect, it } from 'vitest';
import { encodeCursor } from '@/lib/pagination/cursor';
import { accountsTable } from '@/lib/tables/accounts';
import {
  dateBounds,
  defineTable,
  describeFilter,
  filterText,
  foreignParams,
  normaliseFilter,
  parseFilterParam,
  parseSearchInput,
  parseSearchToken,
  parseTableState,
  serializeTableState,
  tableHref,
  tokenize,
  withFilter,
  withoutFilter,
  withPage,
  withSort,
  withQuery,
  withCursor,
  stateCursor,
  cleared,
  type FilterKey,
  type FilterState,
  type TableState,
} from '@/lib/tables/core';
import { centsToDecimal, formatMoneyChip, parseMoneyInput } from '@/lib/tables/money';

// A table touching every field kind, so each normaliser and operator is covered.
const t = defineTable({
  id: 'kitchen-sink',
  filters: {
    type: { label: 'Type', kind: 'enum', options: [{ value: 'personal', label: 'Personal' }, { value: 'business', label: 'Business' }, { value: 'government', label: 'Government' }] },
    balance: { label: 'Balance', kind: 'money' },
    staff: { label: 'Staff count', kind: 'number', integer: true },
    ratio: { label: 'Ratio', kind: 'number' },
    created: { label: 'Created', kind: 'date' },
    firm: { label: 'Firm', kind: 'entity', entity: 'firm' },
    player: { label: 'Player', kind: 'entity', entity: 'player' },
    item: { label: 'Item', kind: 'entity', entity: 'item' },
    reason: { label: 'Reason', kind: 'text' },
    archived: { label: 'Archived', kind: 'boolean' },
  },
  sorts: { balance: 'Balance', created: 'Created', name: 'Name' },
  defaultSort: '-balance',
  quick: [{ label: 'Businesses', f: ['type:in:business'] }],
  noun: { one: 'row', many: 'rows' },
});

const cursorTable = defineTable({
  id: 'feed',
  filters: { amount: { label: 'Amount', kind: 'money' } },
  sorts: { time: 'Time' },
  defaultSort: '-time',
  pagination: 'cursor',
});

const NOW = new Date('2026-10-26T15:30:00Z');
const parse = (qs: string) => parseTableState(new URLSearchParams(qs), t);
const f = (field: FilterKey<typeof t>, op: FilterState['op'], ...values: string[]): FilterState<FilterKey<typeof t>> => ({ field, op, values });

describe('defineTable', () => {
  it('fills defaults and parses the default sort', () => {
    expect(t.defaultSort).toEqual({ field: 'balance', dir: 'desc' });
    expect(t.pagination).toBe('page');
    expect(t.pageSize).toBe(50);
  });

  it('rejects programmer errors at definition time', () => {
    expect(() => defineTable({ id: 'x', filters: {}, sorts: { a: 'A' }, defaultSort: '-b' as '-a' })).toThrow(/defaultSort/);
    expect(() => defineTable({ id: 'Bad Id', filters: {}, sorts: { a: 'A' }, defaultSort: 'a' })).toThrow(/bad id/);
    expect(() => defineTable({ id: 'x', filters: { 'drop table': { label: 'x', kind: 'text' } }, sorts: { a: 'A' }, defaultSort: 'a' })).toThrow(/field key/);
    expect(() => defineTable({ id: 'x', filters: { e: { label: 'E', kind: 'enum', options: [] } }, sorts: { a: 'A' }, defaultSort: 'a' })).toThrow(/no options/);
    expect(() => defineTable({ id: 'x', filters: { m: { label: 'M', kind: 'money' } }, sorts: { a: 'A' }, defaultSort: 'a', quick: [{ label: 'Bad', f: ['m:gt:lots'] }] })).toThrow(/quick filter/);
  });

  it('declares the accounts directory as the spec describes', () => {
    expect(Object.keys(accountsTable.filters)).toEqual(['type', 'balance', 'created', 'firm', 'archived']);
    expect(accountsTable.defaultSort).toEqual({ field: 'balance', dir: 'desc' });
  });
});

describe('parseTableState', () => {
  it('returns defaults for an empty URL', () => {
    expect(parse('')).toEqual({ q: '', filters: [], sort: { field: 'balance', dir: 'desc' }, page: 1, cursor: null, limit: 50 });
  });

  it('parses the URL format from the spec', () => {
    const s = parse('q=acme&f=type:in:business,government&f=balance:gt:10000&sort=-created&page=3&limit=25');
    expect(s).toEqual({
      q: 'acme',
      filters: [f('type', 'in', 'business', 'government'), f('balance', 'gt', '10000')],
      sort: { field: 'created', dir: 'desc' },
      page: 3,
      cursor: null,
      limit: 25,
    });
  });

  it('accepts Next-style records with repeated params as arrays', () => {
    const s = parseTableState({ f: ['type:in:business', 'archived:eq:yes'], sort: 'name', q: ['first', 'second'] }, t);
    expect(s.filters).toEqual([f('type', 'in', 'business'), f('archived', 'eq', 'true')]);
    expect(s.sort).toEqual({ field: 'name', dir: 'asc' });
    expect(s.q).toBe('first');
  });

  it.each([
    ['unknown field', 'f=nope:eq:1'],
    ['operator not allowed for the kind', 'f=type:gt:business'],
    ['unknown operator', 'f=balance:approx:10'],
    ['missing operator', 'f=balance:10'],
    ['empty value', 'f=balance:gt:'],
    ['enum value not an option', 'f=type:in:alien'],
    ['one bad value poisons an in list', 'f=type:in:business,alien'],
    ['between with one bound', 'f=balance:between:10'],
    ['between with three bounds', 'f=balance:between:1,2,3'],
    ['money with sub-cent precision', 'f=balance:gt:0.001'],
    ['money that is not a number', 'f=balance:gt:1e9'],
    ['integer field with a fraction', 'f=staff:gt:1.5'],
    ['impossible date', 'f=created:eq:2026-02-30'],
    ['date out of range', 'f=created:eq:1999'],
    ['relative date too far back', 'f=created:gte:-9999d'],
    ['firm id that is not an id', 'f=firm:in:0'],
    ['player name with spaces', 'f=player:in:bad name'],
    ['text with control characters', 'f=reason:contains:a%00b'],
    ['boolean that is not one', 'f=archived:eq:maybe'],
    ['prototype pollution key', 'f=__proto__:eq:1'],
    ['constructor key', 'f=constructor:eq:1'],
  ])('drops %s', (_name, qs) => {
    expect(parse(qs).filters).toEqual([]);
  });

  it('keeps good filters next to bad ones', () => {
    expect(parse('f=nope:eq:1&f=balance:gte:5k&f=type:gt:x').filters).toEqual([f('balance', 'gte', '5000')]);
  });

  it('canonicalises values', () => {
    expect(parse('f=balance:gt:$1,500.50').filters).toEqual([f('balance', 'gt', '1500.5')]);
    expect(parse('f=type:IN:Government,PERSONAL').filters).toEqual([f('type', 'in', 'personal', 'government')]);
    expect(parse('f=type:eq:business').filters).toEqual([f('type', 'in', 'business')]);
    expect(parse('f=player:in:00000000000000000000000000000A1C').filters).toEqual([f('player', 'in', '00000000-0000-0000-0000-000000000a1c')]);
    expect(parse('f=item:in:diamond').filters).toEqual([f('item', 'in', 'DIAMOND')]);
    expect(parse('f=ratio:gt:007.50').filters).toEqual([f('ratio', 'gt', '7.5')]);
    expect(parse('f=reason:contains:%20%20auction%20%20house%20').filters).toEqual([f('reason', 'contains', 'auction house')]);
    expect(parse('f=archived:eq:NO').filters).toEqual([f('archived', 'eq', 'false')]);
  });

  it('orders between bounds and collapses equal ones', () => {
    expect(parse('f=balance:between:5000,1000').filters).toEqual([f('balance', 'between', '1000', '5000')]);
    expect(parse('f=created:between:2026-03,2025').filters).toEqual([f('created', 'between', '2025', '2026-03')]);
    expect(parse('f=balance:between:5k,5000').filters).toEqual([f('balance', 'eq', '5000')]);
  });

  it('keeps text values with commas and colons whole', () => {
    expect(parse('f=reason:contains:a,b:c').filters).toEqual([f('reason', 'contains', 'a,b:c')]);
  });

  it('keeps one filter per field, the last one winning, in chip order', () => {
    expect(parse('f=balance:gt:1&f=type:in:business&f=balance:lt:9').filters).toEqual([f('type', 'in', 'business'), f('balance', 'lt', '9')]);
  });

  it('caps the number of filters and the query length', () => {
    const many = Array.from({ length: 100 }, () => 'f=balance:gt:1').join('&');
    expect(parse(many).filters).toHaveLength(1);
    expect(parse(`q=${'x'.repeat(5000)}`).q).toHaveLength(200);
    expect(parse(`f=reason:contains:${'x'.repeat(201)}`).filters).toEqual([]);
  });

  it('cleans the query of control characters and runs of whitespace', () => {
    expect(parse('q=%20a%09%0Ab%20%20c%00').q).toBe('a b c');
  });

  it.each([
    ['sort=nope', { field: 'balance', dir: 'desc' }],
    ['sort=--balance', { field: 'balance', dir: 'desc' }],
    ['sort=created', { field: 'created', dir: 'asc' }],
    ['sort=created&dir=DESC', { field: 'created', dir: 'desc' }],
  ])('sort %s', (qs, sort) => {
    expect(parse(qs).sort).toEqual(sort);
  });

  it.each(['page=0', 'page=-1', 'page=abc', 'page=1.5', 'page=999999999'])('resets a bad %s to 1', (qs) => {
    expect(parse(qs).page).toBe(1);
  });

  it('falls back to the page size when limit is out of range', () => {
    expect(parse('limit=0').limit).toBe(50);
    expect(parse('limit=201').limit).toBe(50);
    expect(parse('limit=200').limit).toBe(200);
  });

  it('honours legacy params so old links keep working', () => {
    const s = parseTableState(new URLSearchParams('type=PERSONAL&sort=balance&dir=DESC'), accountsTable);
    expect(s.filters).toEqual([f('type', 'in', 'personal')]);
    expect(s.sort).toEqual({ field: 'balance', dir: 'desc' });
    expect(parseTableState(new URLSearchParams('sort=account_id&dir=ASC'), accountsTable).sort).toEqual({ field: 'id', dir: 'asc' });
    expect(parseTableState(new URLSearchParams('type=nonsense'), accountsTable).filters).toEqual([]);
  });

  it('keeps only cursors that decode, and only on cursor tables', () => {
    const token = encodeCursor({ at: new Date('2026-10-01T00:00:00Z'), id: 42 });
    expect(parseTableState(new URLSearchParams(`cursor=${token}&page=4`), cursorTable)).toMatchObject({ cursor: token, page: 1 });
    expect(parseTableState(new URLSearchParams('cursor=not-a-cursor'), cursorTable).cursor).toBeNull();
    expect(parse(`cursor=${token}`).cursor).toBeNull();
  });

  it('never throws on hostile input', () => {
    const nasty = ['%', '%%%', '\u0000', '::::', 'f=', 'f=:', 'f=::', 'f=balance::', "f=type:in:'; DROP TABLE accounts; --", 'f=balance:gt:1;DELETE', 'sort=-', 'page=NaN', 'limit=Infinity', 'q=%F0%9F%92%A5', 'f=created:eq:2026-13', 'f=firm:in:1,,2', '&&&&', 'f=reason:contains:%E0%A4%A'];
    for (const qs of nasty) expect(() => parse(qs)).not.toThrow();
    expect(() => parseTableState({ f: undefined, q: [] }, t)).not.toThrow();
    expect(parse("f=type:in:'; DROP TABLE accounts; --").filters).toEqual([]);
  });
});

describe('serializeTableState', () => {
  it('writes the readable spec format and omits defaults', () => {
    const s = parse('q=acme corp&f=type:in:business,government&f=balance:gt:10000&sort=-created&page=2');
    expect(serializeTableState(s, t)).toBe('q=acme%20corp&f=type:in:business,government&f=balance:gt:10000&sort=-created&page=2');
    expect(serializeTableState(parse(''), t)).toBe('');
    expect(tableHref('/accounts', parse(''), t)).toBe('/accounts');
  });

  it('carries foreign params through', () => {
    const s = parse('f=type:in:business');
    const extra = foreignParams(new URLSearchParams('tab=wealth&f=x&page=3'), ['tab', 'f', 'page']);
    expect(extra).toEqual([['tab', 'wealth']]);
    expect(tableHref('/x', s, t, extra)).toBe('/x?tab=wealth&f=type:in:business');
  });

  it('escapes values that would break the query string', () => {
    const s = withFilter(parse(''), f('reason', 'contains', 'a&b=c #d'));
    expect(serializeTableState(s, t)).toBe('f=reason:contains:a%26b%3Dc%20%23d');
    expect(parse(serializeTableState(s, t)).filters).toEqual([f('reason', 'contains', 'a&b=c #d')]);
  });
});

// ── Property-style round trips ────────────────────────────────────────────

/** Deterministic PRNG so failures reproduce. */
function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 2 ** 32;
  };
}

function randomState(r: () => number): TableState<typeof t> {
  const pick = <T,>(a: readonly T[]): T => a[Math.floor(r() * a.length)] as T;
  const money = () => `${pick(['', '-'])}${Math.floor(r() * 1e7)}${pick(['', '.5', '.25', '.05'])}`;
  const date = () => pick(['2025', '2026-03', '2026-02-28', '-7d', '-3m', '-1y', '2024-12-31']);
  const candidates: (() => FilterState)[] = [
    () => f('type', 'in', ...new Set([pick(['personal', 'business', 'government']), pick(['personal', 'business', 'government'])])),
    () => f('balance', pick(['gt', 'gte', 'lt', 'lte', 'eq'] as const), money()),
    () => f('balance', 'between', money(), money()),
    () => f('staff', pick(['gt', 'lt'] as const), String(Math.floor(r() * 500))),
    () => f('created', pick(['eq', 'gte', 'lt'] as const), date()),
    () => f('firm', 'in', String(1 + Math.floor(r() * 9999))),
    () => f('reason', pick(['contains', 'eq'] as const), pick(['auction', 'a,b', 'tax: 5%', 'x_y', 'quote " mark', 'ünïcode'])),
    () => f('archived', 'eq', pick(['true', 'false'])),
  ];
  let state = parse('');
  const n = Math.floor(r() * 5);
  for (let i = 0; i < n; i++) {
    const raw = pick(candidates)();
    const norm = normaliseFilter(t, raw.field, raw.op, raw.values);
    if (norm) state = withFilter(state, norm as FilterState<'balance'>);
  }
  state = withSort(state, pick(['balance', 'created', 'name']), pick(['asc', 'desc'] as const), t);
  if (r() < 0.5) state = { ...state, q: pick(['acme', 'a b', '#12', 'uuid-ish 1234']) };
  if (r() < 0.3) state = withPage(state, 1 + Math.floor(r() * 40));
  return state;
}

describe('round trips', () => {
  it('parse(serialize(state)) === state for 2,000 random valid states', () => {
    const r = rng(534);
    for (let i = 0; i < 2000; i++) {
      const state = randomState(r);
      const qs = serializeTableState(state, t);
      expect(parse(qs), qs).toEqual(state);
    }
  });

  it('serialize(parse(url)) is a fixed point for random hostile URLs', () => {
    const r = rng(42);
    const alphabet = ['f=', 'q=', 'sort=', 'page=', '&', ':', ',', 'type', 'balance', 'created', 'in', 'gt', 'between', '-', '1', '0', 'k', '.', '%20', '%', 'business', '2026', '-7d', 'archived', 'eq', 'true', 'reason', 'contains', '"', '<script>', "'"];
    for (let i = 0; i < 3000; i++) {
      let url = '';
      const len = Math.floor(r() * 30);
      for (let j = 0; j < len; j++) url += alphabet[Math.floor(r() * alphabet.length)];
      let once: TableState;
      try {
        once = parse(url);
      } catch {
        // URLSearchParams itself never throws; parse must not either.
        throw new Error(`parse threw on ${url}`);
      }
      const canonical = serializeTableState(once, t);
      expect(serializeTableState(parse(canonical), t), url).toBe(canonical);
    }
  });
});

describe('state transitions', () => {
  it('returns to the first page on any change', () => {
    const s = withPage(parse('f=type:in:business'), 5);
    expect(withFilter(s, f('balance', 'gt', '1') as FilterState<'balance'>).page).toBe(1);
    expect(withoutFilter(s, 'type').page).toBe(1);
    expect(withSort(s, 'created', 'asc', t).page).toBe(1);
    expect(cleared(withQuery(s, 'x'))).toMatchObject({ q: '', filters: [], page: 1 });
  });

  it('moves a cursor table by keyset position and back to the first page', () => {
    const first = parseTableState(new URLSearchParams('f=amount:gt:5'), cursorTable);
    const next = withCursor(first, { at: new Date('2026-10-01T00:00:00Z'), id: 42 });
    expect(stateCursor(next)).toEqual({ at: new Date('2026-10-01T00:00:00Z'), id: 42 });
    expect(parseTableState(new URLSearchParams(serializeTableState(next, cursorTable)), cursorTable)).toEqual(next);
    expect(stateCursor(withCursor(next, null))).toBeNull();
  });

  it('ignores sorts the table does not declare', () => {
    const s = parse('');
    expect(withSort(s, 'drop', 'asc', t)).toBe(s);
  });
});

describe('search tokens', () => {
  it('tokenizes with quoted values', () => {
    expect(tokenize('a reason:"auction house"  b').map((x) => x.token)).toEqual(['a', 'reason:"auction house"', 'b']);
  });

  it.each([
    ['type:business', f('type', 'in', 'business')],
    ['Type:Business,Government', f('type', 'in', 'business', 'government')],
    ['type:in:business,government', f('type', 'in', 'business', 'government')],
    ['balance:>10000', f('balance', 'gt', '10000')],
    ['balance:>=10k', f('balance', 'gte', '10000')],
    ['balance:<$1.5m', f('balance', 'lt', '1500000')],
    ['balance:1k..5k', f('balance', 'between', '1000', '5000')],
    ['balance:between:1k,5k', f('balance', 'between', '1000', '5000')],
    ['balance:250', f('balance', 'eq', '250')],
    ['created:2026', f('created', 'eq', '2026')],
    ['created:>2026-03', f('created', 'gt', '2026-03')],
    ['created:2025..2026-06', f('created', 'between', '2025', '2026-06')],
    ['staffcount:>10', f('staff', 'gt', '10')],
    ['reason:"auction house"', f('reason', 'contains', 'auction house')],
    ['reason:=refund', f('reason', 'eq', 'refund')],
    ['archived:yes', f('archived', 'eq', 'true')],
    ['firm:12', f('firm', 'in', '12')],
  ])('%s becomes a chip', (token, filter) => {
    expect(parseSearchToken(token, t)).toEqual(filter);
  });

  it.each(['hello', 'nope:1', 'type:', ':business', 'type:alien', 'balance:>lots', 'balance:>', 'created:someday', 'http://example.com', 'firm:Acme'])('%s stays as free text', (token) => {
    expect(parseSearchToken(token, t)).toBeNull();
  });

  it('turns the spec example into two chips', () => {
    expect(parseSearchInput('type:business balance:>10000', t)).toEqual({ text: '', filters: [f('type', 'in', 'business'), f('balance', 'gt', '10000')] });
  });

  it('keeps unknown tokens as text and leaves an unfinished token alone while typing', () => {
    expect(parseSearchInput('acme type:business corp balance:>1', t, { keepTrailing: true })).toEqual({ text: 'acme corp balance:>1', filters: [f('type', 'in', 'business')] });
    expect(parseSearchInput('type:business ', t, { keepTrailing: true })).toEqual({ text: '', filters: [f('type', 'in', 'business')] });
    expect(parseSearchInput('acme ', t, { keepTrailing: true }).text).toBe('acme ');
  });
});

describe('dates', () => {
  const b = (op: FilterState['op'], ...values: string[]) => {
    const r = dateBounds(f('created', op, ...values), NOW);
    return [r.from?.toISOString() ?? null, r.to?.toISOString() ?? null];
  };

  it('turns periods into half-open UTC ranges', () => {
    expect(b('eq', '2026')).toEqual(['2026-01-01T00:00:00.000Z', '2027-01-01T00:00:00.000Z']);
    expect(b('eq', '2026-02')).toEqual(['2026-02-01T00:00:00.000Z', '2026-03-01T00:00:00.000Z']);
    expect(b('eq', '2026-12-31')).toEqual(['2026-12-31T00:00:00.000Z', '2027-01-01T00:00:00.000Z']);
    expect(b('gte', '2026-03')).toEqual(['2026-03-01T00:00:00.000Z', null]);
    expect(b('gt', '2026-03')).toEqual(['2026-04-01T00:00:00.000Z', null]);
    expect(b('lt', '2026-03')).toEqual([null, '2026-03-01T00:00:00.000Z']);
    expect(b('lte', '2026-03')).toEqual([null, '2026-04-01T00:00:00.000Z']);
    expect(b('between', '2025-06', '2026')).toEqual(['2025-06-01T00:00:00.000Z', '2027-01-01T00:00:00.000Z']);
  });

  it('anchors relative presets to the start of a UTC day so they cache for a day', () => {
    expect(b('gte', '-7d')).toEqual(['2026-10-20T00:00:00.000Z', null]);
    expect(b('gte', '-1d')).toEqual(['2026-10-26T00:00:00.000Z', null]);
    expect(b('gte', '-1m')).toEqual(['2026-09-26T00:00:00.000Z', null]);
    expect(b('gte', '-1y')).toEqual(['2025-10-26T00:00:00.000Z', null]);
  });
});

describe('chip text', () => {
  const text = (filter: FilterState) => filterText(filter, t, { currency: '£', entityLabels: { 'firm:12': 'Acme Corp' } });

  it.each([
    [f('type', 'in', 'business'), 'Type: Business'],
    [f('type', 'in', 'personal', 'business', 'government'), 'Type: Personal, Business, Government'],
    [f('balance', 'gt', '10000'), 'Balance > £10k'],
    [f('balance', 'gte', '1500000'), 'Balance ≥ £1.5M'],
    [f('balance', 'lt', '10250.5'), 'Balance < £10,250.50'],
    [f('balance', 'eq', '-250'), 'Balance = −£250'],
    [f('balance', 'between', '1000', '5000'), 'Balance: £1k – £5k'],
    [f('staff', 'gt', '1200'), 'Staff count > 1,200'],
    [f('created', 'eq', '2026'), 'Created: 2026'],
    [f('created', 'eq', '2026-03'), 'Created: Mar 2026'],
    [f('created', 'gte', '-30d'), 'Created: past 30 days'],
    [f('created', 'gte', '2026-03-05'), 'Created since Mar 5, 2026'],
    [f('created', 'lt', '2025'), 'Created before 2025'],
    [f('firm', 'in', '12'), 'Firm: Acme Corp'],
    [f('firm', 'in', '99'), 'Firm: #99'],
    [f('reason', 'contains', 'auction'), 'Reason contains “auction”'],
    [f('archived', 'eq', 'true'), 'Archived'],
    [f('archived', 'eq', 'false'), 'Archived: No'],
  ])('%j reads %s', (filter, expected) => {
    expect(text(filter)).toBe(expected);
  });

  it('splits label and value for styling', () => {
    expect(describeFilter(f('balance', 'gt', '10000'), t)).toEqual({ label: 'Balance', sep: ' ', value: '> $10k' });
  });

  it('parses the chip format back from parseFilterParam', () => {
    expect(parseFilterParam('balance:gt:10000', t)).toEqual(f('balance', 'gt', '10000'));
  });
});

describe('typed money input', () => {
  it.each([
    ['1000', 100000n], ['1,000', 100000n], ['$1,000.50', 100050n], ['£10k', 1000000n], ['1.5m', 150000000n],
    ['2b', 200000000000n], ['-250', -25000n], ['−250', -25000n], ['1.25k', 125000n], ['0.05', 5n], ['  42  ', 4200n],
  ])('%s is %s cents', (input, cents) => {
    expect(parseMoneyInput(input)).toBe(cents);
  });

  it.each(['', 'abc', '1e6', '0.001', '1.234567k', '1..2', '.5', '1.', '--1', '$', '1kk', '99999999999999999999', 'NaN', 'Infinity', '0x10'])('rejects %j', (input) => {
    expect(parseMoneyInput(input)).toBeNull();
  });

  it('never goes through a double: values past 2^53 cents stay exact', () => {
    expect(parseMoneyInput('90071992547409.93')).toBe(9007199254740993n);
    expect(centsToDecimal(9007199254740993n)).toBe('90071992547409.93');
  });

  it('formats chips exactly or not at all', () => {
    expect(formatMoneyChip(1000000n, '$')).toBe('$10k');
    expect(formatMoneyChip(1025000n, '$')).toBe('$10.25k');
    expect(formatMoneyChip(1025050n, '$')).toBe('$10,250.50');
    expect(formatMoneyChip(-150000000n, '$')).toBe('−$1.5M');
    expect(formatMoneyChip(5n, '$')).toBe('$0.05');
  });
});
