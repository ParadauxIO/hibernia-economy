import { exportHrefs } from '@/lib/export/href';
import type { DayRange } from '@/lib/range';
import { dateBounds, normaliseFilter, type AnyTable, type FilterState, type TableState } from '@/lib/tables/core';

// Export links for /gov tables (lib/export/datasets/gov.ts). The export route
// only carries table state, so the page's unit and range become chips: the
// selected accounts as `account` (intersected with any account chip already
// set) and the range as a date chip (intersected with any already set). The
// dataset re-checks the scope either way.

const day = (d: Date) => d.toISOString().slice(0, 10);

/** The intersection of the page range and a date chip, as inclusive days; null when they don't overlap. */
export function intersectRange(range: DayRange, chip: FilterState | undefined, now = new Date()): DayRange | null {
  if (!chip) return range;
  const b = dateBounds(chip, now);
  const from = b.from && day(b.from) > range.from ? day(b.from) : range.from;
  const to = b.to && day(new Date(b.to.getTime() - 1)) < range.to ? day(new Date(b.to.getTime() - 1)) : range.to;
  return from <= to ? { from, to } : null;
}

/**
 * ExportMenu hrefs for a /gov table, or an empty object when the page's own
 * filters leave nothing to export.
 */
export function govExportHrefs(id: string, state: TableState, table: AnyTable, selection: readonly number[], range: DayRange, dateField: string): ReturnType<typeof exportHrefs> | Record<string, never> {
  const chip = state.filters.find((f) => f.field === 'account');
  const accounts = chip ? selection.filter((a) => chip.values.includes(String(a))) : [...selection];
  const dates = intersectRange(range, state.filters.find((f) => f.field === dateField));
  if (accounts.length === 0 || !dates) return {};
  // More than LIMITS.inValues (50) accounts don't fit a chip; the export then
  // covers the whole scope, which the dataset still enforces.
  const account = normaliseFilter(table, 'account', 'in', accounts.map(String));
  const date = normaliseFilter(table, dateField, 'between', [dates.from, dates.to]);
  const filters = state.filters.filter((f) => f.field !== 'account' && f.field !== dateField);
  if (account) filters.push(account);
  if (date) filters.push(date);
  return exportHrefs(id, { ...state, filters }, table);
}
