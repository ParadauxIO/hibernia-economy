// Fixture preview endpoint for the gallery's EntityLink hover cards. Real
// previews are served by the entity workstream; this returns fictional data.
import type { EntityPreviewData } from '@democracycraft/design-system';

const PREVIEWS: Record<string, EntityPreviewData> = {
  player: { title: 'Ada_Lovelace', subtitle: 'Player · joined Mar 2024', rows: [{ label: 'Personal accounts', value: '2' }, { label: 'Firm roles', value: 'Director, Riverside Builders' }] },
  account: { title: 'Riverside Builders · Operating', subtitle: 'Business account · #1204', rows: [{ label: 'Balance', value: '$84,210.55' }, { label: 'In, 30 days', value: '$41.2K' }, { label: 'Out, 30 days', value: '$37.9K' }] },
  firm: { title: 'Riverside Builders', subtitle: 'Firm · 14 staff', rows: [{ label: 'Accounts', value: '3' }, { label: 'Headquarters', value: 'Riverside, 120 / 64 / -340' }] },
  item: { title: 'Diamond', subtitle: 'Item · 412 shops', rows: [{ label: 'Average price, 7 days', value: '$4.12' }, { label: 'Sales, 7 days', value: '1,840' }] },
};

export async function GET(_request: Request, { params }: { params: Promise<{ kind: string }> }): Promise<Response> {
  const { kind } = await params;
  const preview = PREVIEWS[kind];
  if (!preview) return Response.json({ error: 'unknown kind' }, { status: 404 });
  return Response.json(preview, { headers: { 'Cache-Control': 'no-store' } });
}
