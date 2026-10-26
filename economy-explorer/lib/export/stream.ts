import 'server-only';
import { csvLine } from './csv';
import type { ExportFormat } from './href';
import type { ExportContext, ExportDefinition } from './registry';
import type { AnyTable, TableState } from '@/lib/tables/core';

// Streams an export as CSV, NDJSON or JSON, one batch query at a time, so a
// 100k-row file never sits in memory and the first bytes leave after the first
// batch. Stops at the row cap and says so in the file itself: a last CSV line,
// an NDJSON trailer object, or `"truncated": true` in JSON.

export const ROW_CAP = 100_000;
export const BATCH_SIZE = 1_000;

export const CONTENT_TYPES: Record<ExportFormat, string> = {
  csv: 'text/csv; charset=utf-8',
  ndjson: 'application/x-ndjson; charset=utf-8',
  json: 'application/json; charset=utf-8',
};

export function capMessage(cap: number): string {
  return `Export stopped at ${cap.toLocaleString('en-US')} rows. Narrow the filters to export the rest.`;
}

export interface StreamOptions<T extends AnyTable, R, C> {
  def: ExportDefinition<T, R, C>;
  state: TableState<T>;
  format: ExportFormat;
  ctx: Omit<ExportContext, 'limit'>;
  /** Export only the first batch, of `state.limit` rows, from the state's own position. */
  scope: 'all' | 'page';
  /** Start cursor for a page-scoped export. */
  start?: C | null;
  cap?: number;
  batchSize?: number;
  /** Called once with the row count and whether the cap cut the file short. */
  onDone?: (result: { rows: number; truncated: boolean }) => void;
  onError?: (err: unknown) => void;
}

/** Plain-object row in column order, for NDJSON and JSON. */
function record<R>(def: ExportDefinition<AnyTable, R, unknown>, row: R): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const c of def.columns) {
    const v = c.value(row);
    out[c.key] = typeof v === 'bigint' ? v.toString() : v ?? null;
  }
  return out;
}

export function exportStream<T extends AnyTable, R, C>(opts: StreamOptions<T, R, C>): ReadableStream<Uint8Array> {
  const { def, state, format, ctx, scope } = opts;
  const cap = scope === 'page' ? Math.min(state.limit, opts.cap ?? ROW_CAP) : opts.cap ?? ROW_CAP;
  const batchSize = scope === 'page' ? cap : opts.batchSize ?? BATCH_SIZE;
  const enc = new TextEncoder();
  let cursor: C | null = opts.start ?? null;
  let emitted = 0;
  let started = false;
  let finished = false;
  let cancelled = false;
  // Re-read after awaits: cancel() can run while a batch query is pending.
  const isCancelled = () => cancelled;

  const head = (): string => {
    if (format === 'csv') return `﻿${csvLine(def.columns.map((c) => c.header))}\n`;
    if (format === 'json') return `{"dataset":${JSON.stringify(def.id)},"rows":[`;
    return '';
  };
  const tail = (truncated: boolean): string => {
    if (format === 'csv') return truncated ? `${csvLine([`# ${capMessage(cap)}`])}\n` : '';
    if (format === 'json') return `\n],"truncated":${truncated}${truncated ? `,"message":${JSON.stringify(capMessage(cap))}` : ''}}\n`;
    return truncated ? `${JSON.stringify({ _truncated: true, rows: cap, message: capMessage(cap) })}\n` : '';
  };
  const line = (row: R): string => {
    if (format === 'csv') return `${csvLine(def.columns.map((c) => c.value(row)))}\n`;
    const json = JSON.stringify(record(def as ExportDefinition<AnyTable, R, unknown>, row));
    if (format === 'json') return `${emitted === 0 ? '\n' : ',\n'}${json}`;
    return `${json}\n`;
  };

  const finish = (controller: ReadableStreamDefaultController<Uint8Array>, truncated: boolean) => {
    finished = true;
    controller.enqueue(enc.encode(tail(truncated)));
    controller.close();
    opts.onDone?.({ rows: emitted, truncated });
  };

  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      if (finished || cancelled) return;
      try {
        let chunk = '';
        if (!started) {
          started = true;
          chunk += head();
        }
        const limit = Math.min(batchSize, cap - emitted);
        let batch = await def.rows(state, cursor, { ...ctx, limit });
        // A filtered scan can find no rows while still having older history.
        // Only an exhausted cursor ends an all-results export.
        while (scope !== 'page' && batch.rows.length === 0 && batch.next !== null) {
          if (isCancelled()) return;
          cursor = batch.next;
          batch = await def.rows(state, cursor, { ...ctx, limit });
        }
        if (isCancelled()) return;
        const rows = batch.rows.slice(0, limit);
        for (const row of rows) {
          chunk += line(row);
          emitted++;
        }
        cursor = batch.next;
        if (chunk) controller.enqueue(enc.encode(chunk));
        const more = batch.next !== null || batch.rows.length > limit;
        if (scope === 'page' || !more || rows.length === 0) return finish(controller, false);
        if (emitted >= cap) return finish(controller, true);
      } catch (err) {
        finished = true;
        opts.onError?.(err);
        controller.error(err);
      }
    },
    cancel() {
      cancelled = true;
    },
  });
}
