// Table schema, URL state and search tokens for every explorer table (spec §5.5).
//
// A table is declared once with `defineTable`. The server page parses the URL
// with `parseTableState`, the service and SQL layers receive the typed state, and
// the client `FilterBar` renders chips from the same schema and writes the URL
// back with `serializeTableState`. Everything here is pure, client-safe and
// never throws on user input: unknown or malformed filters are dropped.
//
// URL format: ?q=text&f=field:op:value&f=…&sort=-field&page=2 (or &cursor=…)

import { z } from 'zod';
import { tryParseMoney } from '@/lib/money';
import { decodeCursor, encodeCursor, type KeysetCursor } from '@/lib/pagination/cursor';
import { centsToDecimal, formatMoneyChip, parseMoneyInput } from './money';

// ── Schema types ──────────────────────────────────────────────────────────

export type FieldKind = 'enum' | 'money' | 'number' | 'date' | 'entity' | 'text' | 'boolean';
export type Operator = 'in' | 'eq' | 'gt' | 'gte' | 'lt' | 'lte' | 'between' | 'contains';
export type EntityKind = 'player' | 'account' | 'firm' | 'item';
export type SortDir = 'asc' | 'desc';

export const OPERATORS: readonly Operator[] = ['in', 'eq', 'gt', 'gte', 'lt', 'lte', 'between', 'contains'];

interface FieldBase {
  label: string;
  /** One line shown under the field name in the "+ Filter" menu. */
  hint?: string;
}
export interface EnumOption { value: string; label: string }
export interface EnumField extends FieldBase { kind: 'enum'; options: readonly EnumOption[] }
export interface MoneyField extends FieldBase { kind: 'money' }
export interface NumberField extends FieldBase { kind: 'number'; integer?: boolean }
export interface DateField extends FieldBase { kind: 'date' }
export interface EntityField extends FieldBase { kind: 'entity'; entity: EntityKind }
export interface TextField extends FieldBase { kind: 'text' }
export interface BooleanField extends FieldBase { kind: 'boolean' }
export type FieldDef = EnumField | MoneyField | NumberField | DateField | EntityField | TextField | BooleanField;

/** Operators each kind accepts, canonical first. `eq` on enum/entity becomes `in`. */
export const KIND_OPERATORS: Record<FieldKind, readonly Operator[]> = {
  enum: ['in'],
  entity: ['in'],
  money: ['gt', 'gte', 'lt', 'lte', 'between', 'eq'],
  number: ['gt', 'gte', 'lt', 'lte', 'between', 'eq'],
  date: ['eq', 'gte', 'gt', 'lt', 'lte', 'between'],
  text: ['contains', 'eq'],
  boolean: ['eq'],
};

export interface QuickFilter {
  label: string;
  /** Filters in URL form, e.g. `type:in:business`. */
  f: readonly string[];
}

interface TableInput<F extends Record<string, FieldDef>, S extends Record<string, string>> {
  /** Stable id: keys localStorage (columns menu) and cache entries. */
  id: string;
  /** Columns the free-text search covers. Documentation for the SQL mapping and the placeholder. */
  search?: readonly string[];
  searchPlaceholder?: string;
  filters: F;
  /** Sortable fields → header label. */
  sorts: S;
  defaultSort: Extract<keyof S, string> | `-${Extract<keyof S, string>}`;
  quick?: readonly QuickFilter[];
  /** Bounded directories page by number; unbounded feeds use a cursor (§4.3). */
  pagination?: 'page' | 'cursor';
  pageSize?: number;
  maxPageSize?: number;
  /** For the summary line: "1,204 accounts". */
  noun?: { one: string; many: string };
  /**
   * Old query params still honoured so existing links keep working: a bare
   * `?type=PERSONAL` becomes a filter, and `?sort=balance&dir=DESC` a sort.
   */
  legacy?: { filterParams?: Partial<Record<string, Extract<keyof F, string>>>; sortParams?: Partial<Record<string, Extract<keyof S, string>>> };
}

export interface TableDef<F extends Record<string, FieldDef> = Record<string, FieldDef>, S extends Record<string, string> = Record<string, string>> {
  readonly id: string;
  readonly search: readonly string[];
  readonly searchPlaceholder: string;
  readonly filters: F;
  readonly sorts: S;
  readonly defaultSort: SortState<Extract<keyof S, string>>;
  readonly quick: readonly QuickFilter[];
  readonly pagination: 'page' | 'cursor';
  readonly pageSize: number;
  readonly maxPageSize: number;
  readonly noun: { one: string; many: string };
  readonly legacy: { filterParams: Partial<Record<string, string>>; sortParams: Partial<Record<string, string>> };
}

// Any table, for helpers that don't care which.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type AnyTable = TableDef<any, any>;
export type FilterKey<T extends AnyTable> = Extract<keyof T['filters'], string>;
export type SortKey<T extends AnyTable> = Extract<keyof T['sorts'], string>;

export interface FilterState<K extends string = string> {
  field: K;
  op: Operator;
  /** Canonical values: one for most operators, two for `between`, one or more for `in`. */
  values: readonly string[];
}
export interface SortState<K extends string = string> { field: K; dir: SortDir }
export interface TableState<T extends AnyTable = AnyTable> {
  q: string;
  filters: readonly FilterState<FilterKey<T>>[];
  sort: SortState<SortKey<T>>;
  /** 1-based; always 1 for cursor tables. */
  page: number;
  /** Opaque cursor (lib/pagination/cursor.ts owns the encoding); cursor tables only. */
  cursor: string | null;
  limit: number;
}

// ── Limits ────────────────────────────────────────────────────────────────

export const LIMITS = {
  q: 200,
  text: 200,
  filters: 20,
  inValues: 50,
  param: 1_000,
  cursor: 512,
  page: 100_000,
} as const;

const KEY_RE = /^[a-zA-Z][a-zA-Z0-9_]{0,39}$/;

// ── defineTable ───────────────────────────────────────────────────────────

/**
 * Declare a table. Throws on programmer error (bad keys, a default sort or
 * quick filter that doesn't parse) so mistakes fail at import time, not on a
 * user's request. The result is plain data, so a server page can pass it to the
 * client `FilterBar` as a prop.
 */
export function defineTable<const F extends Record<string, FieldDef>, const S extends Record<string, string>>(
  input: TableInput<F, S>,
): TableDef<F, S> {
  if (!/^[a-z][a-z0-9-]{0,39}$/.test(input.id)) throw new Error(`defineTable: bad id "${input.id}"`);
  for (const key of [...Object.keys(input.filters), ...Object.keys(input.sorts)]) {
    if (!KEY_RE.test(key)) throw new Error(`defineTable(${input.id}): bad field key "${key}"`);
  }
  for (const [key, field] of Object.entries(input.filters)) {
    if (field.kind === 'enum') {
      if (field.options.length === 0) throw new Error(`defineTable(${input.id}): enum "${key}" has no options`);
      for (const o of field.options) {
        if (!/^[a-zA-Z0-9_-]{1,40}$/.test(o.value)) throw new Error(`defineTable(${input.id}): bad option "${o.value}"`);
      }
    }
  }
  const sort = parseSortParam(input.defaultSort, input.sorts);
  if (!sort) throw new Error(`defineTable(${input.id}): defaultSort "${input.defaultSort}" is not a declared sort`);
  const pageSize = input.pageSize ?? 50;
  const def: TableDef<F, S> = {
    id: input.id,
    search: input.search ?? [],
    searchPlaceholder: input.searchPlaceholder ?? 'Search…',
    filters: input.filters,
    sorts: input.sorts,
    defaultSort: sort as SortState<Extract<keyof S, string>>,
    quick: input.quick ?? [],
    pagination: input.pagination ?? 'page',
    pageSize,
    maxPageSize: Math.max(pageSize, input.maxPageSize ?? 200),
    noun: input.noun ?? { one: 'result', many: 'results' },
    legacy: { filterParams: input.legacy?.filterParams ?? {}, sortParams: input.legacy?.sortParams ?? {} },
  };
  for (const quick of def.quick) {
    for (const f of quick.f) {
      if (!parseFilterParam(f, def)) throw new Error(`defineTable(${input.id}): quick filter "${f}" does not parse`);
    }
  }
  return def;
}

// ── Value normalisation (the only gate between user input and state) ─────

const DATE_ABS_RE = /^(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?$/;
const DATE_REL_RE = /^-([1-9]\d{0,3})([dwmy])$/;
const NUMBER_RE = /^([+-]?)(\d{1,15})(?:\.(\d{1,6}))?$/;
const UUID_RE = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;
const PLAYER_NAME_RE = /^\.?[A-Za-z0-9_]{1,32}$/;
// ChestShop item keys are free text up to VARCHAR(190): `Diamond`, `Diamond Axe#7Gk`,
// `Diamond Axe:1142`. Anything printable except a comma (the `in` value separator).
const ITEM_RE = /^[^\u0000-\u001f\u007f,]{1,190}$/;
const ID_RE = /^[1-9]\d{0,9}$/;
const CONTROL_RE = /[\u0000-\u001f\u007f]/;

function canonicalDate(raw: string): string | null {
  const v = raw.trim().toLowerCase();
  const rel = DATE_REL_RE.exec(v);
  if (rel) return Number(rel[1]) <= 3650 ? v : null;
  const m = DATE_ABS_RE.exec(v);
  if (!m) return null;
  const year = Number(m[1]);
  if (year < 2000 || year > 2100) return null;
  if (m[2] !== undefined) {
    const month = Number(m[2]);
    if (month < 1 || month > 12) return null;
    if (m[3] !== undefined) {
      const day = Number(m[3]);
      const d = new Date(Date.UTC(year, month - 1, day));
      if (d.getUTCMonth() !== month - 1 || d.getUTCDate() !== day) return null;
    }
  }
  return v;
}

function canonicalNumber(raw: string, integer: boolean): string | null {
  const m = NUMBER_RE.exec(raw.trim().replace(/[,_\s]/g, '').replace(/−/g, '-'));
  if (!m) return null;
  const [, sign = '', whole = '', frac = ''] = m;
  const f = frac.replace(/0+$/, '');
  if (integer && f) return null;
  const w = whole.replace(/^0+(?=\d)/, '');
  const neg = sign === '-' && (w !== '0' || f !== '');
  return `${neg ? '-' : ''}${w}${f ? '.' + f : ''}`;
}

function canonicalEntity(raw: string, entity: EntityKind): string | null {
  const v = raw.trim();
  switch (entity) {
    case 'firm':
    case 'account':
      return ID_RE.test(v) ? v : null;
    case 'player': {
      if (UUID_RE.test(v)) {
        const hex = v.replace(/-/g, '').toLowerCase();
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
      }
      return PLAYER_NAME_RE.test(v) ? v : null;
    }
    case 'item':
      return ITEM_RE.test(v) ? v.toUpperCase() : null;
  }
}

const BOOL_TRUE = new Set(['true', 'yes', '1', 'on', 'y']);
const BOOL_FALSE = new Set(['false', 'no', '0', 'off', 'n']);

/** One raw value → its canonical form for a field, or null. */
function canonicalValue(field: FieldDef, raw: string): string | null {
  if (raw.length > LIMITS.param) return null;
  switch (field.kind) {
    case 'enum': {
      const v = raw.trim().toLowerCase();
      const opt = field.options.find((o) => o.value.toLowerCase() === v || o.label.toLowerCase() === v);
      return opt ? opt.value : null;
    }
    case 'money': {
      const cents = parseMoneyInput(raw);
      return cents === null ? null : centsToDecimal(cents);
    }
    case 'number':
      return canonicalNumber(raw, field.integer ?? false);
    case 'date':
      return canonicalDate(raw);
    case 'entity':
      return canonicalEntity(raw, field.entity);
    case 'text': {
      const v = raw.trim().replace(/\s+/g, ' ');
      if (v.length === 0 || v.length > LIMITS.text || CONTROL_RE.test(v)) return null;
      return v;
    }
    case 'boolean': {
      const v = raw.trim().toLowerCase();
      if (BOOL_TRUE.has(v)) return 'true';
      if (BOOL_FALSE.has(v)) return 'false';
      return null;
    }
  }
}

/** Compare two canonical values of a field, for ordering `between` bounds. */
function compareValues(field: FieldDef, a: string, b: string): number {
  if (field.kind === 'money') {
    const x = tryParseMoney(a) ?? 0n;
    const y = tryParseMoney(b) ?? 0n;
    return x < y ? -1 : x > y ? 1 : 0;
  }
  if (field.kind === 'number') return compareDecimal(a, b);
  if (field.kind === 'date') {
    // Relative bounds move with the clock; leave their order as typed.
    if (DATE_REL_RE.test(a) || DATE_REL_RE.test(b)) return 0;
    const epoch = new Date(0);
    return periodStart(a, epoch).getTime() - periodStart(b, epoch).getTime();
  }
  return 0;
}

/** Exact comparison of canonical decimal strings (no float conversion). */
function compareDecimal(a: string, b: string): number {
  const scale = (s: string) => {
    const [w = '0', f = ''] = s.replace('-', '').split('.');
    return { neg: s.startsWith('-'), n: BigInt(w + f.padEnd(6, '0')) };
  };
  const x = scale(a);
  const y = scale(b);
  const xv = x.neg ? -x.n : x.n;
  const yv = y.neg ? -y.n : y.n;
  return xv < yv ? -1 : xv > yv ? 1 : 0;
}

/**
 * Validate and canonicalise one filter against its field. Returns null when the
 * operator doesn't apply to the field or any value is malformed.
 */
export function normaliseFilter(table: AnyTable, fieldKey: string, opRaw: string, rawValues: readonly string[]): FilterState | null {
  if (!Object.hasOwn(table.filters, fieldKey)) return null;
  const field = (table.filters as Record<string, FieldDef>)[fieldKey];
  if (!field) return null;
  let op = opRaw.toLowerCase() as Operator;
  if (!OPERATORS.includes(op)) return null;
  if ((field.kind === 'enum' || field.kind === 'entity') && op === 'eq') op = 'in';
  if (!KIND_OPERATORS[field.kind].includes(op)) return null;

  const values: string[] = [];
  for (const raw of rawValues) {
    const v = canonicalValue(field, raw);
    if (v === null) return null;
    if (!values.includes(v)) values.push(v);
  }
  if (op === 'in') {
    if (values.length === 0 || values.length > LIMITS.inValues) return null;
    if (field.kind === 'enum') {
      // Option order, so `in:b,a` and `in:a,b` are one state.
      values.sort((a, b) => field.options.findIndex((o) => o.value === a) - field.options.findIndex((o) => o.value === b));
    }
  } else if (op === 'between') {
    if (rawValues.length !== 2) return null;
    if (values.length === 1) {
      const only = values[0] as string;
      // `between:5,5` is an exact match on numbers; on dates it's one period.
      return { field: fieldKey, op: 'eq', values: [only] };
    }
    if (values.length !== 2) return null;
    if (compareValues(field, values[0] as string, values[1] as string) > 0) values.reverse();
  } else if (values.length !== 1 || rawValues.length !== 1) {
    return null;
  }
  return { field: fieldKey, op, values };
}

// ── URL params ────────────────────────────────────────────────────────────

/** Split an `f` value's value part for its operator. Text values are never split. */
function splitValues(field: FieldDef | undefined, op: string, rest: string): string[] {
  if (field?.kind === 'text') return [rest];
  if (op === 'in' || op === 'between') return rest.split(',');
  // Single-value ops: commas are thousands separators (`balance:gt:1,000`).
  return [rest];
}

/** Parse one `f=field:op:value` param. */
export function parseFilterParam(param: string, table: AnyTable): FilterState | null {
  if (param.length > LIMITS.param) return null;
  const first = param.indexOf(':');
  if (first <= 0) return null;
  const second = param.indexOf(':', first + 1);
  if (second < 0) return null;
  const fieldKey = param.slice(0, first);
  const op = param.slice(first + 1, second);
  const rest = param.slice(second + 1);
  const field = Object.hasOwn(table.filters, fieldKey) ? (table.filters as Record<string, FieldDef>)[fieldKey] : undefined;
  if (!field) return null;
  return normaliseFilter(table, fieldKey, op, splitValues(field, op.toLowerCase(), rest));
}

export function formatFilterParam(f: FilterState): string {
  return `${f.field}:${f.op}:${f.values.join(',')}`;
}

function parseSortParam(raw: string, sorts: Record<string, string>): SortState | null {
  const desc = raw.startsWith('-');
  const key = desc ? raw.slice(1) : raw;
  if (!Object.hasOwn(sorts, key)) return null;
  return { field: key, dir: desc ? 'desc' : 'asc' };
}

function formatSortParam(s: SortState): string {
  return `${s.dir === 'desc' ? '-' : ''}${s.field}`;
}

/** Anything that looks like search params: Next's page prop, URLSearchParams, or useSearchParams(). */
export type SearchParamsInput =
  | Record<string, string | string[] | undefined>
  | { getAll(name: string): string[] };

function reader(input: SearchParamsInput): (name: string) => string[] {
  if (typeof (input as { getAll?: unknown }).getAll === 'function') {
    const sp = input as { getAll(name: string): string[] };
    return (name) => sp.getAll(name);
  }
  const rec = input as Record<string, string | string[] | undefined>;
  return (name) => {
    if (!Object.hasOwn(rec, name)) return [];
    const v = rec[name];
    if (v === undefined) return [];
    return Array.isArray(v) ? v : [v];
  };
}

const firstString = z.array(z.string()).transform((a) => a[0]);
const ENVELOPE = z.object({
  q: firstString.pipe(z.string().max(10_000).optional()).catch(undefined),
  f: z.array(z.string().max(LIMITS.param)).catch([]),
  sort: firstString.pipe(z.string().max(64).optional()).catch(undefined),
  dir: firstString.pipe(z.string().max(8).optional()).catch(undefined),
  page: firstString.pipe(z.coerce.number().int().min(1).max(LIMITS.page).optional()).catch(undefined),
  limit: firstString.pipe(z.coerce.number().int().min(1).max(10_000).optional()).catch(undefined),
  cursor: firstString.pipe(z.string().max(LIMITS.cursor).optional()).catch(undefined),
});

/** Collapse whitespace and cap the free-text query. */
function cleanQuery(q: string): string {
  return q.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, LIMITS.q);
}

/** Replace any filter on the same field, keeping chip order otherwise. */
function upsert<K extends string>(filters: FilterState<K>[], f: FilterState<K>): FilterState<K>[] {
  const out = filters.filter((x) => x.field !== f.field);
  out.push(f);
  return out;
}

/**
 * Parse URL search params into typed table state. Never throws: unknown fields,
 * bad operators, malformed values and out-of-range pages are dropped or reset
 * to the table's defaults.
 */
export function parseTableState<T extends AnyTable>(input: SearchParamsInput, table: T): TableState<T> {
  const read = reader(input);
  const env = ENVELOPE.parse({
    q: read('q'), f: read('f'), sort: read('sort'), dir: read('dir'),
    page: read('page'), limit: read('limit'), cursor: read('cursor'),
  });

  let filters: FilterState<FilterKey<T>>[] = [];
  for (const [param, field] of Object.entries(table.legacy.filterParams)) {
    if (!field) continue;
    const raw = read(param)[0];
    if (raw === undefined || raw === '') continue;
    const def = (table.filters as Record<string, FieldDef>)[field];
    if (!def) continue;
    const f = normaliseFilter(table, field, KIND_OPERATORS[def.kind][0] ?? 'eq', [raw]);
    if (f) filters = upsert(filters, f as FilterState<FilterKey<T>>);
  }
  for (const raw of env.f.slice(0, LIMITS.filters * 2)) {
    const f = parseFilterParam(raw, table);
    if (f) filters = upsert(filters, f as FilterState<FilterKey<T>>);
  }
  filters = filters.slice(-LIMITS.filters);

  let sort = table.defaultSort as SortState<SortKey<T>>;
  if (env.sort) {
    let raw = env.sort;
    const legacyKey = table.legacy.sortParams[raw.replace(/^-/, '')];
    if (!Object.hasOwn(table.sorts, raw.replace(/^-/, '')) && legacyKey) raw = (raw.startsWith('-') ? '-' : '') + legacyKey;
    if (env.dir && !raw.startsWith('-') && env.dir.toLowerCase() === 'desc') raw = '-' + raw;
    const parsed = parseSortParam(raw, table.sorts);
    if (parsed) sort = parsed as SortState<SortKey<T>>;
  }

  const cursorMode = table.pagination === 'cursor';
  const limit = env.limit !== undefined && env.limit <= table.maxPageSize ? env.limit : table.pageSize;
  return {
    q: cleanQuery(env.q ?? ''),
    filters,
    sort,
    page: cursorMode ? 1 : env.page ?? 1,
    // Opaque, but only tokens lib/pagination/cursor.ts can decode survive.
    cursor: cursorMode && env.cursor && decodeCursor(env.cursor) ? env.cursor : null,
    limit,
  };
}

/** The decoded keyset position for a cursor table, or null on the first page. */
export function stateCursor(state: TableState): KeysetCursor | null {
  return decodeCursor(state.cursor);
}

/** The next page of a cursor table: after the last row shown. */
export function withCursor<T extends AnyTable>(state: TableState<T>, after: KeysetCursor | null): TableState<T> {
  return { ...state, page: 1, cursor: after ? encodeCursor(after) : null };
}

/** Encode a query component, keeping `:` and `,` readable (both are legal in a query). */
function encodeComponent(s: string): string {
  return encodeURIComponent(s).replace(/%3A/gi, ':').replace(/%2C/gi, ',');
}

/** The state's URL params, in canonical order, omitting defaults. */
function tableStateEntries(state: TableState, table: AnyTable): [string, string][] {
  const out: [string, string][] = [];
  if (state.q) out.push(['q', state.q]);
  for (const f of state.filters) out.push(['f', formatFilterParam(f)]);
  const def = table.defaultSort;
  if (state.sort.field !== def.field || state.sort.dir !== def.dir) out.push(['sort', formatSortParam(state.sort)]);
  if (table.pagination === 'cursor') {
    if (state.cursor) out.push(['cursor', state.cursor]);
  } else if (state.page > 1) {
    out.push(['page', String(state.page)]);
  }
  if (state.limit !== table.pageSize) out.push(['limit', String(state.limit)]);
  return out;
}

/**
 * Serialise state to a query string (no leading `?`). `extra` carries params the
 * table doesn't own (e.g. a tab) through unchanged.
 */
export function serializeTableState(state: TableState, table: AnyTable, extra: [string, string][] = []): string {
  return [...extra, ...tableStateEntries(state, table)].map(([k, v]) => `${encodeComponent(k)}=${encodeComponent(v)}`).join('&');
}

/** `pathname?query` for a state, or just the pathname when every param is default. */
export function tableHref<Path extends string>(pathname: Path, state: TableState, table: AnyTable, extra: [string, string][] = []): Path | `${Path}?${string}` {
  const qs = serializeTableState(state, table, extra);
  return qs ? `${pathname}?${qs}` : pathname;
}

/** Params on the current URL that the table doesn't own, to carry through navigation. */
export function foreignParams(input: SearchParamsInput, keys: Iterable<string>): [string, string][] {
  const read = reader(input);
  const own = new Set(['q', 'f', 'sort', 'dir', 'page', 'limit', 'cursor']);
  const out: [string, string][] = [];
  for (const k of keys) {
    if (own.has(k)) continue;
    for (const v of read(k)) out.push([k, v]);
  }
  return out;
}

// ── State transitions (every change returns to the first page) ───────────

function firstPage<T extends AnyTable>(state: TableState<T>): TableState<T> {
  return { ...state, page: 1, cursor: null };
}
export function withQuery<T extends AnyTable>(state: TableState<T>, q: string): TableState<T> {
  return firstPage({ ...state, q: cleanQuery(q) });
}
export function withFilter<T extends AnyTable>(state: TableState<T>, f: FilterState<FilterKey<T>>): TableState<T> {
  return firstPage({ ...state, filters: upsert([...state.filters], f) });
}
export function withoutFilter<T extends AnyTable>(state: TableState<T>, field: string): TableState<T> {
  return firstPage({ ...state, filters: state.filters.filter((f) => f.field !== field) });
}
export function withFilters<T extends AnyTable>(state: TableState<T>, filters: readonly FilterState<FilterKey<T>>[]): TableState<T> {
  let out = [...state.filters];
  for (const f of filters) out = upsert(out, f);
  return firstPage({ ...state, filters: out });
}
export function cleared<T extends AnyTable>(state: TableState<T>): TableState<T> {
  return firstPage({ ...state, q: '', filters: [] });
}
export function withPage<T extends AnyTable>(state: TableState<T>, page: number): TableState<T> {
  return { ...state, page: Math.min(Math.max(1, Math.floor(page)), LIMITS.page), cursor: null };
}

/** The quick filters as parsed filters (defineTable already checked they parse). */
export function quickFilters(table: AnyTable): { label: string; filters: FilterState[] }[] {
  return table.quick.map((quick) => ({
    label: quick.label,
    filters: quick.f.map((f) => parseFilterParam(f, table)).filter((f): f is FilterState => f !== null),
  }));
}

export function isQuickActive(state: TableState, quick: { filters: readonly FilterState[] }): boolean {
  return quick.filters.every((qf) => state.filters.some((f) => sameFilter(f, qf)));
}

export function sameFilter(a: FilterState, b: FilterState): boolean {
  return a.field === b.field && a.op === b.op && a.values.length === b.values.length && a.values.every((v, i) => v === b.values[i]);
}

// ── Search tokens: `type:business balance:>10000` ─────────────────────────

/** Split on whitespace, keeping `"quoted values"` together. */
export function tokenize(input: string): { token: string; end: number }[] {
  const out: { token: string; end: number }[] = [];
  let i = 0;
  while (i < input.length) {
    while (i < input.length && /\s/.test(input[i] as string)) i++;
    if (i >= input.length) break;
    let token = '';
    let quoted = false;
    while (i < input.length && (quoted || !/\s/.test(input[i] as string))) {
      const ch = input[i] as string;
      if (ch === '"') quoted = !quoted;
      token += ch;
      i++;
    }
    out.push({ token, end: i });
  }
  return out;
}

function unquote(s: string): string {
  return s.length >= 2 && s.startsWith('"') && s.endsWith('"') ? s.slice(1, -1) : s.replace(/"/g, '');
}

/** Field key for a token prefix: the key itself or its label, case- and space-insensitive. */
function resolveFieldKey(table: AnyTable, name: string): string | null {
  const n = name.toLowerCase().replace(/[\s_-]/g, '');
  if (!n) return null;
  for (const [key, field] of Object.entries(table.filters as Record<string, FieldDef>)) {
    if (key.toLowerCase() === n || field.label.toLowerCase().replace(/[\s_-]/g, '') === n) return key;
  }
  return null;
}

const SHORT_OPS: [string, Operator][] = [['>=', 'gte'], ['<=', 'lte'], ['>', 'gt'], ['<', 'lt'], ['=', 'eq']];

/** One `field:value` token → a filter, or null if it should stay as free text. */
export function parseSearchToken(token: string, table: AnyTable): FilterState | null {
  const colon = token.indexOf(':');
  if (colon <= 0 || colon === token.length - 1) return null;
  const key = resolveFieldKey(table, token.slice(0, colon));
  if (!key) return null;
  const field = (table.filters as Record<string, FieldDef>)[key];
  if (!field) return null;
  let rest = token.slice(colon + 1);

  // Explicit operator: `type:in:a,b`, `balance:between:1k,5k`.
  const second = rest.indexOf(':');
  if (second > 0) {
    const op = rest.slice(0, second).toLowerCase();
    if ((OPERATORS as readonly string[]).includes(op)) {
      return normaliseFilter(table, key, op, splitValues(field, op, unquote(rest.slice(second + 1))));
    }
  }
  rest = unquote(rest);
  for (const [sym, op] of SHORT_OPS) {
    if (rest.startsWith(sym)) {
      const value = rest.slice(sym.length);
      return value ? normaliseFilter(table, key, op, [value]) : null;
    }
  }
  if (field.kind !== 'text') {
    const range = rest.split('..');
    if (range.length === 2) return normaliseFilter(table, key, 'between', range);
  }
  const defaultOp = KIND_OPERATORS[field.kind][0] ?? 'eq';
  if (field.kind === 'money' || field.kind === 'number') return normaliseFilter(table, key, 'eq', [rest]);
  return normaliseFilter(table, key, defaultOp, splitValues(field, defaultOp, rest));
}

/**
 * Pull `field:value` tokens out of search text. With `keepTrailing`, a token
 * still being typed (no whitespace after it yet) stays in the text, so
 * `balance:>1` doesn't become a chip before the user finishes `10000`.
 */
export function parseSearchInput(
  input: string,
  table: AnyTable,
  opts: { keepTrailing?: boolean } = {},
): { text: string; filters: FilterState[] } {
  const tokens = tokenize(input);
  const words: string[] = [];
  const filters: FilterState[] = [];
  tokens.forEach(({ token, end }, i) => {
    const trailing = opts.keepTrailing === true && i === tokens.length - 1 && end === input.length;
    const f = trailing ? null : parseSearchToken(token, table);
    if (f) filters.push(f);
    else words.push(token);
  });
  let text = words.join(' ');
  if (opts.keepTrailing && /\s$/.test(input) && text) text += ' ';
  return { text, filters };
}

// ── Dates ─────────────────────────────────────────────────────────────────

function relativeInstant(v: string, now: Date): Date {
  const m = DATE_REL_RE.exec(v);
  const n = Number(m?.[1] ?? 0);
  const unit = m?.[2] ?? 'd';
  // Day-aligned (UTC) so a relative range is stable for a whole day and caches.
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  if (unit === 'd') d.setUTCDate(d.getUTCDate() - n + 1);
  else if (unit === 'w') d.setUTCDate(d.getUTCDate() - n * 7 + 1);
  else if (unit === 'm') d.setUTCMonth(d.getUTCMonth() - n);
  else d.setUTCFullYear(d.getUTCFullYear() - n);
  return d;
}

function periodStart(v: string, now: Date): Date {
  if (DATE_REL_RE.test(v)) return relativeInstant(v, now);
  const m = DATE_ABS_RE.exec(v);
  return new Date(Date.UTC(Number(m?.[1] ?? 2000), Number(m?.[2] ?? 1) - 1, Number(m?.[3] ?? 1)));
}

function periodEnd(v: string): Date | null {
  if (DATE_REL_RE.test(v)) return null; // "last 30 days" runs to now
  const m = DATE_ABS_RE.exec(v);
  const y = Number(m?.[1] ?? 2000);
  if (m?.[3] !== undefined) return new Date(Date.UTC(y, Number(m[2]) - 1, Number(m[3]) + 1));
  if (m?.[2] !== undefined) return new Date(Date.UTC(y, Number(m[2]), 1));
  return new Date(Date.UTC(y + 1, 0, 1));
}

/** A date filter as a half-open UTC range `[from, to)`; null bounds are open. */
export function dateBounds(f: FilterState, now: Date = new Date()): { from: Date | null; to: Date | null } {
  const [a = '2000', b = a] = f.values;
  switch (f.op) {
    case 'eq': return { from: periodStart(a, now), to: periodEnd(a) };
    case 'gte': return { from: periodStart(a, now), to: null };
    case 'gt': return { from: periodEnd(a) ?? periodStart(a, now), to: null };
    case 'lt': return { from: null, to: periodStart(a, now) };
    case 'lte': return { from: null, to: periodEnd(a) };
    case 'between': return { from: periodStart(a, now), to: periodEnd(b) };
    case 'in':
    case 'contains':
      return { from: null, to: null };
  }
}

export const DATE_PRESETS: readonly { label: string; op: Operator; values: (now: Date) => string[] }[] = [
  { label: 'Last 7 days', op: 'gte', values: () => ['-7d'] },
  { label: 'Last 30 days', op: 'gte', values: () => ['-30d'] },
  { label: 'Last 90 days', op: 'gte', values: () => ['-90d'] },
  { label: 'This year', op: 'eq', values: (now) => [String(now.getUTCFullYear())] },
  { label: 'Last year', op: 'eq', values: (now) => [String(now.getUTCFullYear() - 1)] },
];

// ── Chip text ─────────────────────────────────────────────────────────────

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const REL_UNITS: Record<string, [string, string]> = { d: ['day', 'days'], w: ['week', 'weeks'], m: ['month', 'months'], y: ['year', 'years'] };

export function formatDateValue(v: string): string {
  const rel = DATE_REL_RE.exec(v);
  if (rel) {
    const n = Number(rel[1]);
    const [one, many] = REL_UNITS[rel[2] ?? 'd'] ?? ['day', 'days'];
    return n === 1 ? `past ${one}` : `past ${n} ${many}`;
  }
  const m = DATE_ABS_RE.exec(v);
  if (!m) return v;
  if (m[3] !== undefined) return `${MONTHS[Number(m[2]) - 1] ?? ''} ${Number(m[3])}, ${m[1] ?? ''}`;
  if (m[2] !== undefined) return `${MONTHS[Number(m[2]) - 1] ?? ''} ${m[1] ?? ''}`;
  return m[1] ?? v;
}

export interface DescribeOptions {
  currency?: string;
  /** Display names for entity ids, keyed `field:value` (resolved server-side). */
  entityLabels?: Readonly<Record<string, string>>;
}

const OP_SYMBOL: Partial<Record<Operator, string>> = { gt: '>', gte: '≥', lt: '<', lte: '≤', eq: '=' };

export interface FilterDescription {
  label: string;
  /** Null for a bare boolean chip ("Archived"). */
  value: string | null;
  /** Joiner between label and value: ": " for lists, " " for operators ("Balance > $10k"). */
  sep: string;
}

/** Chip text: `{ label: 'Balance', sep: ' ', value: '> $10k' }`. */
export function describeFilter(f: FilterState, table: AnyTable, opts: DescribeOptions = {}): FilterDescription {
  const field = (table.filters as Record<string, FieldDef>)[f.field];
  if (!field) return { label: f.field, value: f.values.join(', '), sep: ': ' };
  const label = field.label;
  const fmt = (v: string): string => {
    switch (field.kind) {
      case 'money': {
        const c = tryParseMoney(v);
        return c === null ? v : formatMoneyChip(c, opts.currency ?? '$');
      }
      case 'number': return v.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
      case 'date': return formatDateValue(v);
      case 'enum': return field.options.find((o) => o.value === v)?.label ?? v;
      case 'entity': return opts.entityLabels?.[`${f.field}:${v}`] ?? (field.entity === 'account' || field.entity === 'firm' ? `#${v}` : v);
      case 'text': return `“${v}”`;
      case 'boolean': return v === 'true' ? 'Yes' : 'No';
    }
  };
  const [a = '', b = ''] = f.values;
  const op = (value: string) => ({ label, value, sep: ' ' });
  const list = (value: string) => ({ label, value, sep: ': ' });
  if (field.kind === 'boolean') return a === 'true' ? { label, value: null, sep: '' } : list('No');
  if (field.kind === 'text') return op(f.op === 'contains' ? `contains ${fmt(a)}` : `is ${fmt(a)}`);
  if (f.op === 'in') {
    const names = f.values.map(fmt);
    return list(names.length > 3 ? `${names.slice(0, 2).join(', ')} +${names.length - 2}` : names.join(', '));
  }
  if (f.op === 'between') return list(`${fmt(a)} – ${fmt(b)}`);
  if (field.kind === 'date') {
    if (f.op === 'eq') return list(fmt(a));
    if (f.op === 'gte') return DATE_REL_RE.test(a) ? list(fmt(a)) : op(`since ${fmt(a)}`);
    if (f.op === 'gt') return op(`after ${fmt(a)}`);
    if (f.op === 'lt') return op(`before ${fmt(a)}`);
    return op(`until ${fmt(a)}`);
  }
  return op(`${OP_SYMBOL[f.op] ?? f.op} ${fmt(a)}`);
}

/** Plain text for a chip ("Balance > $10k", "Type: Business"), for labels and the empty state. */
export function filterText(f: FilterState, table: AnyTable, opts: DescribeOptions = {}): string {
  const d = describeFilter(f, table, opts);
  return d.value === null ? d.label : d.label + d.sep + d.value;
}

/** Human order words for a sort: "high to low", "newest first", "A–Z". */
function sortDirectionWords(table: AnyTable, field: string, dir: SortDir): string {
  const kind = Object.hasOwn(table.filters, field) ? (table.filters as Record<string, FieldDef>)[field]?.kind : undefined;
  if (kind === 'money' || kind === 'number') return dir === 'desc' ? 'high to low' : 'low to high';
  if (kind === 'date') return dir === 'desc' ? 'newest first' : 'oldest first';
  return dir === 'desc' ? 'Z–A' : 'A–Z';
}

/** Summary-line pieces: `{ label: 'Balance', words: 'high to low' }`. */
export function describeSort(state: TableState, table: AnyTable): { label: string; dir: SortDir; words: string } {
  return {
    label: (table.sorts as Record<string, string>)[state.sort.field] ?? state.sort.field,
    dir: state.sort.dir,
    words: sortDirectionWords(table, state.sort.field, state.sort.dir),
  };
}

/** Sort by `field` in `dir` (what a DataTable header link points at). Unknown fields leave the state alone. */
export function withSort<T extends AnyTable>(state: TableState<T>, field: string, dir: SortDir, table: T): TableState<T> {
  if (!Object.hasOwn(table.sorts, field)) return state;
  return firstPage({ ...state, sort: { field: field as SortKey<T>, dir } });
}

/** A typeahead suggestion for an entity filter (returned by a `searchEntity` server action). */
export interface EntityOption {
  value: string;
  label: string;
  detail?: string;
}
