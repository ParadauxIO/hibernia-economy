import { NextResponse } from 'next/server';
import { getViewer } from '@/lib/auth/viewer';
import { chromeViewer } from '@/lib/services/nav';

// The same snapshot the root layout renders the chrome from, for client
// refreshes (components/ViewerContext refreshViewer) and tests. Presentation
// only; server-side gates always resolve current permissions. Per-user, so
// never cached at the HTTP layer.
export const dynamic = 'force-dynamic';

export async function GET() {
  return NextResponse.json(await chromeViewer(await getViewer()), {
    headers: { 'Cache-Control': 'private, no-store' },
  });
}
