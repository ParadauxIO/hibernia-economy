import 'server-only';
import type { Viewer } from '@/lib/auth/viewer';
import type { AnyTable, TableState } from '@/lib/tables/core';
import type { CsvCell } from './csv';

// Full exports (spec §9.3): one registry of datasets behind
// `GET /api/export/<id>?<the page's own URL state>`.
//
// ── Adding a dataset ──────────────────────────────────────────────────────
//
// 1. Create `lib/export/datasets/<id>.ts` that calls defineExport:
//
//      import { hasPermission } from '@/lib/auth/policy';
//      import { defineExport } from '../registry';
//      import { finesTable } from '@/lib/tables/fines';
//      import { exportFinesBatch } from '@/lib/services/fines';
//
//      defineExport({
//        id: 'fines',                       // the URL segment; [a-z][a-z0-9-]*
//        title: 'Fines',                    // file name stem and audit label
//        table: finesTable,                 // or an async () => table
//        authorize: (viewer, state) => hasPermission(viewer, 'government.read.all'),
//        rows: (state, cursor, { limit }) => exportFinesBatch(state, cursor, limit),
//        columns: [
//          { key: 'fine_id', header: 'Fine', value: (r) => r.fine_id },
//          { key: 'amount', header: 'Amount', value: (r) => r.amount },
//        ],
//      });
//
// 2. Add one line to `lib/export/datasets/index.ts`: `import './fines';`
//
// 3. On the page, build the menu's links with exportHrefs() from
//    `lib/export/href.ts` and pass them to the design system's ExportMenu.
//
// The contract:
//
//  * `authorize` runs the SAME checks as the page that shows the data, for the
//    exact state being exported. Scope rows by the viewer inside `rows` when
//    the page does (e.g. only accounts the viewer can read); the route never
//    widens what the page would show.
//  * `rows(state, cursor, { limit, viewer })` returns up to `limit` rows after
//    `cursor` (null on the first call) and the cursor for the next batch, or
//    null when there are no more. Use a keyset cursor for feeds and an offset
//    for bounded directories. The route keeps calling until the row cap.
//  * `columns` fix the file's schema: the CSV header and the NDJSON/JSON keys,
//    in order. Values are already strings or numbers; money stays a DECIMAL
//    string (never a JS number). CSV escaping is applied by the route.
//  * The route handles sign-in, the rate limit, the 100k row cap, the audit
//    record and streaming. A dataset only answers "may this viewer see this
//    state" and "give me the next batch".

export type ExportCursor = unknown;

export interface ExportColumn<R> {
  /** JSON key; also documents the column. */
  key: string;
  /** CSV header. */
  header: string;
  value: (row: R) => CsvCell;
}

export interface ExportBatch<R, C> {
  rows: R[];
  next: C | null;
}

export interface ExportContext {
  /** Most rows this call may return. */
  limit: number;
  viewer: Extract<Viewer, { anon: false }>;
}

export interface ExportDefinition<T extends AnyTable = AnyTable, R = unknown, C = ExportCursor> {
  id: string;
  title: string;
  table: T | (() => Promise<T>);
  columns: readonly ExportColumn<R>[];
  authorize: (viewer: Extract<Viewer, { anon: false }>, state: TableState<T>) => boolean | Promise<boolean>;
  rows: (state: TableState<T>, cursor: C | null, ctx: ExportContext) => Promise<ExportBatch<R, C>>;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type AnyExport = ExportDefinition<any, any, any>;

const registry = new Map<string, AnyExport>();
const ID_RE = /^[a-z][a-z0-9-]{0,39}$/;

/**
 * Register a dataset. Throws on a malformed or duplicate id so mistakes fail at
 * import time. (Dev hot reload re-runs dataset modules, so there a repeat
 * registration replaces the old one.)
 */
export function defineExport<T extends AnyTable, R, C>(def: ExportDefinition<T, R, C>): ExportDefinition<T, R, C> {
  if (!ID_RE.test(def.id)) throw new Error(`defineExport: bad id "${def.id}"`);
  if (def.columns.length === 0) throw new Error(`defineExport(${def.id}): no columns`);
  const keys = new Set(def.columns.map((c) => c.key));
  if (keys.size !== def.columns.length) throw new Error(`defineExport(${def.id}): duplicate column key`);
  if (registry.has(def.id) && process.env.NODE_ENV !== 'development') throw new Error(`defineExport: "${def.id}" is already registered`);
  registry.set(def.id, def as AnyExport);
  return def;
}

export function getExport(id: string): AnyExport | undefined {
  return registry.get(id);
}

export function exportIds(): string[] {
  return [...registry.keys()].sort();
}

export async function resolveTable(def: AnyExport): Promise<AnyTable> {
  return typeof def.table === 'function' ? await (def.table as () => Promise<AnyTable>)() : def.table;
}

/** Test helper: forget every registration. */
export function resetExportsForTests(): void {
  registry.clear();
}
