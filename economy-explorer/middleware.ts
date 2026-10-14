import { NextResponse, type NextRequest, type NextFetchEvent } from 'next/server';
import { auth } from '@/lib/auth/authjs';

/** Logging and theme handling stay public. The admin boundary verifies the
 * signed session at the edge; live permissions are checked again by the page
 * and action/service using the database, never stale roles in a JWT. */
const authenticatedAdmin = auth((req, event: NextFetchEvent) => {
  void event;
  if (!req.auth?.keycloakSub) {
    const login = new URL('/api/auth/signin', req.url);
    login.searchParams.set('callbackUrl', req.nextUrl.pathname + req.nextUrl.search);
    return NextResponse.redirect(login);
  }
  return handleRequest(req);
});

export default function middleware(req: NextRequest, event: NextFetchEvent) {
  const admin = req.nextUrl.pathname === '/admin' || req.nextUrl.pathname.startsWith('/admin/');
  // Only the explicit legacy role shim bypasses session verification in test builds.
  // Signed-session browser tests (no shim cookie) exercise the real edge boundary.
  const roleShim = process.env.E2E_TEST_AUTH === '1' && req.cookies.has('e2e_viewer');
  if (admin && !roleShim) return authenticatedAdmin(req, event);
  return handleRequest(req);
}

function handleRequest(req: NextRequest) {
  const start = Date.now();
  const ua = req.headers.get('user-agent') ?? '';
  const ip = req.headers.get('x-forwarded-for')?.split(',')[0]?.trim() ?? '';
  const hasSession = req.cookies.has('authjs.session-token') || req.cookies.has('__Secure-authjs.session-token');

  // Theme preview override: ?theme=red|blue sets a cookie and redirects to
  // the same path without the param so the layout (which reads cookies on
  // the next render) picks it up. ?theme=clear drops the cookie.
  const themeParam = req.nextUrl.searchParams.get('theme');
  if (themeParam === 'red' || themeParam === 'blue' || themeParam === 'clear') {
    const url = req.nextUrl.clone();
    url.searchParams.delete('theme');
    const redirect = NextResponse.redirect(url);
    if (themeParam === 'clear') {
      redirect.cookies.delete('theme');
    } else {
      redirect.cookies.set('theme', themeParam, {
        path: '/',
        sameSite: 'lax',
        maxAge: 60 * 60 * 24 * 30,
      });
    }
    return redirect;
  }

  const res = NextResponse.next();

  // Structured access log — one line, JSON, picked up by Loki/Grafana.
  console.log(
    JSON.stringify({
      ts: new Date().toISOString(),
      kind: 'access',
      tenant: process.env.TENANT ?? 'unknown',
      method: req.method,
      path: req.nextUrl.pathname,
      query: req.nextUrl.search || undefined,
      ip,
      ua,
      hasSession,
      // Middleware's own synchronous work only — not request/render time.
      mwMs: Date.now() - start,
    }),
  );

  return res;
}

export const config = {
  matcher: [
    // Run on everything except static assets and the Auth.js endpoint itself.
    '/((?!_next/static|_next/image|favicon.ico|robots.txt|healthz|api/auth).*)',
  ],
};
