import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cacheKeyPrefix } from '@/lib/metrics';
import type { SearchHit, SearchResult } from '@/lib/sql/search';

// The typeahead's rules (spec §9.1) with the SQL and the cache stubbed: minimum
// length, normalisation, (type, prefix) cache keys, ranking, and the exact
// lookups for account numbers and UUIDs.

const memoKeys: string[] = [];
vi.mock('@/lib/cache', () => ({
  memo: async (key: string, _ms: number, producer: () => Promise<unknown>) => {
    memoKeys.push(key);
    return producer();
  },
}));

const account = (id: number, label: string, match: NonNullable<SearchResult['match']>): SearchResult => ({
  kind: 'account', label, secondary: null, account_id: id, account_type: 'BUSINESS', balance: '1.00',
  firm_id: null, firm_name: null, player_uuid: null, player_name: null, href: `/accounts/${id}`, match,
});
const player = (name: string, match: NonNullable<SearchResult['match']>): SearchResult => ({
  kind: 'player', label: name, secondary: null, account_id: null, account_type: null, balance: null,
  firm_id: null, firm_name: null, player_uuid: `uuid-${name}`, player_name: name, match,
});

const sqlMock = vi.hoisted(() => ({
  findAccountsByPrefix: vi.fn(),
  findFirmsByPrefix: vi.fn(),
  findPlayersByPrefix: vi.fn(),
  findItemsByPrefix: vi.fn(),
  findAccountById: vi.fn(),
  findPlayerByUuid: vi.fn(),
}));
vi.mock('@/lib/sql/search', () => sqlMock);

const { normaliseQuery, rankHits, suggest, suggestEntities, typeaheadKey, MIN_QUERY } = await import('@/lib/services/search');

beforeEach(() => {
  memoKeys.length = 0;
  for (const fn of Object.values(sqlMock)) fn.mockReset().mockResolvedValue([]);
});

describe('normaliseQuery', () => {
  it('trims, collapses whitespace, strips control characters and caps length', () => {
    expect(normaliseQuery('  Acme \t  Corp \n')).toBe('Acme Corp');
    expect(normaliseQuery('a\u0000b')).toBe('a b');
    expect(normaliseQuery('x'.repeat(200))).toHaveLength(64);
    expect(normaliseQuery(42)).toBe('');
  });
});

describe('rankHits', () => {
  it('puts exact before prefix before contains and keeps SQL order within a tier', () => {
    const hits = [player('Alicea', 'prefix'), player('Malice', 'contains'), player('Alice', 'exact'), player('Alicez', 'prefix')];
    expect(rankHits(hits).map((h) => h.label)).toEqual(['Alice', 'Alicea', 'Alicez', 'Malice']);
  });
});

describe('suggest', () => {
  it('needs two characters and never queries for less', async () => {
    expect(MIN_QUERY).toBe(2);
    expect(await suggest('a')).toEqual([]);
    expect(await suggest('  ')).toEqual([]);
    expect(sqlMock.findAccountsByPrefix).not.toHaveBeenCalled();
  });

  it('caches per (type, lower-cased prefix) under one constant metrics prefix', async () => {
    await suggest('  ACme ', { kinds: ['account', 'firm', 'item'] });
    expect(memoKeys).toEqual(['search-typeahead:account:acme', 'search-typeahead:firm:acme', 'search-typeahead:item:acme']);
    expect(new Set(memoKeys.map(cacheKeyPrefix))).toEqual(new Set(['search-typeahead']));
    expect(typeaheadKey('player', 'AL')).toBe('search-typeahead:player:al');
    // The cache holds the best 8 whatever the caller's limit, so the key never includes it.
    expect(sqlMock.findAccountsByPrefix).toHaveBeenCalledWith('ACme', 8);
  });

  it('groups by kind in the requested order, ranks inside each, and caps each kind', async () => {
    sqlMock.findAccountsByPrefix.mockResolvedValue([account(2, 'Alice Shop', 'prefix'), account(1, 'Al', 'exact')]);
    sqlMock.findPlayersByPrefix.mockResolvedValue([player('Alicea', 'prefix'), player('Al', 'exact'), player('Alby', 'prefix')]);
    const hits = await suggest('al', { limit: 2, kinds: ['player', 'account'] });
    expect(hits.map((h) => `${h.kind}:${h.label}`)).toEqual(['player:Al', 'player:Alicea', 'account:Al', 'account:Alice Shop']);
  });

  it('looks up an account number exactly and puts it first, without duplicating it', async () => {
    sqlMock.findAccountById.mockResolvedValue([account(12, 'City Hall', 'exact')]);
    sqlMock.findAccountsByPrefix.mockResolvedValue([account(12, 'City Hall', 'prefix'), account(120, '12 Bakers', 'prefix')]);
    const bare = await suggest('12', { kinds: ['account'] });
    expect(bare.map((h) => (h as SearchResult).account_id)).toEqual([12, 120]);
    sqlMock.findAccountsByPrefix.mockClear();
    const hashed = await suggest('#12', { kinds: ['account', 'player'] });
    expect(hashed.map((h) => (h as SearchResult).account_id)).toEqual([12]);
    expect(sqlMock.findAccountsByPrefix).not.toHaveBeenCalled();
  });

  it('treats a pasted UUID as one exact player lookup', async () => {
    sqlMock.findPlayerByUuid.mockResolvedValue([player('Bob', 'exact')]);
    const uuid = '00000000-0000-0000-0000-000000000b0b';
    expect((await suggest(uuid, { kinds: ['player', 'account'] })).map((h) => h.label)).toEqual(['Bob']);
    expect(sqlMock.findPlayerByUuid).toHaveBeenCalledWith(uuid);
    expect(await suggest(uuid, { kinds: ['firm'] })).toEqual([]);
  });

  it('ignores unknown or repeated kinds', async () => {
    await suggest('ab', { kinds: ['firm', 'firm', 'bogus' as never] });
    expect(memoKeys).toEqual(['search-typeahead:firm:ab']);
  });
});

describe('suggestEntities (searchPreview contract)', () => {
  it('returns only accounts, firms and players, in that order, `limit` each', async () => {
    sqlMock.findItemsByPrefix.mockResolvedValue([{ kind: 'item', label: 'Diamond', secondary: null, item_key: 'DIAMOND', last_traded_at: null, href: '/accounts/1', match: 'prefix' } satisfies SearchHit]);
    sqlMock.findFirmsByPrefix.mockResolvedValue([{ ...account(0, 'Acme Corp', 'prefix'), kind: 'firm', account_id: null, firm_id: 1, firm_name: 'Acme Corp' }]);
    sqlMock.findAccountsByPrefix.mockResolvedValue([account(3, 'Acme', 'exact'), account(4, 'Acme 2', 'prefix')]);
    const hits = await suggestEntities('acme', 1);
    expect(hits.map((h) => `${h.kind}:${h.label}`)).toEqual(['account:Acme', 'firm:Acme Corp']);
    expect(sqlMock.findItemsByPrefix).not.toHaveBeenCalled();
  });
});
