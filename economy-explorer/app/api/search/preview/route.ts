import { NextResponse, type NextRequest } from 'next/server';
import { suggest } from '@/lib/services/search';
import type { ItemSearchResult, SearchResult } from '@/lib/sql/search';

// GET /api/search/preview?q=<text>&limit=<1-8>
//
// The typeahead as a plain GET, so the ⌘K palette can abort a request in
// flight (a server action can't be cancelled). Same rules and cache as the
// searchPreview server action: 2+ characters, prefix matches, 60 s per
// (type, prefix). `results` is exactly what searchPreview returns; `items` is
// the optional item prefix match. Public names only, like /search.

export const dynamic = 'force-dynamic';

export interface SearchPreviewResponse {
  results: SearchResult[];
  items?: ItemSearchResult[];
}

export async function GET(request: NextRequest) {
  const sp = request.nextUrl.searchParams;
  const q = sp.get('q') ?? '';
  const limit = Math.min(Math.max(1, Number(sp.get('limit')) || 4), 8);
  const hits = await suggest(q, { limit, kinds: ['account', 'firm', 'player', 'item'] });
  const body: SearchPreviewResponse = {
    results: hits.filter((h): h is SearchResult => h.kind !== 'item'),
    items: hits.filter((h): h is ItemSearchResult => h.kind === 'item'),
  };
  return NextResponse.json(body, { headers: { 'Cache-Control': 'private, max-age=30' } });
}
