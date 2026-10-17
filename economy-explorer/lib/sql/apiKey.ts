import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid } from '@/lib/db';

export interface AdminApiKey {
  key_id: number;
  key_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
  account_id: number | null;
  account_name: string | null;
  firm_id: number | null;
  firm_name: string | null;
  revoked: number;
  issued_at: Date | null;
  expires_at: Date | null;
}

/** Mirrors ApiKeyMapper.listForAdmin (line 35-44). */
export async function listAdminApiKeys(): Promise<AdminApiKey[]> {
  const r = await sql<{
    key_id: number;
    key_type: string;
    owner_uuid_bin: Buffer | null;
    owner_name: string | null;
    account_id: number | null;
    account_name: string | null;
    firm_id: number | null;
    firm_name: string | null;
    revoked: number;
    issued_at: Date | null;
    expires_at: Date | null;
  }>`
    SELECT k.key_id, k.key_type, k.owner_uuid_bin, fp.current_name AS owner_name,
           k.account_id, a.display_name AS account_name,
           k.firm_id, f.display_name AS firm_name,
           k.revoked, k.issued_at, k.expires_at
    FROM api_keys k
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = k.owner_uuid_bin
    LEFT JOIN accounts a ON a.account_id = k.account_id
    LEFT JOIN firm f ON f.firm_id = k.firm_id
    ORDER BY k.revoked ASC, k.issued_at DESC
  `.execute(db);
  return r.rows.map((row) => ({
    key_id: row.key_id,
    key_type: row.key_type,
    owner_uuid: row.owner_uuid_bin ? binToUuid(row.owner_uuid_bin) : null,
    owner_name: row.owner_name,
    account_id: row.account_id,
    account_name: row.account_name,
    firm_id: row.firm_id,
    firm_name: row.firm_name,
    revoked: row.revoked,
    issued_at: row.issued_at,
    expires_at: row.expires_at,
  }));
}

// Writes to api_keys (revoke, rotate) go through the REST admin API (ADT-14):
// lib/treasury.ts revokeApiKey / rotateApiKey. kysely is read-only here. Routing
// rotate through the REST side also means the explorer no longer mints/persists
// JWTs itself (the token column is no longer written — ADT-6).
