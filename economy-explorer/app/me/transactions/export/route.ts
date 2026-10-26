import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { meGate } from '@/lib/services/me';
import { getMyTransactions } from '@/lib/services/meTransactions';
import { parseTableState } from '@/lib/tables/core';
import { meTransactionsTable } from '@/lib/tables/me';

// Full CSV export of /me/transactions: every row the current filters select,
// not just the page, with the page's access rules (spec §8.2, §9.3). Pages
// through the same bounded queries as the table, 100 rows at a time, up to
// 100,000 rows.
//
// TODO(POWER, PAR-540): register as the `me-transactions` dataset in
// lib/export/registry.ts once it lands, and point the page's export at
// /api/export/me-transactions (rate limit and audit action `export` there).

export const dynamic = 'force-dynamic';

const MAX_ROWS = 100_000;
const BATCH = 100;

/** A CSV cell. Text that a spreadsheet would run as a formula is prefixed with '. Numbers are left alone. */
function cell(v: string | number | null | undefined, text = true): string {
  if (v === null || v === undefined) return '';
  let s = String(v);
  if (text && /^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export async function GET(request: Request): Promise<Response> {
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return new Response(gate.kind === 'login' ? 'Log in to export your transactions.' : 'Not available.', { status: gate.kind === 'login' ? 401 : 403 });
  const params = new URL(request.url).searchParams;
  params.delete('cursor');
  params.set('limit', String(BATCH));
  const base = parseTableState(params, meTransactionsTable);
  await auditView(viewer, { method: 'GET', path: '/me/transactions/export', targetType: 'player', targetId: gate.uuid });

  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      try {
        controller.enqueue(encoder.encode(`﻿${['Txn', 'When (UTC)', 'Account', 'Account ID', 'Description', 'Category', 'Counterparty', 'Counterparty ID', 'Amount'].join(',')}\n`));
        let cursor: string | null = null;
        let written = 0;
        for (;;) {
          const page = await getMyTransactions(viewer, gate.uuid, { ...base, cursor });
          const lines = page.rows.slice(0, MAX_ROWS - written).map((r) => [
            cell(r.txnId, false), cell(r.at.toISOString(), false), cell(r.account.name), cell(r.account.accountId, false),
            cell(r.description), cell(r.categoryLabel), cell(r.counterparty?.name), cell(r.counterparty?.accountId, false), cell(r.amount, false),
          ].join(','));
          if (lines.length) controller.enqueue(encoder.encode(`${lines.join('\n')}\n`));
          written += lines.length;
          if (written >= MAX_ROWS) {
            controller.enqueue(encoder.encode(`# Stopped at ${MAX_ROWS.toLocaleString('en-US')} rows. Narrow the filters (a date range) to export the rest.\n`));
            break;
          }
          if (!page.nextCursor) break;
          cursor = page.nextCursor;
        }
        controller.close();
      } catch (err) {
        controller.error(err);
      }
    },
  });
  return new Response(stream, {
    headers: {
      'Content-Type': 'text/csv; charset=utf-8',
      'Content-Disposition': 'attachment; filename="my-transactions.csv"',
      'Cache-Control': 'private, no-store',
    },
  });
}
