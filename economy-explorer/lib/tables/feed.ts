import { tableHref, withCursor, type AnyTable, type SearchParamsInput, type TableState } from './core';
import type { KeysetCursor } from '@/lib/pagination/cursor';

// Newer / Older / jump-to-date for keyset feeds (spec §4.3), on top of the
// table's `cursor` param. Two extra params, both optional:
//   back=1           read the page before `cursor` instead of after it
//   at=YYYY-MM-DD    start the feed at that day (the CursorPager's jump form)
// Pure and client-safe.

export const BACK_PARAM = 'back';
export const JUMP_PARAM = 'at';

export interface FeedNav {
  direction: 'next' | 'prev';
  /** Start of the day after the jump date (UTC): a newest-first feed shows rows before it. */
  jump: { day: string; start: Date; end: Date } | null;
}

const DAY_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

function first(input: SearchParamsInput, name: string): string | undefined {
  if (typeof (input as { getAll?: unknown }).getAll === 'function') return (input as { getAll(n: string): string[] }).getAll(name)[0];
  const v = (input as Record<string, string | string[] | undefined>)[name];
  return Array.isArray(v) ? v[0] : v;
}

export function parseFeedNav(input: SearchParamsInput): FeedNav {
  const back = first(input, BACK_PARAM) === '1';
  const raw = first(input, JUMP_PARAM)?.trim() ?? '';
  const m = DAY_RE.exec(raw);
  let jump: FeedNav['jump'] = null;
  if (m) {
    const start = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
    const valid = start.getUTCFullYear() >= 2000 && start.getUTCFullYear() <= 2100 && start.toISOString().slice(0, 10) === raw;
    if (valid) jump = { day: raw, start, end: new Date(start.getTime() + 86_400_000) };
  }
  return { direction: back && !jump ? 'prev' : 'next', jump };
}

export interface FeedPosition {
  /** First and last rows shown, in feed order. */
  first: KeysetCursor | null;
  last: KeysetCursor | null;
  /** Rows exist before the first / after the last row shown. */
  hasPrev: boolean;
  hasNext: boolean;
}

/**
 * Newer and Older links. On a newest-first feed Older continues after the last
 * row and Newer reads back from the first; an oldest-first feed is the mirror.
 * `extra` carries params the table doesn't own (an open drawer is dropped).
 */
export function feedLinks<T extends AnyTable>(
  pathname: string,
  state: TableState<T>,
  table: T,
  pos: FeedPosition,
  extra: [string, string][] = [],
): { newerHref: string | null; olderHref: string | null } {
  const keep = extra.filter(([k]) => k !== BACK_PARAM && k !== JUMP_PARAM);
  const next = pos.hasNext && pos.last ? tableHref(pathname, withCursor(state, pos.last) as TableState, table, keep) : null;
  const prev = pos.hasPrev && pos.first ? tableHref(pathname, withCursor(state, pos.first) as TableState, table, [...keep, [BACK_PARAM, '1']]) : null;
  const desc = state.sort.dir === 'desc';
  return { newerHref: desc ? prev : next, olderHref: desc ? next : prev };
}
