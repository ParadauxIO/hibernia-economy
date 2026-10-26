import { describe, it, expect, beforeEach } from 'vitest';
import { sql } from 'kysely';
import { db } from '@/lib/db';
import { metricsText, newRequestTiming, resetMetricsForTests, runWithRequestTiming } from '@/lib/metrics';
import { HAS_DB } from './db';

// The request pool's instrumentation against a real MariaDB and mysql2 driver.
// Schema-independent on purpose, so it can run alongside sql.test.ts's resets.
const d = HAS_DB ? describe : describe.skip;

async function counter(labels: string): Promise<number> {
  const line = (await metricsText()).body.split('\n').find((l) => l.startsWith(`explorer_db_queries_total{${labels}`));
  return line ? Number(line.split(' ').pop()) : 0;
}

async function pingDatabase() {
  return sql<{ one: number }>`SELECT 1 AS one`.execute(db);
}

async function runawayQuery() {
  // A cross join big enough to outlive a 50 ms statement limit on any server.
  return sql`SET STATEMENT max_statement_time = 0.05 FOR
    SELECT COUNT(*) FROM information_schema.columns a, information_schema.columns b, information_schema.columns c`.execute(db);
}

d('query metrics on the request pool', () => {
  beforeEach(() => resetMetricsForTests());

  it('labels real queries by DAL function and times them', async () => {
    await pingDatabase();
    expect(await counter('fn="pingDatabase",outcome="ok"')).toBe(1);
  });

  it('counts a query killed by max_statement_time as a timeout', async () => {
    await expect(runawayQuery()).rejects.toThrow();
    expect(await counter('fn="runawayQuery",outcome="timeout"')).toBe(1);
  });

  it('keeps concurrent requests apart across pooled connections', async () => {
    const a = newRequestTiming();
    const b = newRequestTiming();
    await Promise.all([
      runWithRequestTiming(a, async () => {
        await pingDatabase();
        await Promise.all([pingDatabase(), pingDatabase()]);
      }),
      runWithRequestTiming(b, () => pingDatabase()),
    ]);
    expect([a.dbQueries, b.dbQueries]).toEqual([3, 1]);
    expect(a.dbMs).toBeGreaterThan(0);
  });
});
