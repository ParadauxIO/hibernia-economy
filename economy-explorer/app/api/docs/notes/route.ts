import { NextResponse } from 'next/server';
import { parseDocNotes } from '@/lib/docsNotes';

export const dynamic = 'force-dynamic';

export async function GET() {
  const headers = { 'Cache-Control': 'private, no-store' };
  try {
    // Read at request time: the same image serves multiple tenant deployments.
    return NextResponse.json(parseDocNotes(process.env.DOCS_TOP_NOTES), { headers });
  } catch {
    // Do not echo configuration content into the response or logs.
    console.error('Invalid DOCS_TOP_NOTES configuration');
    return NextResponse.json({ error: 'unavailable' }, { status: 503, headers });
  }
}
