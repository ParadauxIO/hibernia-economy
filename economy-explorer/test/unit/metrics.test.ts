import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { DummyDriver, Kysely, MysqlAdapter, MysqlIntrospector, MysqlQueryCompiler, sql, type LogEvent } from 'kysely';
import {
  cacheKeyPrefix,
  callerFromFrames,
  currentRequestTiming,
  isMetricsRequestAuthorised,
  kyselyMetrics,
  metrics,
  metricsText,
  newRequestTiming,
  recordCacheResult,
  recordQueryEvent,
  resetMetricsForTests,
  runWithRequestTiming,
  sqlLabel,
  timedProducer,
  withQueryLabel,
} from '@/lib/metrics';
import { GET } from '@/app/metrics/route';

interface Tables { widgets: { id: number; name: string } }

// A real Kysely instance over a driver that returns no rows: the plugin, the log
// hook and the V8 stack they inspect are all the production code paths.
const db = new Kysely<Tables>({
  dialect: {
    createAdapter: () => new MysqlAdapter(),
    createDriver: () => new DummyDriver(),
    createIntrospector: (k) => new MysqlIntrospector(k),
    createQueryCompiler: () => new MysqlQueryCompiler(),
  },
  ...kyselyMetrics(),
});

async function series(name: string): Promise<string[]> {
  const { body } = await metricsText();
  return body.split('\n').filter((l) => l.startsWith(name));
}

async function count(name: string, labels: string): Promise<number> {
  const line = (await series(name)).find((l) => l.startsWith(`${name}{${labels}}`));
  return line ? Number(line.split(' ').pop()) : 0;
}

beforeEach(() => {
  vi.stubEnv('TENANT', ''); // no default tenant label, so series lines match exactly
  resetMetricsForTests();
});
afterEach(() => vi.unstubAllEnvs());

describe('query labels', () => {
  async function listWidgets() {
    return db.selectFrom('widgets').selectAll().execute();
  }
  async function findWidget() {
    return db.selectFrom('widgets').selectAll().where('id', '=', 1).executeTakeFirst();
  }
  async function countWidgetsRaw() {
    return sql`SELECT COUNT(*) FROM widgets`.execute(db);
  }
  // What a minified production bundle leaves behind.
  async function d() {
    return db.selectFrom('widgets').select('id').execute();
  }

  it('labels a query with the DAL function that executed it', async () => {
    await listWidgets();
    await findWidget();
    await countWidgetsRaw();
    expect(await count('explorer_db_queries_total', 'fn="listWidgets",outcome="ok"')).toBe(1);
    expect(await count('explorer_db_queries_total', 'fn="findWidget",outcome="ok"')).toBe(1);
    expect(await count('explorer_db_queries_total', 'fn="countWidgetsRaw",outcome="ok"')).toBe(1);
    expect(await count('explorer_db_query_seconds_count', 'fn="listWidgets"')).toBe(1);
  });

  it('falls back to verb and table when the caller is anonymous or minified', async () => {
    await Promise.all([1].map(() => db.selectFrom('widgets').selectAll().execute()));
    await d();
    expect(await count('explorer_db_queries_total', 'fn="sql:select:widgets",outcome="ok"')).toBe(2);
  });

  it('lets an explicit label override the inferred one', async () => {
    await withQueryLabel('widgetReport', () => listWidgets());
    expect(await count('explorer_db_queries_total', 'fn="widgetReport",outcome="ok"')).toBe(1);
    expect(await count('explorer_db_queries_total', 'fn="listWidgets",outcome="ok"')).toBe(0);
  });

  it('picks the first named frame past Kysely, and gives up on library frames', () => {
    const kysely = [
      'currentFrameNames',
      'QueryLabelPlugin.transformQuery',
      'DefaultQueryExecutor.transformQuery',
      'SelectQueryBuilderImpl.toOperationNode',
      'SelectQueryBuilderImpl.execute',
      'SelectQueryBuilderImpl.executeTakeFirst',
    ];
    expect(callerFromFrames([...kysely, 'Object.findAccount', 'getAccountPage'])).toBe('findAccount');
    expect(callerFromFrames([...kysely, '<anonymous>', 'loadAccount'])).toBe('loadAccount');
    expect(callerFromFrames([...kysely, 'Array.map', 'loadAccount'])).toBeNull();
    expect(callerFromFrames(['foo', 'bar'])).toBeNull();
  });

  it('counts errors and MariaDB statement timeouts separately', async () => {
    const query = db.selectFrom('widgets').selectAll().compile();
    const event = (error: unknown): LogEvent => ({ level: 'error', error, query, queryDurationMillis: 15_000 });
    recordQueryEvent(event({ errno: 1969, message: 'Query execution was interrupted (max_statement_time exceeded)' }));
    recordQueryEvent(event(new Error('boom')));
    expect(await count('explorer_db_queries_total', 'fn="sql:select:widgets",outcome="timeout"')).toBe(1);
    expect(await count('explorer_db_queries_total', 'fn="sql:select:widgets",outcome="error"')).toBe(1);
  });

  it('adds query time to the current request only', async () => {
    const timing = newRequestTiming();
    await runWithRequestTiming(timing, async () => {
      await listWidgets();
      await Promise.all([findWidget(), countWidgetsRaw()]);
      expect(currentRequestTiming()).toBe(timing);
    });
    await listWidgets(); // outside any request
    expect(timing.dbQueries).toBe(3);
    expect(timing.dbMs).toBeGreaterThanOrEqual(0);
  });
});

describe('sqlLabel', () => {
  it.each([
    ['select `id` from `explorer_role` where `x` = ?', 'sql:select:explorer_role'],
    ['  WITH t AS (SELECT * FROM ledger_txns lt JOIN ledger_postings lp ON 1) SELECT * FROM t', 'sql:with:ledger_txns'],
    ['insert into `s80037_economy`.`explorer_audit` (a) values (?)', 'sql:insert:explorer_audit'],
    ['UPDATE explorer_identity SET x = 1', 'sql:update:explorer_identity'],
    ['/* note */ -- comment\nSELECT 1', 'sql:select'],
    ['begin', 'sql:begin'],
    ['', 'sql:unknown'],
  ])('%s → %s', (text, label) => expect(sqlLabel(text)).toBe(label));
});

describe('cache metrics', () => {
  it('derives a bounded key prefix from the memo key', () => {
    expect(cacheKeyPrefix('account-detail:123:1:50')).toBe('account-detail');
    expect(cacheKeyPrefix('firm-stats')).toBe('firm-stats');
    expect(cacheKeyPrefix('weird key/with spaces:1')).toBe('weird_key_with_spaces');
  });

  it('counts results and marks the request as a hit or a miss', async () => {
    const timing = newRequestTiming();
    runWithRequestTiming(timing, () => {
      recordCacheResult('market-window:v1:7', 'hit_l1');
      recordCacheResult('market-window:v1:30', 'stale');
      recordCacheResult('economy-overview:v1:30', 'miss');
    });
    expect(await count('explorer_cache_requests_total', 'key_prefix="market-window",result="hit_l1"')).toBe(1);
    expect(await count('explorer_cache_requests_total', 'key_prefix="market-window",result="stale"')).toBe(1);
    expect(timing).toMatchObject({ cacheHits: 2, cacheMisses: 1 });
  });

  it('times every compute, successful or not', async () => {
    await timedProducer('firm-stats', () => Promise.resolve(1))();
    await expect(timedProducer('firm-stats', () => Promise.reject(new Error('timeout')))()).rejects.toThrow('timeout');
    expect(await count('explorer_cache_refresh_seconds_count', 'key_prefix="firm-stats"')).toBe(2);
  });

  it('is wired into memo(): miss, L1 hit, stale serve and a failed refresh', async () => {
    vi.resetModules();
    const { memo, resetCacheForTests } = await import('@/lib/cache');
    resetCacheForTests();
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000_000);
    const producer = vi.fn(() => Promise.resolve('v'));
    await memo('d0-test:1', 60_000, producer);
    await memo('d0-test:1', 60_000, producer);
    expect(producer).toHaveBeenCalledTimes(1);
    expect(await count('explorer_cache_requests_total', 'key_prefix="d0-test",result="miss"')).toBe(1);
    expect(await count('explorer_cache_requests_total', 'key_prefix="d0-test",result="hit_l1"')).toBe(1);

    // Next window: the old value is served at once and the background refresh fails.
    now.mockReturnValue(1_000_000 + 60_000);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await expect(memo('d0-test:1', 60_000, () => Promise.reject(new Error('refresh fail')))).resolves.toBe('v');
    await vi.waitFor(async () =>
      expect(await count('explorer_cache_requests_total', 'key_prefix="d0-test",result="refresh_fail"')).toBe(1));
    expect(await count('explorer_cache_requests_total', 'key_prefix="d0-test",result="stale"')).toBe(1);
    expect(await count('explorer_cache_refresh_seconds_count', 'key_prefix="d0-test"')).toBe(2);
    warn.mockRestore();
    now.mockRestore();
  });

  it('marks the request as a cache miss when a cold compute runs inside it', async () => {
    vi.resetModules();
    const { memo, resetCacheForTests } = await import('@/lib/cache');
    resetCacheForTests();
    const timing = newRequestTiming();
    await runWithRequestTiming(timing, () => memo('d0-cold:1', 60_000, () => Promise.resolve(1)));
    expect(timing).toMatchObject({ cacheHits: 0, cacheMisses: 1 });
  });
});

describe('/metrics access', () => {
  it('needs a configured token and the matching bearer header', () => {
    expect(isMetricsRequestAuthorised('Bearer s3cret', undefined)).toBe(false);
    expect(isMetricsRequestAuthorised('Bearer s3cret', '  ')).toBe(false);
    expect(isMetricsRequestAuthorised(null, 's3cret')).toBe(false);
    expect(isMetricsRequestAuthorised('Bearer wrong', 's3cret')).toBe(false);
    expect(isMetricsRequestAuthorised('Basic s3cret', 's3cret')).toBe(false);
    expect(isMetricsRequestAuthorised('Bearer s3cret', 's3cret')).toBe(true);
    expect(isMetricsRequestAuthorised('bearer s3cret', 's3cret')).toBe(true);
  });

  it('is a 404 unless the token matches, then serves the Prometheus text format', async () => {
    const req = (auth?: string) => new Request('http://x/metrics', auth ? { headers: { authorization: auth } } : {});
    expect((await GET(req('Bearer anything'))).status).toBe(404);
    vi.stubEnv('METRICS_TOKEN', 's3cret');
    expect((await GET(req())).status).toBe(404);
    expect((await GET(req('Bearer nope'))).status).toBe(404);
    metrics().dbQueries.inc({ fn: 'probe', outcome: 'ok' });
    const ok = await GET(req('Bearer s3cret'));
    expect(ok.status).toBe(200);
    expect(ok.headers.get('content-type')).toContain('text/plain');
    expect(ok.headers.get('cache-control')).toBe('no-store');
    const body = await ok.text();
    expect(body).toContain('explorer_db_queries_total{fn="probe",outcome="ok"} 1');
    expect(body).toContain('process_cpu_seconds_total');
  });
});
