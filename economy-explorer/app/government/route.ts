import { NextResponse, type NextRequest } from 'next/server';
import { governmentRedirect } from '@/lib/services/governmentModel';

// /government moved to the /gov workspace (PAR-539). A route handler rather
// than a page, so old links get a real HTTP redirect instead of a streamed
// loading shell followed by a client-side replace. Access is checked at /gov.
export function GET(req: NextRequest) {
  return NextResponse.redirect(new URL(governmentRedirect(req.nextUrl.searchParams), req.url), 308);
}
