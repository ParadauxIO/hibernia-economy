// EntityLink hover-card data (DS README, EntityPreviewData): `{ title, subtitle?, rows? }`.
// Public data only (lib/services/preview), cached server-side for five minutes
// and by browsers and the CDN for one.
import { getPreview, type PreviewKind } from '@/lib/services/preview';

export const dynamic = 'force-dynamic';

const KINDS = new Set<PreviewKind>(['player', 'account', 'firm', 'item']);

export async function GET(_request: Request, { params }: { params: Promise<{ kind: string; id: string }> }): Promise<Response> {
  const { kind, id } = await params;
  const key = decodeURIComponent(id);
  if (!KINDS.has(kind as PreviewKind) || key.length === 0 || key.length > 64) return Response.json({ error: 'not found' }, { status: 404 });
  const preview = await getPreview(kind as PreviewKind, key);
  if (!preview) return Response.json({ error: 'not found' }, { status: 404, headers: { 'Cache-Control': 'public, max-age=60' } });
  return Response.json(preview, { headers: { 'Cache-Control': 'public, max-age=60, stale-while-revalidate=300' } });
}
