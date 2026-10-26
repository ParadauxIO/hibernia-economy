// Turns parsed table state (lib/tables/core.ts) into SQL. Pure: it builds Kysely
// fragments and never runs a query.
//
// Safety contract, which the unit tests pin down:
//   * only fields declared in BOTH the table schema and the column map compile;
//     anything else in the state is ignored;
//   * every user value is a bound parameter. The only raw SQL is the ASC/DESC
//     keyword, chosen from a two-value literal;
//   * money is compared as DECIMAL (`CAST(? AS DECIMAL(19,2))`), never as a double.

import { sql, type RawBuilder, type SelectQueryBuilder, type SqlBool } from 'kysely';
import {
  dateBounds,
  type AnyTable,
  type FieldDef,
  type FilterKey,
  type FilterState,
  type SortDir,
  type SortKey,
  type TableState,
} from '@/lib/tables/core';
import { toDbString, tryParseMoney } from '@/lib/money';

type Fragment = RawBuilder<unknown>;
type Condition = RawBuilder<SqlBool>;

/** A filter backed by one column. `values` maps enum URL values to stored values. */
export interface ColumnFilterSql {
  column: Fragment;
  values?: Readonly<Record<string, string | number>>;
}
/** A filter that needs its own SQL (a semi-join, say). Return null to skip it. */
export interface CustomFilterSql {
  compile: (filter: FilterState, now: Date) => Condition | null;
}
export type FilterSql = ColumnFilterSql | CustomFilterSql;

export interface ColumnMap<T extends AnyTable> {
  table: T;
  /** Every declared filter must be mapped: the type makes a missing one a compile error. */
  filters: { readonly [K in FilterKey<T>]: FilterSql };
  sorts: { readonly [K in SortKey<T>]: Fragment };
  /** Appended to every ORDER BY so pages are stable (normally the primary key). */
  tiebreak?: { column: Fragment; dir?: SortDir };
  /** Free-text search. Return null when the text can't match anything useful. */
  search?: (q: string) => Condition | null;
}

/** Identity helper so the map's keys are checked against the table. */
export function defineColumnMap<T extends AnyTable>(map: ColumnMap<T>): ColumnMap<T> {
  return map;
}

export interface CompiledTableState {
  conditions: Condition[];
  /** `WHERE a AND b`, or empty when there are no conditions. */
  where: Fragment;
  order: { expr: Fragment; dir: SortDir }[];
  /** `ORDER BY x DESC, id DESC`. */
  orderBy: Fragment;
}

function keyword(dir: SortDir): Fragment {
  return sql.raw(dir === 'asc' ? 'ASC' : 'DESC');
}

/** Escape LIKE wildcards so `contains` means a literal substring. */
export function likeContains(text: string): string {
  return `%${text.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
}

function decimal(field: FieldDef, value: string): Fragment | null {
  if (field.kind === 'money') {
    const cents = tryParseMoney(value);
    return cents === null ? null : sql`CAST(${toDbString(cents)} AS DECIMAL(19,2))`;
  }
  // number: canonical decimal strings, compared exactly as DECIMAL.
  return /^-?\d{1,15}(\.\d{1,6})?$/.test(value) ? sql`CAST(${value} AS DECIMAL(21,6))` : null;
}

function compare(column: Fragment, op: FilterState['op'], value: Fragment): Condition | null {
  switch (op) {
    case 'eq': return sql<SqlBool>`${column} = ${value}`;
    case 'gt': return sql<SqlBool>`${column} > ${value}`;
    case 'gte': return sql<SqlBool>`${column} >= ${value}`;
    case 'lt': return sql<SqlBool>`${column} < ${value}`;
    case 'lte': return sql<SqlBool>`${column} <= ${value}`;
    case 'in':
    case 'between':
    case 'contains':
      return null;
  }
}

function compileColumnFilter(field: FieldDef, f: FilterState, m: ColumnFilterSql, now: Date): Condition | null {
  const col = m.column;
  switch (field.kind) {
    case 'enum':
    case 'entity': {
      if (f.op !== 'in' || f.values.length === 0) return null;
      const values = f.values.map((v) => {
        if (m.values && Object.hasOwn(m.values, v)) return m.values[v] as string | number;
        if (field.kind === 'entity' && (field.entity === 'firm' || field.entity === 'account')) return Number(v);
        return v;
      });
      return sql<SqlBool>`${col} IN (${sql.join(values)})`;
    }
    case 'money':
    case 'number': {
      if (f.op === 'between') {
        const lo = decimal(field, f.values[0] ?? '');
        const hi = decimal(field, f.values[1] ?? '');
        return lo && hi ? sql<SqlBool>`${col} BETWEEN ${lo} AND ${hi}` : null;
      }
      const v = decimal(field, f.values[0] ?? '');
      return v ? compare(col, f.op, v) : null;
    }
    case 'date': {
      const { from, to } = dateBounds(f, now);
      const parts: Condition[] = [];
      if (from) parts.push(sql<SqlBool>`${col} >= ${from}`);
      if (to) parts.push(sql<SqlBool>`${col} < ${to}`);
      if (parts.length === 0) return null;
      return parts.length === 1 ? (parts[0] as Condition) : sql<SqlBool>`(${sql.join(parts, sql` AND `)})`;
    }
    case 'text': {
      const v = f.values[0];
      if (v === undefined) return null;
      if (f.op === 'contains') return sql<SqlBool>`${col} LIKE ${likeContains(v)}`;
      return f.op === 'eq' ? sql<SqlBool>`${col} = ${v}` : null;
    }
    case 'boolean':
      return f.op === 'eq' ? sql<SqlBool>`${col} = ${f.values[0] === 'true' ? 1 : 0}` : null;
  }
}

/**
 * Compile a table state against a column map. `now` anchors relative dates
 * ("last 30 days") so callers and tests can pin the clock.
 */
export function compileTableState<T extends AnyTable>(state: TableState<T>, map: ColumnMap<T>, now: Date = new Date()): CompiledTableState {
  const fields = map.table.filters as Record<string, FieldDef>;
  const mapped = map.filters as Record<string, FilterSql>;
  const conditions: Condition[] = [];

  for (const f of state.filters) {
    if (!Object.hasOwn(fields, f.field) || !Object.hasOwn(mapped, f.field)) continue;
    const field = fields[f.field];
    const m = mapped[f.field];
    if (!field || !m) continue;
    const cond = 'compile' in m ? m.compile(f, now) : compileColumnFilter(field, f, m, now);
    if (cond) conditions.push(cond);
  }
  if (state.q && map.search) {
    const cond = map.search(state.q);
    if (cond) conditions.push(cond);
  }

  const sorts = map.sorts as Record<string, Fragment>;
  const order: { expr: Fragment; dir: SortDir }[] = [];
  const primary = Object.hasOwn(sorts, state.sort.field) ? sorts[state.sort.field] : undefined;
  const fallback = map.table.defaultSort as { field: string; dir: SortDir };
  if (primary) order.push({ expr: primary, dir: state.sort.dir });
  else if (sorts[fallback.field]) order.push({ expr: sorts[fallback.field] as Fragment, dir: fallback.dir });
  if (map.tiebreak) order.push({ expr: map.tiebreak.column, dir: map.tiebreak.dir ?? 'desc' });

  return {
    conditions,
    where: conditions.length ? sql`WHERE ${sql.join(conditions, sql` AND `)}` : sql``,
    order,
    orderBy: order.length ? sql`ORDER BY ${sql.join(order.map((o) => sql`${o.expr} ${keyword(o.dir)}`))}` : sql``,
  };
}

/** The same state applied to a Kysely select builder: one `where` per condition, then the ORDER BY. */
export function applyTableState<DB, TB extends keyof DB, O, T extends AnyTable>(
  qb: SelectQueryBuilder<DB, TB, O>,
  state: TableState<T>,
  map: ColumnMap<T>,
  now: Date = new Date(),
): SelectQueryBuilder<DB, TB, O> {
  const compiled = compileTableState(state, map, now);
  let out = qb;
  for (const cond of compiled.conditions) out = out.where(cond);
  for (const o of compiled.order) out = out.orderBy(o.expr, o.dir);
  return out;
}
