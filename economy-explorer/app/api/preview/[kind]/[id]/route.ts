// EntityLink hover-card data (DS README, EntityPreviewData): `{ title, subtitle?, rows? }`.
// Item previews require login; other entity previews remain public.
import { getViewer } from '@/lib/auth/viewer';
import { getPreview, type PreviewKind } from '@/lib/services/preview';

export const dynamic = 'force-dynamic';

const KINDS = new Set<PreviewKind>(['player', 'account', 'firm', 'item']);

export async function GET(_request: Request, { params }: { params: Promise<{ kind: string; id: string }> }): Promise<Response> {
  const { kind, id } = await params;
  const key = decodeURIComponent(id);
  if (!KINDS.has(kind as PreviewKind) || key.length === 0 || key.length > 64) return Response.json({ error: 'not found' }, { status: 404 });
  if (kind === 'item' && (await getViewer()).anon) {
    return Response.json({ error: 'unauthorized' }, { status: 401, headers: { 'Cache-Control': 'private, no-store' } });
  }
  const cacheControl = kind === 'item' ? 'private, no-store' : 'public, max-age=60, stale-while-revalidate=300';
  const preview = await getPreview(kind as PreviewKind, key);
  if (!preview) return Response.json({ error: 'not found' }, { status: 404, headers: { 'Cache-Control': kind === 'item' ? 'private, no-store' : 'public, max-age=60' } });
  return Response.json(preview, { headers: { 'Cache-Control': cacheControl } });
}
