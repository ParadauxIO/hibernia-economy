import { NextResponse } from 'next/server';
import { getTaxRates } from '@/lib/services/taxRates';

// Live tax rates require a linked account at the request boundary, like all
// dynamic economy data. Static documentation remains public.
export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    return NextResponse.json(await getTaxRates(), {
      headers: { 'Cache-Control': 'private, no-store' },
    });
  } catch {
    return NextResponse.json({ error: 'unavailable' }, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
}
