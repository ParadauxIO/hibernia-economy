import { createHash, timingSafeEqual } from 'node:crypto';
import { AsyncLocalStorage } from 'node:async_hooks';
import { Counter, Histogram, Registry, collectDefaultMetrics } from 'prom-client';
import type {
  KyselyPlugin,
  LogEvent,
  PluginTransformQueryArgs,
  PluginTransformResultArgs,
  QueryResult,
  RootOperationNode,
  UnknownRow,
} from 'kysely';

// Prometheus metrics for the explorer (PAR-531). Exposed at /metrics.
//
// Next.js can load one source module into several bundles (instrumentation,
// route handlers, server components), each with its own module instance. The
// registry, the metric objects and the label store therefore live on
// globalThis, so every copy records into the same series.

type Metrics = ReturnType<typeof createMetrics>;
const METRICS_KEY = Symbol.for('economy-explorer.metrics');
const LABEL_KEY = Symbol.for('economy-explorer.metrics.queryLabel');
const QUERY_LABELS_KEY = Symbol.for('economy-explorer.metrics.queryLabels');
const TIMING_KEY = Symbol.for('economy-explorer.metrics.requestTiming');
const store = globalThis as unknown as Record<symbol, unknown>;

const DB_BUCKETS = [0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 15];
const RENDER_BUCKETS = [0.01, 0.025, 0.05, 0.1, 0.25, 0.4, 0.6, 1, 1.5, 2, 3, 5, 10, 20];
const QUERY_COUNT_BUCKETS = [0, 1, 2, 3, 5, 8, 10, 15, 20, 30, 50, 100];

function createMetrics() {
  const registry = new Registry();
  const tenant = process.env.TENANT?.trim();
  if (tenant) registry.setDefaultLabels({ tenant });
  collectDefaultMetrics({ register: registry });
  return {
    registry,
    dbQuerySeconds: new Histogram({
      name: 'explorer_db_query_seconds',
      help: 'Time spent executing one database query, labelled by the DAL function that issued it.',
      labelNames: ['fn'] as const,
      buckets: DB_BUCKETS,
      registers: [registry],
    }),
    dbQueries: new Counter({
      name: 'explorer_db_queries_total',
      help: 'Database queries by DAL function and outcome (ok, error, timeout).',
      labelNames: ['fn', 'outcome'] as const,
      registers: [registry],
    }),
    cacheRequests: new Counter({
      name: 'explorer_cache_requests_total',
      help: 'memo() lookups by key prefix and result (hit_l1, hit_redis, stale, miss, refresh_fail).',
      labelNames: ['key_prefix', 'result'] as const,
      registers: [registry],
    }),
    cacheRefreshSeconds: new Histogram({
      name: 'explorer_cache_refresh_seconds',
      help: 'Time spent computing a cached value (cold miss or background refresh).',
      labelNames: ['key_prefix'] as const,
      buckets: RENDER_BUCKETS,
      registers: [registry],
    }),
    renderSeconds: new Histogram({
      name: 'explorer_render_seconds',
      help: 'Server time per request, from arrival to the last byte, by route pattern and request kind.',
      labelNames: ['route', 'kind'] as const,
      buckets: RENDER_BUCKETS,
      registers: [registry],
    }),
    renderQueries: new Histogram({
      name: 'explorer_render_db_queries',
      help: 'Database queries issued while serving one request, by route pattern and request kind.',
      labelNames: ['route', 'kind'] as const,
      buckets: QUERY_COUNT_BUCKETS,
      registers: [registry],
    }),
    requestErrors: new Counter({
      name: 'explorer_request_errors_total',
      help: 'Errors reported by Next.js onRequestError, by route pattern and route type.',
      labelNames: ['route', 'type'] as const,
      registers: [registry],
    }),
    auditWrites: new Counter({
      name: 'explorer_audit_writes_total',
      help: 'explorer_audit inserts by kind (view, mutation, relationship) and outcome (ok, error, throttled). The log is fail-open, so errors are only visible here.',
      labelNames: ['kind', 'outcome'] as const,
      registers: [registry],
    }),
  };
}

/** The process-wide metric set, created on first use. */
export function metrics(): Metrics {
  return (store[METRICS_KEY] ??= createMetrics()) as Metrics;
}

/** Test hook: drop every series so each test starts from zero. */
export function resetMetricsForTests(): void {
  delete store[METRICS_KEY];
}

/** Prometheus text exposition of every registered metric. */
export async function metricsText(): Promise<{ body: string; contentType: string }> {
  const { registry } = metrics();
  return { body: await registry.metrics(), contentType: registry.contentType };
}

// ── Per-request accumulation ─────────────────────────────────────────────────

/** What one HTTP request has spent so far; feeds Server-Timing and the render metrics. */
export interface RequestTiming {
  startMs: number;
  dbMs: number;
  dbQueries: number;
  cacheHits: number;
  cacheMisses: number;
}

function timingStore(): AsyncLocalStorage<RequestTiming> {
  return (store[TIMING_KEY] ??= new AsyncLocalStorage<RequestTiming>()) as AsyncLocalStorage<RequestTiming>;
}

export function newRequestTiming(startMs = performance.now()): RequestTiming {
  return { startMs, dbMs: 0, dbQueries: 0, cacheHits: 0, cacheMisses: 0 };
}

/** Run `fn` (and everything it awaits) with `timing` as the current request. */
export function runWithRequestTiming<T>(timing: RequestTiming, fn: () => T): T {
  return timingStore().run(timing, fn);
}

/** The current request's accumulator, or undefined outside a request (background work). */
export function currentRequestTiming(): RequestTiming | undefined {
  return timingStore().getStore();
}

// ── /metrics access ──────────────────────────────────────────────────────────

/**
 * True when the Authorization header carries `Bearer <METRICS_TOKEN>`. With no
 * token configured the endpoint is closed, never open. Digests are compared in
 * constant time so the token can't be recovered byte by byte.
 */
export function isMetricsRequestAuthorised(authorization: string | null, token: string | undefined): boolean {
  const expected = token?.trim();
  if (!expected || !authorization) return false;
  const match = /^Bearer\s+(.+)$/i.exec(authorization.trim());
  if (!match?.[1]) return false;
  const digest = (s: string) => createHash('sha256').update(s).digest();
  return timingSafeEqual(digest(match[1]), digest(expected));
}

// ── Labels ───────────────────────────────────────────────────────────────────

const MAX_LABEL = 64;

function cleanLabel(value: string): string {
  return value.replace(/[^A-Za-z0-9_:.-]/g, '_').slice(0, MAX_LABEL) || 'unknown';
}

/**
 * Cache key → `key_prefix` label: everything before the first `:`, so
 * `account-detail:123:1:50` → `account-detail`. Keys must start with a constant
 * name for this to stay low-cardinality; every memo() key in the app does.
 */
export function cacheKeyPrefix(key: string): string {
  return cleanLabel(key.split(':', 1)[0] ?? '');
}

/**
 * Fallback query label when the calling function can't be named: `sql:<verb>`
 * plus the first table read or written, e.g. `sql:select:ledger_postings`.
 */
export function sqlLabel(sqlText: string): string {
  const text = sqlText.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, ' ').trim();
  const verb = /^[(\s]*([A-Za-z]+)/.exec(text)?.[1]?.toLowerCase() ?? 'unknown';
  const table = /\b(?:from|into|update|join)\s+((?:`[^`]+`|\w+)(?:\.(?:`[^`]+`|\w+))?)/i.exec(text)?.[1];
  const name = table?.split('.').pop()?.replace(/`/g, '').toLowerCase();
  return cleanLabel(name ? `sql:${verb}:${name}` : `sql:${verb}`);
}

// Kysely methods between our plugin and the code that ran the query.
const KYSELY_ENTRY = new Set(['execute', 'executeTakeFirst', 'executeTakeFirstOrThrow', 'stream']);
const IDENTIFIER = /^[A-Za-z_$][\w$]*$/;

/**
 * Name of the function that called Kysely's `execute*()`, given the frame names
 * of a stack captured inside the plugin (Kysely calls it synchronously from
 * execute), innermost first, as `Type.method` or `fn`. Returns null when the
 * caller is anonymous or the name looks minified, so the SQL fallback is used
 * instead of a meaningless one-letter label.
 */
export function callerFromFrames(names: readonly string[]): string | null {
  const method = (name: string) => name.slice(name.lastIndexOf('.') + 1);
  let i = names.findIndex((n) => KYSELY_ENTRY.has(method(n)));
  if (i < 0) return null;
  while (i + 1 < names.length && KYSELY_ENTRY.has(method(names[i + 1]!))) i++;
  for (const raw of names.slice(i + 1, i + 6)) {
    const name = raw.replace(/^(?:Object|Module|exports)\./, '');
    if (IDENTIFIER.test(name) && name.length >= 3) return name;
    if (name.includes('.')) return null; // a library frame (Promise.all, Array.map, …)
  }
  return null;
}

/**
 * Frame names of the current call stack. Reads V8's structured call sites
 * instead of the formatted `stack` string: no string building and no
 * source-map lookups, which keeps this to a few microseconds per query.
 */
function currentFrameNames(): string[] {
  const { prepareStackTrace, stackTraceLimit } = Error;
  try {
    Error.stackTraceLimit = 16;
    Error.prepareStackTrace = (_err, sites) => sites;
    const holder: { stack?: unknown } = {};
    Error.captureStackTrace(holder);
    const sites = holder.stack as NodeJS.CallSite[];
    return sites.map((site) => {
      const fn = site.getFunctionName() ?? '<anonymous>';
      const type = site.isToplevel() ? null : site.getTypeName();
      return type && !fn.includes('.') ? `${type}.${fn}` : fn;
    });
  } finally {
    Error.prepareStackTrace = prepareStackTrace;
    Error.stackTraceLimit = stackTraceLimit;
  }
}

function labelOverrides(): AsyncLocalStorage<string> {
  return (store[LABEL_KEY] ??= new AsyncLocalStorage<string>()) as AsyncLocalStorage<string>;
}

function queryLabels(): WeakMap<object, string> {
  return (store[QUERY_LABELS_KEY] ??= new WeakMap<object, string>()) as WeakMap<object, string>;
}

/**
 * Label every query run inside `fn` as `label`. Only needed where the automatic
 * name is wrong or missing: queries run from an anonymous callback (a
 * transaction body, a shared helper) or a function whose name isn't the one
 * you want on the dashboard.
 */
export function withQueryLabel<T>(label: string, fn: () => T): T {
  return labelOverrides().run(cleanLabel(label), fn);
}

/**
 * Kysely plugin that records which function issued each query. It changes
 * nothing about the query; the label is read back by `recordQueryEvent`.
 */
export class QueryLabelPlugin implements KyselyPlugin {
  transformQuery({ node, queryId }: PluginTransformQueryArgs): RootOperationNode {
    const label = labelOverrides().getStore() ?? callerFromFrames(currentFrameNames());
    if (label) queryLabels().set(queryId, label);
    return node;
  }

  transformResult({ result }: PluginTransformResultArgs): Promise<QueryResult<UnknownRow>> {
    return Promise.resolve(result);
  }
}

// MariaDB ER_STATEMENT_TIMEOUT: the query hit max_statement_time (lib/db.ts).
const STATEMENT_TIMEOUT_ERRNO = 1969;

/** Kysely `log` hook: one histogram observation and one counter per query. */
export function recordQueryEvent(event: LogEvent): void {
  const fn = queryLabels().get(event.query.queryId) ?? sqlLabel(event.query.sql);
  let outcome = 'ok';
  if (event.level === 'error') {
    const errno = (event.error as { errno?: unknown } | null)?.errno;
    outcome = errno === STATEMENT_TIMEOUT_ERRNO ? 'timeout' : 'error';
  }
  recordQuery(fn, event.queryDurationMillis, outcome);
}

/** Record one query directly (for code that talks to the pool without Kysely). */
export function recordQuery(fn: string, durationMs: number, outcome: string): void {
  const m = metrics();
  const label = cleanLabel(fn);
  m.dbQuerySeconds.observe({ fn: label }, durationMs / 1000);
  m.dbQueries.inc({ fn: label, outcome });
  const timing = currentRequestTiming();
  if (timing) {
    timing.dbMs += durationMs;
    timing.dbQueries += 1;
  }
}

/**
 * Spread into any `new Kysely({...})` config so its queries are timed and
 * labelled like the request pool's: `new Kysely({ dialect, ...kyselyMetrics() })`.
 */
export function kyselyMetrics(): { plugins: KyselyPlugin[]; log: (event: LogEvent) => void } {
  return { plugins: [new QueryLabelPlugin()], log: recordQueryEvent };
}

// ── Cache ────────────────────────────────────────────────────────────────────

export type CacheResult = 'hit_l1' | 'hit_redis' | 'stale' | 'miss' | 'refresh_fail';

/** Count one memo() outcome and note it on the current request's Server-Timing. */
export function recordCacheResult(key: string, result: CacheResult): void {
  metrics().cacheRequests.inc({ key_prefix: cacheKeyPrefix(key), result });
  const timing = currentRequestTiming();
  if (!timing) return;
  if (result === 'miss') timing.cacheMisses += 1;
  else if (result !== 'refresh_fail') timing.cacheHits += 1;
}

/**
 * Wrap a cache producer so every compute (cold or background, successful or
 * not) is observed in `explorer_cache_refresh_seconds`.
 */
export function timedProducer<T>(key: string, producer: () => Promise<T>): () => Promise<T> {
  return async () => {
    const end = metrics().cacheRefreshSeconds.startTimer({ key_prefix: cacheKeyPrefix(key) });
    try {
      return await producer();
    } finally {
      end();
    }
  };
}

// ── Requests ─────────────────────────────────────────────────────────────────

export type RequestKind = 'document' | 'rsc' | 'prefetch';

/** Observe one finished request. */
export function observeRequest(route: string, kind: RequestKind, seconds: number, dbQueries: number): void {
  const m = metrics();
  m.renderSeconds.observe({ route, kind }, seconds);
  m.renderQueries.observe({ route, kind }, dbQueries);
}

export type AuditWriteKind = 'view' | 'mutation' | 'relationship';
export type AuditWriteOutcome = 'ok' | 'error' | 'throttled';

/** Count one audit write attempt (lib/audit.ts). */
export function recordAuditWrite(kind: AuditWriteKind, outcome: AuditWriteOutcome): void {
  metrics().auditWrites.inc({ kind, outcome });
}

/** Count one error reported through instrumentation's onRequestError. */
export function recordRequestError(route: string, type: string): void {
  metrics().requestErrors.inc({ route: route.slice(0, MAX_LABEL), type: cleanLabel(type) });
}
