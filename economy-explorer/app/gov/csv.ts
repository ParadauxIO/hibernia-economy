import { escapeCsvCell, type CsvCell } from '@/lib/export/csv';

// CSV responses for /gov downloads that aren't tables (the monthly report).
// Table exports go through the shared export route (lib/export/datasets/gov.ts).

export function csv(rows: readonly (readonly CsvCell[])[]): string {
  return rows.map((r) => r.map(escapeCsvCell).join(',')).join('\r\n') + '\r\n';
}

export function csvResponse(body: string, filename: string): Response {
  return new Response(body, {
    headers: {
      'Content-Type': 'text/csv; charset=utf-8',
      'Content-Disposition': `attachment; filename="${filename.replace(/[^A-Za-z0-9._-]/g, '_')}"`,
      'Cache-Control': 'private, no-store',
      'X-Content-Type-Options': 'nosniff',
    },
  });
}
