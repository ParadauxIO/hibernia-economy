import { NextResponse } from 'next/server';
import { getTaxRates } from '@/lib/services/taxRates';

// Published tax rates support the public documentation.
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
