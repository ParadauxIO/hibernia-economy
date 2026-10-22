import mysql from 'mysql2/promise';
import { migrationSql } from './migrationSql';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { withoutUndefined } from '../../lib/util/withoutUndefined';

// Integration tests run only when RUN_INTEGRATION=1 and DB_* point at a
// disposable MariaDB (a CI service container, or a local mariadbd). They exercise
// the REAL lib/sql queries against a REAL database — no query mocking.
//
// The schema is the AUTHORITATIVE one: every economy-flyway migration, applied in
// version order — the same files Flyway runs in production, including the balance
// triggers (trg_postings_*), stored functions, generated columns and views. No JVM
// is needed: client-side DELIMITER directives are translated before MariaDB
// parses stored-program bodies itself in each multi-statement query.
export const HAS_DB = process.env.RUN_INTEGRATION === '1';

// Resolved from the package root (vitest and Playwright both run there) rather than
// import.meta.url, which Playwright's CommonJS transpile of this file can't use.
const file = (p: string) => resolve(process.cwd(), 'test/integration', p);
const MIGRATIONS = resolve(process.cwd(), '../economy-flyway/src/main/resources/db/migration');

/** The `V<n>__*.sql` migrations in Flyway's numeric (not lexical) version order. */
export function migrationFiles(dir: string = MIGRATIONS): string[] {
  const version = (f: string) => Number(/^V(\d+)__/.exec(f)?.[1]);
  const files = readdirSync(dir).filter((f) => /^V\d+__.+\.sql$/.test(f));
  if (files.length === 0) throw new Error(`no Flyway migrations found in ${dir}`);
  return files.sort((a, b) => version(a) - version(b)).map((f) => resolve(dir, f));
}

/** Connection options for the disposable test DB named by DB_*. */
export function testDbOptions(): mysql.ConnectionOptions {
  return {
    ...withoutUndefined({
      host: process.env.DB_HOST,
      user: process.env.DB_USERNAME,
      password: process.env.DB_PASSWORD,
      database: process.env.DB_NAME,
    }),
    port: Number(process.env.DB_PORT ?? 3306),
  };
}

/** Drop everything, apply the Flyway migrations, and load the deterministic seed. */
export async function resetDb(): Promise<void> {
  const conn = await mysql.createConnection({
    ...testDbOptions(),
    multipleStatements: true,
    // mysql2 asks for IGNORE_SPACE by default; Flyway's JDBC driver doesn't, and
    // the triggers/functions record the sql_mode they were created under.
    flags: ['-IGNORE_SPACE'],
  });
  try {
    // Match CI even on servers whose default is unicode_ci. Migrations must set
    // compatible table collations explicitly (PAR-101 source joins depend on it).
    await conn.query('ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci');
    await conn.query('SET FOREIGN_KEY_CHECKS=0');
    // Drop views first — DROP TABLE can't remove them (V22 added the
    // account_read_access_* views).
    const [views] = await conn.query<mysql.RowDataPacket[]>(
      'SELECT table_name AS n FROM information_schema.views WHERE table_schema = DATABASE()');
    for (const v of views) await conn.query('DROP VIEW IF EXISTS `' + String(v.n) + '`');
    const [tables] = await conn.query<mysql.RowDataPacket[]>(
      "SELECT table_name AS n FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'");
    for (const t of tables) await conn.query('DROP TABLE IF EXISTS `' + String(t.n) + '`');
    // V1's uuid helper functions; the triggers went with their tables.
    const [routines] = await conn.query<mysql.RowDataPacket[]>(
      'SELECT routine_name AS n, routine_type AS t FROM information_schema.routines WHERE routine_schema = DATABASE()');
    for (const r of routines) {
      await conn.query(`DROP ${r.t === 'PROCEDURE' ? 'PROCEDURE' : 'FUNCTION'} IF EXISTS \`${String(r.n)}\``);
    }
    await conn.query('SET FOREIGN_KEY_CHECKS=1');
    for (const migration of migrationFiles()) {
      try {
        await conn.query(migrationSql(readFileSync(migration, 'utf8')));
      } catch (e) {
        throw new Error(`applying ${migration}: ${(e as Error).message}`, { cause: e });
      }
    }
    await conn.query(readFileSync(file('./seed.sql'), 'utf8'));
  } finally {
    await conn.end();
  }
}

/** Canonical hyphenated UUID from the 32-hex used in seed.sql (so they match). */
export function uuid(hex32: string): string {
  const h = hex32.toLowerCase();
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export const ALICE = uuid('0000000000000000000000000000A1CE');
export const BOB = uuid('00000000000000000000000000000B0B');
export const CAROL = uuid('0000000000000000000000000000CA01');
export const DAVE = uuid('0000000000000000000000000000DA7E');
// A Bedrock/Floodgate player: Floodgate UUIDs share this all-zero high-8-bytes
// shape (the others above are coincidentally shaped the same); what's distinct is
// the '.'-prefixed name and a completed explorer_identity link (see seed.sql).
export const BEDROCK = uuid('0000000000000000000000000000BED0');
// A government department "secretary": a read-only viewer of gov account #5
// (City Hall) — not its owner and not a member (PAR-237).
export const SECRETARY = uuid('00000000000000000000000000005EC0');
// "Penny": the drift fixture (behaviour/0001). Two personal accounts (#9, #10)
// whose balances (0.10 + 0.20) and windowed credits (0.10 + 0.20 + 0.30) sum
// exactly in DECIMAL but NOT in a JS double — proves getPlayerTotals sums in SQL.
export const PENNY = uuid('0000000000000000000000000000BE00');
