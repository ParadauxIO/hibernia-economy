import { beforeAll, afterAll, beforeEach, describe, expect, it } from 'vitest';
import mysql from 'mysql2/promise';
import { sql } from 'kysely';
import { createHash } from 'node:crypto';
import { HAS_DB, resetDb, testDbOptions, BEDROCK } from './db';
import { db } from '@/lib/db';
import { startRecovery, recoveryStatus, claimRecovery } from '@/lib/sql/recovery';
const hash = (s: string) => createHash('sha256').update(s).digest('hex');
const uuid = Buffer.from(BEDROCK.replaceAll('-', ''), 'hex');
const suite = HAS_DB ? describe : describe.skip;
suite('browser recovery against migrated MariaDB', () => {
  beforeAll(async () => {
    if (!/test/i.test(process.env.DB_NAME ?? '')) throw new Error('Requires disposable test database');
    const options = testDbOptions(); delete options.database;
    const connection = await mysql.createConnection(options);
    await connection.query(`CREATE DATABASE IF NOT EXISTS \`${process.env.DB_NAME}\``);
    await connection.end(); await resetDb();
  }, 60_000);
  beforeEach(async () => {
    await sql`DELETE FROM explorer_password_recovery`.execute(db);
    await sql`DELETE FROM explorer_identity WHERE player_uuid_bin = ${uuid}`.execute(db);
    await sql`INSERT INTO explorer_identity (keycloak_sub, player_uuid_bin, minecraft_name) VALUES ('recovery-sub', ${uuid}, '.bedrock')`.execute(db);
  });
  afterAll(async () => { await db.destroy(); });
  async function approve(browser: string, code: string) {
    await startRecovery(hash(browser), hash(code));
    // The Java mapper contract: confirm from authenticated sender, hash code, assign its existing subject.
    await sql`UPDATE explorer_password_recovery SET keycloak_sub = 'recovery-sub', player_uuid_bin = ${uuid}
      WHERE code_hash = ${hash(code)} AND keycloak_sub IS NULL AND expires_at > NOW()`.execute(db);
  }
  it('has a disabled release flag after migration', async () => {
    const { rows } = await sql<{ enabled: number }>`SELECT enabled FROM feature_flags WHERE name = 'explorer.browser_recovery'`.execute(db);
    expect(rows).toEqual([{ enabled: 0 }]);
  });
  it('requires approval and the originating browser, then consumes exactly once under concurrency', async () => {
    await startRecovery(hash('browser'), hash('code'));
    expect(await recoveryStatus(hash('browser'))).toBe('pending');
    expect(await claimRecovery(hash('browser'))).toBeNull();
    await sql`UPDATE explorer_password_recovery SET keycloak_sub = 'recovery-sub', player_uuid_bin = ${uuid} WHERE browser_hash = ${hash('browser')}`.execute(db);
    expect(await recoveryStatus(hash('browser'))).toBe('approved');
    expect(await claimRecovery(hash('code'))).toBeNull();
    expect(await claimRecovery(hash('another-browser'))).toBeNull();
    const claims = await Promise.all(Array.from({ length: 8 }, () => claimRecovery(hash('browser'))));
    expect(claims.filter(Boolean)).toEqual(['recovery-sub']);
    expect(await recoveryStatus(hash('browser'))).toBe('expired');
  });
  it('rejects expired approvals', async () => {
    await approve('browser', 'code');
    await sql`UPDATE explorer_password_recovery SET expires_at = NOW() - INTERVAL 1 SECOND`.execute(db);
    expect(await claimRecovery(hash('browser'))).toBeNull();
    expect(await recoveryStatus(hash('browser'))).toBe('expired');
  });
  it.each(['removed', 'replaced', 'ambiguous'])('consumes and rejects an approval when the identity link becomes %s', async change => {
    await approve('browser', 'code');
    if (change !== 'ambiguous') await sql`DELETE FROM explorer_identity WHERE player_uuid_bin = ${uuid}`.execute(db);
    if (change !== 'removed') await sql`INSERT INTO explorer_identity (keycloak_sub, player_uuid_bin) VALUES ('different-sub', ${uuid})`.execute(db);
    expect(await claimRecovery(hash('browser'))).toBeNull();
    expect(await recoveryStatus(hash('browser'))).toBe('expired');
  });
  it('starting over invalidates only that browser request and cleans expired entries', async () => {
    await startRecovery(hash('old'), hash('old-code'));
    await startRecovery(hash('other'), hash('other-code'));
    await startRecovery(hash('new'), hash('new-code'), hash('old'));
    expect(await recoveryStatus(hash('old'))).toBe('expired');
    expect(await recoveryStatus(hash('other'))).toBe('pending');
    expect(await recoveryStatus(hash('new'))).toBe('pending');
  });
});
