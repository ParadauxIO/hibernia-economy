import { dateBounds, type FilterState } from './core';

// Per-row filters on the big feeds (a transaction's amount, a sale's price,
// message text) have no index, so on their own they would scan the whole
// ledger looking for rare matches. Spec §11 caps request-path SQL at 31 days of
// raw ledger. This turns a feed's date filter into the window such a query may
// scan: the filter's own window when it is short enough, otherwise the last
// `days` days of it. Pure, so the rule is unit-tested and shown to the user.

export interface ScanWindow {
  from: Date | null;
  to: Date | null;
  /** The window was narrowed (or added) to keep a per-row filter bounded. */
  bounded: boolean;
  /** No date filter was set; the window is the last `days` days. */
  implicit: boolean;
  /** The longest window allowed. */
  days: number;
}

const DAY_MS = 86_400_000;

/**
 * The settlement window for a query. Without per-row filters it is just the
 * date filter (possibly open-ended). With them it is at most `days` long and
 * ends where the date filter ends, or now.
 */
export function scanWindow(dateFilter: FilterState | undefined, needsBound: boolean, days: number, now: Date = new Date()): ScanWindow {
  const range = dateFilter ? dateBounds(dateFilter, now) : { from: null, to: null };
  if (!needsBound) return { from: range.from, to: range.to, bounded: false, implicit: false, days };
  const to = range.to && range.to.getTime() < now.getTime() ? range.to : null;
  const end = to ?? now;
  const earliest = new Date(end.getTime() - days * DAY_MS);
  if (range.from && range.from.getTime() >= earliest.getTime()) {
    return { from: range.from, to, bounded: false, implicit: false, days };
  }
  return { from: earliest, to, bounded: true, implicit: !dateFilter, days };
}
