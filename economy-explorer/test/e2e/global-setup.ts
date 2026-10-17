import { resetDb } from '../integration/db';

// Managed e2e runs (see playwright.config.ts) start from the same deterministic
// schema + seed as the SQL integration suite. resetDb DROPS every table in
// DB_NAME first, so refuse anything that doesn't look like a throwaway database.
export default async function globalSetup(): Promise<void> {
  const name = process.env.DB_NAME ?? '';
  if (!process.env.DB_HOST || !/test/i.test(name)) {
    throw new Error(
      `e2e needs a disposable database: set DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD and a DB_NAME ` +
        `containing "test" (got "${name}"). Its tables are dropped and reseeded.`,
    );
  }
  await resetDb();
}
