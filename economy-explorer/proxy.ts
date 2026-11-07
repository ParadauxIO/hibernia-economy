import { NextResponse, type NextRequest, type NextFetchEvent } from 'next/server';
import { createRequestBlocker } from '@/lib/requestBlocking';
import { auth } from '@/lib/auth/authjs';
import { normalizeCapability } from '@/lib/auth/capabilities';
import { isPublicExplorerPath, validMinecraftUuid } from '@/lib/auth/routeAccess';
import { findIdentityBySub, upsertIdentity } from '@/lib/sql/identity';
import { isQuietPath } from '@/lib/accessLog';
import { checkAccountRateLimit } from '@/lib/requestRateLimit';

const blockRequest = createRequestBlocker(process.env);

/** Directory pagination requires sign-in; protected data also requires a link. */
const authenticatedPage = auth(async (req, event: NextFetchEvent) => {
  void event;
  const api = req.nextUrl.pathname.startsWith('/api/');
  const callbackUrl = req.nextUrl.pathname + req.nextUrl.search;
  if (!req.auth?.keycloakSub) {
    if (api) return NextResponse.json({ error: 'Sign in required.' }, { status: 401, headers: { 'Cache-Control': 'private, no-store' } });
    return accessPrompt(req, callbackUrl);
  }
  const limited = await checkAccountRateLimit(req.auth.keycloakSub);
  if (limited) {
    return new NextResponse(limited.status === 429 ? 'Too many requests' : 'Request protection unavailable', {
      status: limited.status,
      headers: { 'Cache-Control': 'private, no-store', 'Retry-After': String(limited.retryAfter) },
    });
  }
  // Later pages of public directories need a verified session, but contain no
  // additional private fields and do not require a linked Minecraft identity.
  if (isPublicExplorerPath(req.nextUrl.pathname)) return handleRequest(req);
  // A link must be established durably. Verified provider claims can establish
  // it just as getViewer does; identity-store errors fail closed.
  let linked = false;
  try {
    let identity = await findIdentityBySub(req.auth.keycloakSub);
    if (validMinecraftUuid(req.auth.minecraftUuid) && identity?.playerUuid !== req.auth.minecraftUuid) {
      await upsertIdentity({ sub: req.auth.keycloakSub, playerUuid: req.auth.minecraftUuid, minecraftName: req.auth.minecraftName ?? null, linkedBy: 'token-claim' });
      identity = await findIdentityBySub(req.auth.keycloakSub);
    }
    linked = validMinecraftUuid(identity?.playerUuid);
  } catch {
    return new NextResponse('Identity verification unavailable', { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
  if (!linked) {
    if (api) return NextResponse.json({ error: 'Linked Minecraft account required.' }, { status: 403, headers: { 'Cache-Control': 'private, no-store' } });
    return accessPrompt(req, callbackUrl);
  }
  return handleRequest(req);
});

function accessPrompt(req: NextRequest, callbackUrl: string) {
  // Redirect before rendering: protected pages and their metadata never execute.
  const url = new URL('/access', req.url);
  url.searchParams.set('callbackUrl', callbackUrl);
  return NextResponse.redirect(url, { headers: { 'Cache-Control': 'private, no-store' } });
}

export default function proxy(req: NextRequest, event: NextFetchEvent) {
  const decision = blockRequest(req.headers);
  if (decision.reason) {
    console.log(JSON.stringify({
      ts: new Date().toISOString(), kind: 'blocked', tenant: process.env.TENANT ?? 'unknown',
      method: req.method, path: req.nextUrl.pathname, ip: decision.ip,
      ua: req.headers.get('user-agent') ?? '', reason: decision.reason, status: 403,
    }));
    return new NextResponse('Forbidden', {
      status: 403, headers: { 'Cache-Control': 'no-store', 'Content-Type': 'text/plain' },
    });
  }
  // Pre-consolidation Health bookmarks keep their window on the overview. Done
  // here as a plain HTTP redirect: a redirect() from the page fires after the
  // loading.tsx shell has streamed, so the browser got a 200, a client-side
  // replace AND a <meta refresh> reload of the same target.
  if (req.nextUrl.pathname === '/economy/health') {
    const overview = new URL('/', req.url);
    overview.searchParams.set('days', req.nextUrl.searchParams.get('days') === '7' ? '7' : '30');
    return NextResponse.redirect(overview);
  }
  // Only a recognized fixture role bypasses session verification in test builds.
  const role = req.cookies.get('e2e_viewer')?.value;
  const roleShim = process.env.E2E_TEST_AUTH === '1' && !!role && role !== 'anon' && normalizeCapability(role) !== null;
  if (!isPublicExplorerPath(req.nextUrl.pathname, req.nextUrl.searchParams) && !roleShim) return authenticatedPage(req, event);
  return handleRequest(req);
}

function handleRequest(req: NextRequest) {
  const start = Date.now();
  const ua = req.headers.get('user-agent') ?? '';
  const ip = blockRequest(req.headers).ip;
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
  if (isQuietPath(req.nextUrl.pathname)) return res;

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
    // Include auth endpoints so blocks precede session work. Keep liveness public.
    // /metrics is bearer-token protected in its route handler; skipping the proxy
    // keeps Prometheus scrapes out of the access log and the UA/IP denylist.
    '/((?!_next/static|favicon.ico|robots.txt|healthz|metrics).*)',
  ],
};
