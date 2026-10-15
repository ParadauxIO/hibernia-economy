import { NextResponse } from 'next/server';
import { getTaxRates } from '@/lib/services/taxRates';

// Live tax rates for the statically generated /docs pages (PAR-80). The docs are
// built once and shared by every tenant, so the numbers can't be baked in; the
// LiveDocBlocks client component fetches them from here. Public and the same for
// every viewer, so a shared HTTP cache may hold it briefly.
export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    return NextResponse.json(await getTaxRates(), {
      headers: { 'Cache-Control': 'public, max-age=60' },
    });
  } catch {
    return NextResponse.json({ error: 'unavailable' }, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
}
