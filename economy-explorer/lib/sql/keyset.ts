// Keyset (seek) pagination fragments for unbounded feeds ordered by
// `(timestamp, id)` (spec §4.3): no OFFSET, so page 1,000 costs the same as page 1.
// Pure: builds Kysely fragments, runs nothing.
//
// A page is read in the table's sort direction ("next") or against it
// ("prev", the Newer link on a newest-first feed); a prev page is fetched in
// reverse and flipped back by the caller with `inPageOrder`.

import { sql, type RawBuilder, type SqlBool } from 'kysely';
import type { KeysetCursor } from '@/lib/pagination/cursor';

export type SortDir = 'asc' | 'desc';
export type PageDirection = 'next' | 'prev';

export interface KeysetPage {
  /** The row the page continues from (exclusive), or null for the first page. */
  cursor: KeysetCursor | null;
  direction: PageDirection;
  /** The feed's own order. */
  dir: SortDir;
  limit: number;
}

/** The order rows are read in: the feed order for "next", reversed for "prev". */
export function readDir(page: Pick<KeysetPage, 'dir' | 'direction'>): SortDir {
  if (page.direction === 'next') return page.dir;
  return page.dir === 'desc' ? 'asc' : 'desc';
}

function keyword(dir: SortDir): RawBuilder<unknown> {
  return sql.raw(dir === 'asc' ? 'ASC' : 'DESC');
}

/**
 * `(time, id) < (at, id)` (or `>`), written out so MariaDB uses a range on the
 * `(time, …, id)` index: row-value comparisons are not range-optimised.
 */
export function afterCursor(time: RawBuilder<unknown>, id: RawBuilder<unknown>, cursor: KeysetCursor, dir: SortDir): RawBuilder<SqlBool> {
  const op = sql.raw(dir === 'desc' ? '<' : '>');
  return sql<SqlBool>`(${time} ${op} ${cursor.at} OR (${time} = ${cursor.at} AND ${id} ${op} ${cursor.id}))`;
}

/** `id < cursor.id` (or `>`), for feeds read off an `(entity, id)` index. */
export function afterId(id: RawBuilder<unknown>, cursor: KeysetCursor, dir: SortDir): RawBuilder<SqlBool> {
  return sql<SqlBool>`${id} ${sql.raw(dir === 'desc' ? '<' : '>')} ${cursor.id}`;
}

export function orderByTime(time: RawBuilder<unknown>, id: RawBuilder<unknown>, dir: SortDir): RawBuilder<unknown> {
  return sql`ORDER BY ${time} ${keyword(dir)}, ${id} ${keyword(dir)}`;
}

export function orderById(id: RawBuilder<unknown>, dir: SortDir): RawBuilder<unknown> {
  return sql`ORDER BY ${id} ${keyword(dir)}`;
}

export interface KeysetResult<R> {
  rows: R[];
  /** Rows exist past the end of this page, in the direction it was read. */
  more: boolean;
}

/**
 * Trim the `limit + 1` probe row and put a prev page back in feed order.
 * `rows` must be in read order (as the query returned them).
 */
export function inPageOrder<R>(rows: readonly R[], page: Pick<KeysetPage, 'direction' | 'limit'>): KeysetResult<R> {
  const more = rows.length > page.limit;
  const kept = rows.slice(0, page.limit);
  return { rows: page.direction === 'prev' ? kept.reverse() : kept, more };
}
