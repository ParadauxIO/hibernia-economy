import mysql from 'mysql2/promise';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Integration tests run only when RUN_INTEGRATION=1 and DB_* point at a
// disposable MariaDB (a CI service container, or a local docker). They exercise
// the REAL lib/sql queries against a REAL database — no query mocking.
//
// The schema loaded here (./schema.sql) is a DELIBERATE LIGHT stand-in, not the
// authoritative schema (that's economy-flyway/). See the header of schema.sql for
// the full rationale: in-repo tests stay light; the full authoritative-schema +
// trigger harness lives outside the monorepo in ../other (economy-explorer/testing/0005).
export const HAS_DB = process.env.RUN_INTEGRATION === '1';

const file = (p: string) => fileURLToPath(new URL(p, import.meta.url));

/** Drop everything, recreate the schema, and load the deterministic seed. */
export async function resetDb(): Promise<void> {
  const conn = await mysql.createConnection({
    host: process.env.DB_HOST,
    port: Number(process.env.DB_PORT ?? 3306),
    user: process.env.DB_USERNAME,
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
    multipleStatements: true,
  });
  try {
    await conn.query('SET FOREIGN_KEY_CHECKS=0');
    // Drop views first — DROP TABLE can't remove them (ADT-13 added the
    // account_read_access_* views), and they'd otherwise show up in SHOW TABLES.
    const [views] = await conn.query<mysql.RowDataPacket[]>(
      'SELECT table_name AS n FROM information_schema.views WHERE table_schema = DATABASE()');
    for (const v of views) {
      await conn.query('DROP VIEW IF EXISTS `' + (v as Record<string, string>).n + '`');
    }
    const [tables] = await conn.query<mysql.RowDataPacket[]>('SHOW TABLES');
    const col = tables.length ? Object.keys(tables[0])[0] : null;
    for (const row of tables) {
      if (col) await conn.query('DROP TABLE IF EXISTS `' + (row as Record<string, string>)[col] + '`');
    }
    await conn.query('SET FOREIGN_KEY_CHECKS=1');
    await conn.query(readFileSync(file('./schema.sql'), 'utf8'));
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
