import { mkdir, writeFile } from 'node:fs/promises';
import { encode } from 'next-auth/jwt';
import { resetDb } from '../integration/db';
import { seedGovFixtures } from './gov-fixtures';

// Managed e2e runs (see playwright.config.ts) start from the same deterministic
// schema + seed as the SQL integration suite, plus the government workspace
// fixtures (gov-fixtures.ts). resetDb DROPS every table in DB_NAME first, so
// refuse anything that doesn't look like a throwaway database.
export default async function globalSetup(): Promise<void> {
  const name = process.env.DB_NAME ?? '';
  if (!process.env.DB_HOST || !/test/i.test(name)) {
    throw new Error(
      `e2e needs a disposable database: set DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD and a DB_NAME ` +
        `containing "test" (got "${name}"). Its tables are dropped and reseeded.`,
    );
  }
  await resetDb();
  await seedGovFixtures();
  // Ordinary linked visitor with no account ownership or elevated role. Content
  // suites exercise the authenticated product; boundary specs explicitly clear it.
  const base = new URL(process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3100');
  const cookie = 'authjs.session-token';
  const value = await encode({ secret: process.env.E2E_AUTH_SECRET!, salt: cookie, token: {
    sub: 'e2e-linked-visitor', minecraftUuid: '00000000-0000-0000-0000-00000000f001', minecraftName: 'Visitor',
  } });
  await mkdir('.context', { recursive: true });
  await writeFile('.context/linked-visitor.json', JSON.stringify({ cookies: [{ name: cookie, value, domain: base.hostname, path: '/', expires: -1, httpOnly: true, secure: false, sameSite: 'Lax' }], origins: [] }));
}
