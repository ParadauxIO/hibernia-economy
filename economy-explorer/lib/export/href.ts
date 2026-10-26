import { serializeTableState, type AnyTable, type TableState } from '@/lib/tables/core';

// Links into the export route, built from the page's own table state so an
// export selects exactly what the page's filters select. Client-safe.

export type ExportFormat = 'csv' | 'ndjson' | 'json';
export const EXPORT_FORMATS: readonly ExportFormat[] = ['csv', 'ndjson', 'json'];

/**
 * `/api/export/<id>?<filters, search and sort>&format=…`. Paging is dropped for
 * a full export; `scope: 'page'` keeps it and exports only the rows on screen.
 */
export function exportHref(id: string, state: TableState, table: AnyTable, format: ExportFormat, scope: 'all' | 'page' = 'all'): string {
  const s = scope === 'page' ? state : { ...state, page: 1, cursor: null, limit: table.pageSize };
  const extra: [string, string][] = [['format', format]];
  if (scope === 'page') extra.push(['scope', 'page']);
  const qs = serializeTableState(s, table, extra);
  return `/api/export/${id}?${qs}`;
}

/** The ExportMenu's `hrefs`: every result as CSV and JSON, plus this page when the table pages by number. */
export function exportHrefs(id: string, state: TableState, table: AnyTable): { page?: { csv: string; json: string }; all: { csv: string; json: string } } {
  const all = { csv: exportHref(id, state, table, 'csv'), json: exportHref(id, state, table, 'json') };
  if (table.pagination !== 'page') return { all };
  return { page: { csv: exportHref(id, state, table, 'csv', 'page'), json: exportHref(id, state, table, 'json', 'page') }, all };
}
