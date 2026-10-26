import 'server-only';
import { memo } from '@/lib/cache';
import {
  countAccountsContaining,
  countFirmsContaining,
  countItemsContaining,
  countPlayersByPrefix,
  findAccountById,
  findAccountsByPrefix,
  findFirmsByPrefix,
  findItemsByPrefix,
  findPlayerByUuid,
  findPlayersByPrefix,
  searchAccountsContaining,
  searchFirmsContaining,
  searchItemsContaining,
  type ItemSearchResult,
  type SearchHit,
  type SearchHitKind,
  type SearchResult,
} from '@/lib/sql/search';

// Search service (spec §9.1): the typeahead behind the header search, the ⌘K
// palette and entity filter chips, and the /search page.
//
// Typeahead rules: at least 2 characters, case-insensitive, prefix only, and
// cached in Redis for 60 s per (type, prefix) through memo(). Every memo key
// starts with the constant `search-typeahead` so the cache metrics label stays
// low-cardinality. Each type caches its best TYPEAHEAD_MAX rows once and callers
// slice, so the key never depends on the requested limit.

export const MIN_QUERY = 2;
export const TYPEAHEAD_MAX = 8;
const TYPEAHEAD_TTL_MS = 60_000;
const MAX_QUERY = 64;
const UUID_RE = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;
const ACCOUNT_NUMBER_RE = /^#?(\d{1,10})$/;

export const TYPEAHEAD_KINDS: readonly SearchHitKind[] = ['account', 'firm', 'player', 'item'];
export const DEFAULT_PREVIEW_KINDS: readonly SearchResult['kind'][] = ['account', 'firm', 'player'];

/** Trim, collapse whitespace and cap. The cache key and the SQL see the same text. */
export function normaliseQuery(q: unknown): string {
  if (typeof q !== 'string') return '';
  return q.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, MAX_QUERY);
}

/** Cache key for one (type, prefix) pair. Prefix lower-cased: matching is case-insensitive. */
export function typeaheadKey(kind: string, prefix: string): string {
  return `search-typeahead:${kind}:${prefix.toLowerCase()}`;
}

const RANK: Record<string, number> = { exact: 0, prefix: 1, contains: 2 };

/**
 * Stable best-first order inside one kind: exact, then prefix, then contains.
 * SQL already orders each kind by its tiebreak (recent login, last trade,
 * balance); this only lifts better matches above it and never reorders ties.
 */
export function rankHits<T extends { match?: string | undefined }>(hits: readonly T[]): T[] {
  return hits
    .map((h, i) => ({ h, i }))
    .sort((a, b) => (RANK[a.h.match ?? 'contains'] ?? 2) - (RANK[b.h.match ?? 'contains'] ?? 2) || a.i - b.i)
    .map(({ h }) => h);
}

type Loader = (prefix: string) => Promise<SearchHit[]>;

const LOADERS: Record<SearchHitKind, Loader> = {
  account: (p) => findAccountsByPrefix(p, TYPEAHEAD_MAX),
  firm: (p) => findFirmsByPrefix(p, TYPEAHEAD_MAX),
  player: (p) => findPlayersByPrefix(p, TYPEAHEAD_MAX),
  item: (p) => findItemsByPrefix(p, TYPEAHEAD_MAX),
};

async function prefixKind(kind: SearchHitKind, prefix: string): Promise<SearchHit[]> {
  return memo(typeaheadKey(kind, prefix), TYPEAHEAD_TTL_MS, () => LOADERS[kind](prefix));
}

/**
 * Typeahead results, grouped in `kinds` order, at most `limit` per kind. A
 * pasted UUID or an account number (`123`, `#123`) is looked up exactly and
 * put first.
 */
export async function suggest(q: unknown, options: { limit?: number; kinds?: readonly SearchHitKind[] } = {}): Promise<SearchHit[]> {
  const term = normaliseQuery(q);
  if (term.length < MIN_QUERY) return [];
  const limit = Math.min(Math.max(1, Math.floor(options.limit ?? 4)), TYPEAHEAD_MAX);
  const kinds = (options.kinds ?? DEFAULT_PREVIEW_KINDS).filter((k, i, all) => TYPEAHEAD_KINDS.includes(k) && all.indexOf(k) === i);

  if (UUID_RE.test(term)) {
    return kinds.includes('player') ? memo(typeaheadKey('player-uuid', term), TYPEAHEAD_TTL_MS, () => findPlayerByUuid(term)) : [];
  }
  const number = ACCOUNT_NUMBER_RE.exec(term);
  const exactAccount = number && kinds.includes('account')
    ? await memo(typeaheadKey('account-id', number[1] as string), TYPEAHEAD_TTL_MS, () => findAccountById(Number(number[1])))
    : [];
  // `#123` is only ever an account number; bare digits may also start a name.
  if (number && term.startsWith('#')) return exactAccount;

  const groups = await Promise.all(kinds.map((kind) => prefixKind(kind, term)));
  const out: SearchHit[] = [...exactAccount];
  groups.forEach((hits) => {
    for (const hit of rankHits(hits).slice(0, limit)) {
      if (hit.kind === 'account' && exactAccount.some((a) => a.kind === 'account' && a.account_id === hit.account_id)) continue;
      out.push(hit);
    }
  });
  return out;
}

/** Only the original three kinds, for consumers typed on SearchResult. */
export async function suggestEntities(q: unknown, limit = 4): Promise<SearchResult[]> {
  return (await suggest(q, { limit, kinds: DEFAULT_PREVIEW_KINDS })).filter((h): h is SearchResult => h.kind !== 'item');
}

// ── /search page ─────────────────────────────────────────────────────────

export type SearchTab = 'all' | 'accounts' | 'firms' | 'players' | 'items';
export const SEARCH_TABS: readonly SearchTab[] = ['all', 'accounts', 'firms', 'players', 'items'];
export type SearchGroupKind = Exclude<SearchTab, 'all'>;

export interface SearchGroup<T> {
  kind: SearchGroupKind;
  total: number;
  rows: T[];
}

export interface SearchPageResult {
  q: string;
  accounts: SearchGroup<SearchResult>;
  firms: SearchGroup<SearchResult>;
  players: SearchGroup<SearchResult>;
  items: SearchGroup<ItemSearchResult>;
}

/**
 * The full search: contains matches for accounts, firms and items, prefix for
 * players (never a contains scan of 140k names), plus an exact account number
 * or player UUID. `limit`/`offset` apply to the tab being viewed; the other
 * groups return their first `preview` rows for the All view.
 */
export async function searchEverything(q: unknown, opts: { tab: SearchTab; limit: number; offset: number; preview: number }): Promise<SearchPageResult> {
  const term = normaliseQuery(q);
  const empty = <T,>(kind: SearchGroupKind): SearchGroup<T> => ({ kind, total: 0, rows: [] });
  if (term.length < MIN_QUERY && !ACCOUNT_NUMBER_RE.test(term)) {
    return { q: term, accounts: empty('accounts'), firms: empty('firms'), players: empty('players'), items: empty('items') };
  }
  const window = (kind: SearchGroupKind) => opts.tab === kind ? { limit: opts.limit, offset: opts.offset } : { limit: opts.preview, offset: 0 };

  if (UUID_RE.test(term)) {
    const players = await findPlayerByUuid(term);
    return { q: term, accounts: empty('accounts'), firms: empty('firms'), players: { kind: 'players', total: players.length, rows: players }, items: empty('items') };
  }

  const number = ACCOUNT_NUMBER_RE.exec(term);
  const nameSearch = term.length >= MIN_QUERY && !term.startsWith('#');
  const a = window('accounts');
  const f = window('firms');
  const p = window('players');
  const i = window('items');
  const [exact, accounts, accountCount, firms, firmCount, players, playerCount, items, itemCount] = await Promise.all([
    number ? findAccountById(Number(number[1])) : Promise.resolve([]),
    nameSearch ? searchAccountsContaining(term, a.limit, a.offset) : Promise.resolve([]),
    nameSearch ? countAccountsContaining(term) : Promise.resolve(0),
    nameSearch ? searchFirmsContaining(term, f.limit, f.offset) : Promise.resolve([]),
    nameSearch ? countFirmsContaining(term) : Promise.resolve(0),
    // Players page by prefix rank; offset paging over a ranked prefix list is cheap.
    nameSearch ? findPlayersByPrefix(term, p.limit + p.offset).then((r) => r.slice(p.offset)) : Promise.resolve([]),
    nameSearch ? countPlayersByPrefix(term) : Promise.resolve(0),
    nameSearch ? searchItemsContaining(term, i.limit, i.offset) : Promise.resolve([]),
    nameSearch ? countItemsContaining(term) : Promise.resolve(0),
  ]);
  const exactIds = new Set(exact.map((e) => e.account_id));
  const accountRows = a.offset === 0 ? [...exact, ...accounts.filter((r) => !exactIds.has(r.account_id))] : accounts;
  return {
    q: term,
    accounts: { kind: 'accounts', total: accountCount + exact.length, rows: rankHits(accountRows) },
    firms: { kind: 'firms', total: firmCount, rows: rankHits(firms) },
    players: { kind: 'players', total: playerCount, rows: players },
    items: { kind: 'items', total: itemCount, rows: rankHits(items) },
  };
}

