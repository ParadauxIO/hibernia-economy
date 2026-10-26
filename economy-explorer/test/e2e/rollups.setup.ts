import Module from 'node:module';
import { resolve } from 'node:path';
import { test as setup } from '@playwright/test';
import { Kysely, MysqlDialect } from 'kysely';
import { createPool } from 'mysql2';
import { testDbOptions } from '../integration/db';

// Runs after fresh-deploy.spec.ts (the "fresh-deploy" project) and before every
// other project: builds the rollups for the seeded ledger, as the in-process
// worker would after its first tick and a complete backfill. The e2e server
// runs with the worker off (lib/rollup/start.ts), so without this every
// economy page would stay in its "Preparing history" state.

// lib/rollup/steps.ts imports 'server-only', which throws outside an RSC
// bundle. Point it at the same no-op stub Vitest uses.
const stub = resolve(process.cwd(), 'test/stubs/server-only.ts');
const loader = Module as unknown as { _resolveFilename: (request: string, ...rest: unknown[]) => string };
const resolveFilename = loader._resolveFilename;
loader._resolveFilename = function (request: string, ...rest: unknown[]) {
  return resolveFilename.call(this, request === 'server-only' ? stub : request, ...rest);
};

setup('build the rollups for the seeded ledger', async () => {
  setup.skip(!process.env.E2E_AUTH_SHIM, 'only the managed e2e database is seeded here');
  const { ROLLUP_JOBS, markHistoryComplete, recomputeDay, snapshotWealth } = await import('../../lib/rollup/steps');
  const { addDays, utcDay } = await import('../../lib/rollup/days');
  // The worker's session settings (lib/rollup/db.ts): UTC days.
  const pool = createPool({ ...testDbOptions(), timezone: 'Z', dateStrings: true, connectionLimit: 1 });
  pool.on('connection', (conn) => { conn.query("SET SESSION time_zone = '+00:00'", () => {}); });
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const db = new Kysely<any>({ dialect: new MysqlDialect({ pool: pool as never }) });
  try {
    const today = utcDay();
    for (const day of [addDays(today, -1), today]) for (const job of ROLLUP_JOBS) await recomputeDay(db, job, day);
    // seed.sql settles everything today, so history is complete from here.
    for (const job of ROLLUP_JOBS) await markHistoryComplete(db, job);
    await snapshotWealth(db, today);
  } finally {
    await db.destroy();
  }
});
