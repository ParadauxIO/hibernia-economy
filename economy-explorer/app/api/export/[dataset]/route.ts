import { NextResponse, type NextRequest } from 'next/server';
import { auditView } from '@/lib/audit';
import { getViewer } from '@/lib/auth/viewer';
import '@/lib/export/datasets';
import { EXPORT_FORMATS, type ExportFormat } from '@/lib/export/href';
import { EXPORT_LIMIT, takeExportSlot } from '@/lib/export/rateLimit';
import { getExport, resolveTable } from '@/lib/export/registry';
import { CONTENT_TYPES, ROW_CAP, exportStream } from '@/lib/export/stream';
import { parseTableState, serializeTableState, stateCursor } from '@/lib/tables/core';

// GET /api/export/<dataset>?<the page's URL state>&format=csv|ndjson|json[&scope=page]
//
// Streams every row the page's filters select (spec §9.3), up to 100k. Order of
// checks: signed in (401), dataset exists (404), the page's own permission for
// this state (403, audited), the per-viewer rate limit (429), then the audit
// record and the stream. Datasets are registered in lib/export/datasets.

export const dynamic = 'force-dynamic';

const NO_STORE = { 'Cache-Control': 'private, no-store' };

function stamp(d: Date): string {
  return d.toISOString().slice(0, 16).replace(/[-:]/g, '').replace('T', '-');
}

export async function GET(request: NextRequest, { params }: { params: Promise<{ dataset: string }> }) {
  const { dataset } = await params;
  const viewer = await getViewer();
  if (viewer.anon) return NextResponse.json({ error: 'Sign in to export.' }, { status: 401, headers: NO_STORE });

  const def = getExport(dataset);
  if (!def) return NextResponse.json({ error: 'Unknown dataset.' }, { status: 404, headers: NO_STORE });

  const sp = request.nextUrl.searchParams;
  const rawFormat = sp.get('format') ?? 'csv';
  const format: ExportFormat = (EXPORT_FORMATS as readonly string[]).includes(rawFormat) ? (rawFormat as ExportFormat) : 'csv';
  const scope = sp.get('scope') === 'page' ? 'page' : 'all';
  const table = await resolveTable(def);
  const parsed = parseTableState(sp, table);
  const state = scope === 'page' ? parsed : { ...parsed, page: 1, cursor: null };
  const query = serializeTableState(state, table, scope === 'page' ? [['scope', 'page']] : []);
  const auditPath = `/api/export/${def.id}${query ? `?${query}` : ''}`.slice(0, 255);
  const record = (outcome: number) => auditView(viewer, { method: 'EXPORT', path: auditPath, targetType: 'global', targetId: def.id, outcome });

  if (!(await def.authorize(viewer, state))) {
    await record(403);
    return NextResponse.json({ error: 'You do not have access to this data.' }, { status: 403, headers: NO_STORE });
  }

  const slot = await takeExportSlot(viewer.keycloakSub);
  if (!slot.allowed) {
    return NextResponse.json(
      { error: `Too many exports. You can start ${EXPORT_LIMIT} a minute; try again in ${slot.retryAfterS} s.` },
      { status: 429, headers: { ...NO_STORE, 'Retry-After': String(slot.retryAfterS) } },
    );
  }

  await record(200);
  const body = exportStream({
    def,
    state,
    format,
    scope,
    start: scope === 'page' && table.pagination === 'cursor' ? stateCursor(state) : null,
    ctx: { viewer },
    onError: (err) => console.warn(`[export] ${def.id} failed:`, err),
  });
  const filename = `${def.id}-${stamp(new Date())}.${format === 'ndjson' ? 'ndjson' : format}`;
  return new Response(body, {
    status: 200,
    headers: {
      ...NO_STORE,
      'Content-Type': CONTENT_TYPES[format],
      'Content-Disposition': `attachment; filename="${filename}"`,
      'X-Export-Row-Cap': String(ROW_CAP),
      'X-RateLimit-Remaining': String(slot.remaining),
      'X-Content-Type-Options': 'nosniff',
    },
  });
}
