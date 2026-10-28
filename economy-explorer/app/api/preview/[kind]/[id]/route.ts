// EntityLink hover-card data (DS README, EntityPreviewData): `{ title, subtitle?, rows? }`.
// Every entity preview requires a linked account; responses are never public-cacheable.
import { getViewer } from '@/lib/auth/viewer';
import { getPreview, type PreviewKind } from '@/lib/services/preview';

export const dynamic = 'force-dynamic';

const KINDS = new Set<PreviewKind>(['player', 'account', 'firm', 'item']);

export async function GET(_request: Request, { params }: { params: Promise<{ kind: string; id: string }> }): Promise<Response> {
  const { kind, id } = await params;
  const key = decodeURIComponent(id);
  if (!KINDS.has(kind as PreviewKind) || key.length === 0 || key.length > 64) return Response.json({ error: 'not found' }, { status: 404 });
  const viewer = await getViewer();
  if (viewer.anon) {
    return Response.json({ error: 'unauthorized' }, { status: 401, headers: { 'Cache-Control': 'private, no-store' } });
  }
  if (!viewer.linked || !viewer.minecraftUuid) return Response.json({ error: 'Linked Minecraft account required.' }, { status: 403, headers: { 'Cache-Control': 'private, no-store' } });
  const cacheControl = 'private, no-store';
  const preview = await getPreview(kind as PreviewKind, key);
  if (!preview) return Response.json({ error: 'not found' }, { status: 404, headers: { 'Cache-Control': 'private, no-store' } });
  return Response.json(preview, { headers: { 'Cache-Control': cacheControl } });
}
