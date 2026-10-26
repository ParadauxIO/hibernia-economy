import 'server-only';
import { Kysely, MysqlDialect } from 'kysely';
import { createPool, type Pool } from 'mysql2';
import { kyselyMetrics } from '@/lib/metrics';
import { withoutUndefined } from '@/lib/util/withoutUndefined';

// The rollup worker's own pool, separate from lib/db.ts so a long recompute can
// never take a connection from request traffic. Two connections: one for the
// running step and one spare for the run log.
//
// Every session:
//   * time_zone '+00:00' — rollup days are UTC days whatever the server default;
//   * READ COMMITTED — INSERT ... SELECT then reads the ledger as a consistent
//     snapshot instead of taking shared next-key locks that would block ledger
//     writers (V36 explains why the ledger write path must not wait on us);
//   * a longer max_statement_time (seconds) than request queries, because one
//     day's recompute reads the whole day.

/** Statement timeout for rollup sessions, in seconds. */
export const ROLLUP_STMT_TIMEOUT_S = Number(process.env.ROLLUP_DB_STMT_TIMEOUT_S ?? 300);

export const ROLLUP_SESSION_SQL = [
  "SET SESSION time_zone = '+00:00'",
  'SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED',
  `SET SESSION max_statement_time = ${ROLLUP_STMT_TIMEOUT_S}`,
];

// The row-shape registry is unused: every rollup statement is a raw sql``.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type RollupDb = Kysely<any>;

let instance: { db: RollupDb; pool: Pool } | null = null;

export function rollupDb(): RollupDb {
  if (instance) return instance.db;
  const pool = createPool({
    ...withoutUndefined({
      host: process.env.DB_HOST,
      database: process.env.DB_NAME,
      user: process.env.DB_USERNAME,
      password: process.env.DB_PASSWORD,
    }),
    port: Number(process.env.DB_PORT ?? 3306),
    connectionLimit: 2,
    timezone: 'Z',
    dateStrings: true,
    supportBigNumbers: true,
    bigNumberStrings: false,
    decimalNumbers: false,
  });
  pool.on('connection', (conn) => {
    for (const statement of ROLLUP_SESSION_SQL) conn.query(statement, () => {});
  });
  // mysql2's pool is structurally compatible with kysely's MysqlPool at runtime.
  // kyselyMetrics() times and labels rollup queries like the request pool's.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  instance = { db: new Kysely({ dialect: new MysqlDialect({ pool: pool as any }), ...kyselyMetrics() }), pool };
  return instance.db;
}

export async function closeRollupDb(): Promise<void> {
  const current = instance;
  instance = null;
  if (current) await current.db.destroy();
}
