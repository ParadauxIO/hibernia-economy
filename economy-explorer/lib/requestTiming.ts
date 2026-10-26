import http, { type IncomingMessage, type ServerResponse } from 'node:http';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  newRequestTiming,
  observeRequest,
  runWithRequestTiming,
  type RequestKind,
  type RequestTiming,
} from '@/lib/metrics';

// Per-request timing (PAR-531): the Server-Timing header and explorer_render_seconds.
//
// Server components can't set response headers, and with the root loading.tsx
// every page streams: Next sends the headers with the loading shell, before any
// Suspense boundary has fetched its data. So nothing inside the render can put
// the full DB time in a header. Instead, instrumentation.ts wraps the Node HTTP
// server: each request runs inside an AsyncLocalStorage accumulator that DB and
// cache hooks add to, the header is written from it when Next writes the
// response head, and the metrics are observed when the response closes.
//
// Consequence: the Server-Timing header covers work done before the first byte
// (redirects, route handlers, anything awaited outside Suspense). Work in
// streamed sections lands in explorer_render_seconds and
// explorer_render_db_queries, which are measured to the last byte.

/** `Server-Timing` value for the work recorded so far. */
export function buildServerTiming(timing: RequestTiming, nowMs: number): string {
  const ms = (n: number) => Math.max(0, n).toFixed(1);
  const parts = [`total;dur=${ms(nowMs - timing.startMs)}`];
  const noun = timing.dbQueries === 1 ? 'query' : 'queries';
  parts.push(`db;dur=${ms(timing.dbMs)};desc="${timing.dbQueries} ${noun}"`);
  if (timing.cacheHits + timing.cacheMisses > 0) {
    parts.push(`cache;desc="${timing.cacheMisses > 0 ? 'miss' : 'hit'}"`);
  }
  return parts.join(', ');
}

// ── Route labels ─────────────────────────────────────────────────────────────

interface RoutesManifest {
  staticRoutes?: { page: string; regex: string }[];
  dynamicRoutes?: { page: string; regex: string }[];
}

type RouteMatcher = (pathname: string) => string;

/**
 * Map a request path to its route pattern (`/accounts/123` → `/accounts/[accountId]`)
 * using the build's routes manifest, so the `route` label is bounded by the
 * number of routes, never by ids or player names. Unknown paths are `other`.
 */
export function routeMatcher(manifest: RoutesManifest): RouteMatcher {
  const routes = [...(manifest.staticRoutes ?? []), ...(manifest.dynamicRoutes ?? [])].map((r) => ({
    page: r.page,
    re: new RegExp(r.regex),
  }));
  return (pathname) => routes.find((r) => r.re.test(pathname))?.page ?? 'other';
}

function loadRouteMatcher(): RouteMatcher {
  // `next start` and the standalone server run from the app root; `next dev`
  // writes its manifests under .next/dev.
  for (const dir of [join('.next'), join('.next', 'dev')]) {
    try {
      const manifest = JSON.parse(readFileSync(join(process.cwd(), dir, 'routes-manifest.json'), 'utf8')) as RoutesManifest;
      if (manifest.staticRoutes?.length || manifest.dynamicRoutes?.length) return routeMatcher(manifest);
    } catch {
      /* try the next location */
    }
  }
  return () => 'other';
}

// Paths that aren't page or API work: build assets, the probes and the scrape itself.
const UNTIMED = /^\/(?:_next\/|__nextjs|metrics(?:\/|$)|healthz(?:\/|$)|favicon\.ico$)/;

/** document = a full page load, rsc = client navigation, prefetch = router prefetch. */
export function requestKind(headers: IncomingMessage['headers']): RequestKind {
  if (headers['next-router-prefetch']) return 'prefetch';
  if (headers.rsc) return 'rsc';
  return 'document';
}

// ── HTTP server hook ─────────────────────────────────────────────────────────

const INSTALLED = Symbol.for('economy-explorer.requestTiming.installed');

/**
 * Wrap `http.Server#emit('request')` once per process. Called from
 * instrumentation.ts in the Node runtime; harmless if called again.
 */
export function installRequestTiming(): void {
  const g = globalThis as unknown as Record<symbol, unknown>;
  if (g[INSTALLED]) return;
  g[INSTALLED] = true;

  let matchRoute: RouteMatcher | undefined;
  const proto = http.Server.prototype as unknown as { emit: (event: string, ...args: unknown[]) => boolean };
  const emit = proto.emit;
  proto.emit = function (this: http.Server, event: string, ...args: unknown[]): boolean {
    if (event !== 'request') return emit.call(this, event, ...args);
    const req = args[0] as IncomingMessage;
    const res = args[1] as ServerResponse;
    const pathname = (req.url ?? '/').split('?', 1)[0] ?? '/';
    if (UNTIMED.test(pathname)) return emit.call(this, event, ...args);

    const timing = newRequestTiming();
    const writeHead = res.writeHead;
    res.writeHead = function (this: ServerResponse, ...headArgs: unknown[]) {
      if (!this.headersSent) {
        const value = buildServerTiming(timing, performance.now());
        const existing = this.getHeader('server-timing');
        this.setHeader('Server-Timing', existing ? `${String(existing)}, ${value}` : value);
      }
      return (writeHead as (...a: unknown[]) => ServerResponse).apply(this, headArgs);
    } as ServerResponse['writeHead'];
    res.once('close', () => {
      matchRoute ??= loadRouteMatcher();
      const seconds = (performance.now() - timing.startMs) / 1000;
      observeRequest(matchRoute(pathname), requestKind(req.headers), seconds, timing.dbQueries);
    });
    return runWithRequestTiming(timing, () => emit.call(this, event, ...args));
  };
}
