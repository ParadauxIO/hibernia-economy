import { describe, it, expect, beforeAll, afterAll, beforeEach, vi } from 'vitest';
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { metricsText, newRequestTiming, recordCacheResult, recordQuery, resetMetricsForTests } from '@/lib/metrics';
import { buildServerTiming, installRequestTiming, requestKind, routeMatcher } from '@/lib/requestTiming';

describe('buildServerTiming', () => {
  it('reports total, DB time with the query count, and the cache verdict', () => {
    const t = { ...newRequestTiming(100), dbMs: 12.345, dbQueries: 3, cacheHits: 2, cacheMisses: 0 };
    expect(buildServerTiming(t, 150.04)).toBe('total;dur=50.0, db;dur=12.3;desc="3 queries", cache;desc="hit"');
  });

  it('calls any miss a miss, uses the singular, and omits cache when memo() was not used', () => {
    expect(buildServerTiming({ ...newRequestTiming(0), dbMs: 4, dbQueries: 1, cacheHits: 5, cacheMisses: 1 }, 10))
      .toBe('total;dur=10.0, db;dur=4.0;desc="1 query", cache;desc="miss"');
    expect(buildServerTiming(newRequestTiming(0), 2)).toBe('total;dur=2.0, db;dur=0.0;desc="0 queries"');
  });
});

describe('routeMatcher', () => {
  // The shape `next build` writes to .next/routes-manifest.json.
  const match = routeMatcher({
    staticRoutes: [
      { page: '/', regex: '^/(?:/)?$' },
      { page: '/accounts', regex: '^/accounts(?:/)?$' },
    ],
    dynamicRoutes: [
      { page: '/accounts/[accountId]', regex: '^/accounts/([^/]+?)(?:/)?$' },
      { page: '/docs/[[...slug]]', regex: '^/docs(?:/(.+?))?(?:/)?$' },
    ],
  });

  it('maps paths to bounded route patterns', () => {
    expect(match('/')).toBe('/');
    expect(match('/accounts')).toBe('/accounts');
    expect(match('/accounts/')).toBe('/accounts');
    expect(match('/accounts/4711')).toBe('/accounts/[accountId]');
    expect(match('/docs')).toBe('/docs/[[...slug]]');
    expect(match('/docs/getting-started/accounts')).toBe('/docs/[[...slug]]');
    expect(match('/players/SomeoneNew')).toBe('other');
  });
});

describe('requestKind', () => {
  it('separates full page loads from client navigations and prefetches', () => {
    expect(requestKind({})).toBe('document');
    expect(requestKind({ rsc: '1' })).toBe('rsc');
    expect(requestKind({ rsc: '1', 'next-router-prefetch': '1' })).toBe('prefetch');
  });
});

describe('installRequestTiming', () => {
  let server: http.Server;
  let base: string;

  beforeAll(async () => {
    installRequestTiming();
    installRequestTiming(); // idempotent
    server = http.createServer((req, res) => {
      // Work done before the head is written shows in the header…
      recordQuery('beforeHead', 5, 'ok');
      recordCacheResult('d0-route:1', req.url === '/zz-miss' ? 'miss' : 'hit_l1');
      res.setHeader('content-type', 'text/plain');
      res.write('shell');
      // …work streamed after it only reaches the metrics.
      setTimeout(() => {
        recordQuery('afterHead', 5, 'ok');
        res.end('done');
      }, 5);
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())));
  beforeEach(() => {
    vi.stubEnv('TENANT', '');
    resetMetricsForTests();
  });

  async function waitFor(name: string): Promise<string> {
    for (let i = 0; i < 50; i++) {
      const line = (await metricsText()).body.split('\n').find((l) => l.startsWith(name));
      if (line) return line;
      await new Promise((r) => setTimeout(r, 10));
    }
    throw new Error(`${name} never observed`);
  }

  it('adds Server-Timing for work before the first byte', async () => {
    const res = await fetch(`${base}/zz-hit`);
    expect(await res.text()).toBe('shelldone');
    expect(res.headers.get('server-timing')).toMatch(/^total;dur=[\d.]+, db;dur=5\.0;desc="1 query", cache;desc="hit"$/);
    expect((await fetch(`${base}/zz-miss`)).headers.get('server-timing')).toContain('cache;desc="miss"');
  });

  it('observes the whole request, streamed queries included, under a bounded route label', async () => {
    await (await fetch(`${base}/zz-hit`, { headers: { rsc: '1' } })).text();
    expect(await waitFor('explorer_render_seconds_count{route="other",kind="rsc"}')).toMatch(/ 1$/);
    expect(await waitFor('explorer_render_db_queries_sum{route="other",kind="rsc"}')).toMatch(/ 2$/);
  });

  it('leaves scrapes, probes and static assets untimed', async () => {
    for (const path of ['/metrics', '/healthz', '/_next/static/x.js']) {
      const res = await fetch(`${base}${path}`);
      await res.text();
      expect(res.headers.get('server-timing')).toBeNull();
    }
    await new Promise((r) => setTimeout(r, 30));
    expect((await metricsText()).body).not.toContain('explorer_render_seconds_count{');
  });
});
