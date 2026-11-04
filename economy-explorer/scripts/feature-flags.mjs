#!/usr/bin/env node
import { pathToFileURL } from 'node:url';

export function parseArgs(args) {
  if (args.length === 1 && args[0] === 'list') return { command: 'list' };
  const [command, name, value, actor] = args;
  if (args.length !== 4 || command !== 'set' || !name || name.length > 128
      || name !== name.trim() || !/^[a-z][a-z0-9_]*\.[a-z][a-z0-9_.]*$/.test(name)
      || !['on', 'off'].includes(value) || !actor?.trim() || actor.length > 255) {
    throw new Error('Usage: feature-flags.mjs list | set <namespace.key> <on|off> <actor-or-migration-ref>');
  }
  return { command, name, enabled: value === 'on', actor };
}

export async function run(connection, options) {
  if (options.command === 'list') {
    const [rows] = await connection.execute('SELECT name, description, enabled, revision, updated_at, updated_by FROM feature_flags ORDER BY name');
    return rows;
  }
  await connection.beginTransaction();
  try {
    const [rows] = await connection.execute('SELECT name, enabled, revision FROM feature_flags WHERE name = ? FOR UPDATE', [options.name]);
    if (rows.length !== 1) throw new Error('Unknown feature flag. Register it with a migration first.');
    await connection.execute('UPDATE feature_flags SET enabled = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3), updated_by = ? WHERE name = ?',
      [options.enabled ? 1 : 0, options.actor, options.name]);
    const [after] = await connection.execute('SELECT name, enabled, revision, updated_at, updated_by FROM feature_flags WHERE name = ?', [options.name]);
    await connection.commit();
    return after;
  } catch (error) {
    await connection.rollback();
    throw error;
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (!process.env.DB_HOST || !process.env.DB_NAME || !process.env.DB_USERNAME) throw new Error('DB_HOST, DB_NAME and DB_USERNAME are required.');
  const { createConnection } = await import('mysql2/promise');
  console.log(`Database: ${process.env.DB_HOST}:${process.env.DB_PORT ?? 3306}/${process.env.DB_NAME}`);
  const connection = await createConnection({ host: process.env.DB_HOST, port: Number(process.env.DB_PORT ?? 3306),
    database: process.env.DB_NAME, user: process.env.DB_USERNAME, password: process.env.DB_PASSWORD, connectTimeout: 5_000 });
  try {
    await connection.query('SET SESSION max_statement_time = 3');
    await connection.query('SET SESSION innodb_lock_wait_timeout = 3');
    console.log(JSON.stringify(await run(connection, options), null, 2));
  } finally { await connection.end(); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => {
    // Driver error messages can include connection details. Never print credentials.
    console.error(error.code ? `Feature flag operation failed (${error.code}).` : error.message);
    process.exitCode = 1;
  });
}
