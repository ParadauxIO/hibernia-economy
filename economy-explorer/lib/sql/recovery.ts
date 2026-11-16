import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';

export async function startRecovery(browserHash: string, codeHash: string, previousHash?: string) {
  await db.transaction().execute(async tx => {
    await sql`DELETE FROM explorer_password_recovery WHERE expires_at <= NOW()`.execute(tx);
    if (previousHash) await sql`DELETE FROM explorer_password_recovery WHERE browser_hash = ${previousHash}`.execute(tx);
    await sql`INSERT INTO explorer_password_recovery (browser_hash, code_hash, expires_at)
      VALUES (${browserHash}, ${codeHash}, NOW() + INTERVAL 5 MINUTE)`.execute(tx);
  });
}

export async function recoveryStatus(browserHash: string): Promise<'pending' | 'approved' | 'expired'> {
  const { rows } = await sql<{ keycloak_sub: string | null }>`SELECT keycloak_sub FROM explorer_password_recovery
    WHERE browser_hash = ${browserHash} AND expires_at > NOW()`.execute(db);
  return !rows[0] ? 'expired' : rows[0].keycloak_sub ? 'approved' : 'pending';
}

/** Consume before contacting Keycloak. Even an ambiguous network failure requires a new approval. */
export async function claimRecovery(browserHash: string): Promise<string | null> {
  return db.transaction().execute(async tx => {
    const { rows } = await sql<{ keycloak_sub: string; player_uuid_bin: Buffer }>`
      SELECT keycloak_sub, player_uuid_bin FROM explorer_password_recovery
      WHERE browser_hash = ${browserHash} AND expires_at > NOW() AND keycloak_sub IS NOT NULL
      FOR UPDATE`.execute(tx);
    const request = rows[0];
    if (!request) return null;
    // Recheck the current account link, including ambiguity, at completion.
    const identities = await sql<{ keycloak_sub: string }>`SELECT keycloak_sub FROM explorer_identity
      WHERE player_uuid_bin = ${request.player_uuid_bin} FOR UPDATE`.execute(tx);
    await sql`DELETE FROM explorer_password_recovery WHERE browser_hash = ${browserHash}`.execute(tx);
    return identities.rows.length === 1 && identities.rows[0]?.keycloak_sub === request.keycloak_sub
      ? request.keycloak_sub : null;
  });
}
