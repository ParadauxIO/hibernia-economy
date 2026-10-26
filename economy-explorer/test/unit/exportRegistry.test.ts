import { afterEach, describe, expect, it } from 'vitest';
import { exportHref, exportHrefs } from '@/lib/export/href';
import { defineExport, exportIds, getExport, resetExportsForTests, resolveTable, type ExportDefinition } from '@/lib/export/registry';
import { capMessage, exportStream } from '@/lib/export/stream';
import { defineTable, parseTableState, type AnyTable } from '@/lib/tables/core';

const table = defineTable({
  id: 'widgets',
  filters: { kind: { label: 'Kind', kind: 'enum', options: [{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }] } },
  sorts: { n: 'Number' },
  defaultSort: '-n',
  pagination: 'cursor',
  pageSize: 25,
});
const directory = defineTable({ id: 'things', filters: {}, sorts: { n: 'N' }, defaultSort: 'n', pageSize: 10 });

interface Row { n: number; name: string }

/** A dataset over 0..total-1 that pages by offset and records every call. */
function dataset(total: number, calls: { cursor: number | null; limit: number }[] = []): ExportDefinition<typeof table, Row, number> {
  return {
    id: 'widgets',
    title: 'Widgets',
    table,
    authorize: () => true,
    rows: async (_state, cursor, { limit }) => {
      calls.push({ cursor, limit });
      const start = cursor ?? 0;
      const rows = Array.from({ length: Math.max(0, Math.min(limit, total - start)) }, (_, i) => ({ n: start + i, name: start + i === 1 ? '=evil()' : `w${start + i}` }));
      return { rows, next: start + rows.length < total ? start + rows.length : null };
    },
    columns: [
      { key: 'n', header: 'Number', value: (r) => r.n },
      { key: 'name', header: 'Name', value: (r) => r.name },
    ],
  };
}

const state = parseTableState(new URLSearchParams('f=kind:in:a'), table);
const viewer = { anon: false } as never;

async function read(stream: ReadableStream<Uint8Array>): Promise<string> {
  // ignoreBOM keeps the CSV byte-order mark that Response.text() would strip.
  return new TextDecoder('utf-8', { ignoreBOM: true }).decode(await new Response(stream).arrayBuffer());
}

afterEach(() => resetExportsForTests());

describe('defineExport', () => {
  it('registers datasets by id and resolves static or async tables', async () => {
    defineExport(dataset(1));
    defineExport({ ...dataset(1), id: 'lazy', table: async () => table });
    expect(exportIds()).toEqual(['lazy', 'widgets']);
    expect(getExport('widgets')?.title).toBe('Widgets');
    expect(getExport('__proto__')).toBeUndefined();
    expect(getExport('nope')).toBeUndefined();
    expect(await resolveTable(getExport('lazy')!)).toBe(table);
  });

  it('rejects bad ids, duplicate ids, empty and duplicate columns', () => {
    defineExport(dataset(1));
    expect(() => defineExport(dataset(1))).toThrow(/already registered/);
    expect(() => defineExport({ ...dataset(1), id: 'Bad Id' })).toThrow(/bad id/);
    expect(() => defineExport({ ...dataset(1), id: 'empty', columns: [] })).toThrow(/no columns/);
    expect(() => defineExport({ ...dataset(1), id: 'dupe', columns: [...dataset(1).columns, dataset(1).columns[0]!] })).toThrow(/duplicate column/);
  });
});

describe('export hrefs', () => {
  it('carry the page state without paging, plus the format', () => {
    const paged = { ...state, cursor: 'abc', limit: 100 };
    expect(exportHref('widgets', paged, table, 'csv')).toBe('/api/export/widgets?format=csv&f=kind:in:a');
    expect(exportHref('widgets', state, table, 'json')).toBe('/api/export/widgets?format=json&f=kind:in:a');
  });

  it('offer "this page" only for tables paged by number', () => {
    expect(exportHrefs('widgets', state, table)).toEqual({ all: { csv: '/api/export/widgets?format=csv&f=kind:in:a', json: '/api/export/widgets?format=json&f=kind:in:a' } });
    const dirState = { ...parseTableState(new URLSearchParams('page=3'), directory) };
    expect(exportHrefs('things', dirState, directory).page).toEqual({
      csv: '/api/export/things?format=csv&scope=page&page=3',
      json: '/api/export/things?format=json&scope=page&page=3',
    });
  });
});

describe('exportStream', () => {
  const ctx = { viewer };

  it('streams CSV with a BOM, a header, escaped cells and no trailer under the cap', async () => {
    const calls: { cursor: number | null; limit: number }[] = [];
    let done: unknown;
    const text = await read(exportStream({ def: dataset(3, calls), state, format: 'csv', scope: 'all', ctx, batchSize: 2, onDone: (r) => { done = r; } }));
    expect(text).toBe('﻿Number,Name\n0,w0\n1,\'=evil()\n2,w2\n');
    expect(calls).toEqual([{ cursor: null, limit: 2 }, { cursor: 2, limit: 2 }]);
    expect(done).toEqual({ rows: 3, truncated: false });
  });

  it('continues through empty filtered scan batches to older matches', async () => {
    const cursors: (number | null)[] = [];
    const sparse = {
      ...dataset(1),
      rows: async (_state: typeof state, cursor: number | null) => {
        cursors.push(cursor);
        const scan = cursor ?? 0;
        return scan < 22 ? { rows: [], next: scan + 1 } : { rows: [{ n: 42, name: 'older match' }], next: null };
      },
    };
    const result = JSON.parse(await read(exportStream({ def: sparse, state, format: 'json', scope: 'all', ctx })));
    expect(result).toEqual({ dataset: 'widgets', rows: [{ n: 42, name: 'older match' }], truncated: false });
    expect(cursors).toEqual([null, ...Array.from({ length: 22 }, (_, i) => i + 1)]);
  });

  it('stops at the row cap and says so in each format', async () => {
    const csv = await read(exportStream({ def: dataset(10), state, format: 'csv', scope: 'all', ctx, cap: 4, batchSize: 3 }));
    expect(csv.trimEnd().split('\n')).toEqual(['﻿Number,Name', '0,w0', "1,'=evil()", '2,w2', '3,w3', `# ${capMessage(4)}`]);

    const nd = await read(exportStream({ def: dataset(10), state, format: 'ndjson', scope: 'all', ctx, cap: 2 }));
    const lines = nd.trimEnd().split('\n').map((l) => JSON.parse(l));
    expect(lines).toEqual([{ n: 0, name: 'w0' }, { n: 1, name: '=evil()' }, { _truncated: true, rows: 2, message: capMessage(2) }]);

    const json = JSON.parse(await read(exportStream({ def: dataset(10), state, format: 'json', scope: 'all', ctx, cap: 2 })));
    expect(json).toEqual({ dataset: 'widgets', rows: [{ n: 0, name: 'w0' }, { n: 1, name: '=evil()' }], truncated: true, message: capMessage(2) });
  });

  it('is not truncated when the data ends exactly at the cap', async () => {
    const json = JSON.parse(await read(exportStream({ def: dataset(4), state, format: 'json', scope: 'all', ctx, cap: 4, batchSize: 2 })));
    expect(json.rows).toHaveLength(4);
    expect(json.truncated).toBe(false);
    expect(JSON.parse(await read(exportStream({ def: dataset(0), state, format: 'json', scope: 'all', ctx })))).toEqual({ dataset: 'widgets', rows: [], truncated: false });
  });

  it('exports one page of state.limit rows from the start cursor for scope=page', async () => {
    const calls: { cursor: number | null; limit: number }[] = [];
    const text = await read(exportStream({ def: dataset(100, calls), state: { ...state, limit: 3 }, format: 'ndjson', scope: 'page', ctx, start: 10 }));
    expect(text.trimEnd().split('\n').map((l) => JSON.parse(l).n)).toEqual([10, 11, 12]);
    expect(calls).toEqual([{ cursor: 10, limit: 3 }]);
  });

  it('errors the stream (and reports it) when a batch fails', async () => {
    const errors: unknown[] = [];
    const failing = { ...dataset(1), rows: async () => { throw new Error('db down'); } } as ExportDefinition<AnyTable, Row, number>;
    await expect(read(exportStream({ def: failing, state, format: 'csv', scope: 'all', ctx, onError: (e) => errors.push(e) }))).rejects.toThrow('db down');
    expect(errors).toHaveLength(1);
  });
});
