import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid } from '@/lib/db';

// Read-only over api_rate_limit_override (ADT-14): writes go through the REST
// admin API (lib/treasury.ts setRateLimitOverride / clearRateLimitOverride).

export interface RateLimitOverride {
  owner_uuid: string;
  multiplier: string;
  note: string | null;
  updated_at: Date;
}

/** Mirrors ApiRateLimitOverrideMapper.findAll. */
export async function listRateLimitOverrides(): Promise<RateLimitOverride[]> {
  const r = await sql<{
    owner_uuid: Buffer;
    multiplier: string;
    note: string | null;
    updated_at: Date;
  }>`
    SELECT owner_uuid_bin AS owner_uuid, multiplier, note, updated_at
    FROM api_rate_limit_override
  `.execute(db);
  return r.rows.map((row) => ({
    owner_uuid: binToUuid(row.owner_uuid),
    multiplier: row.multiplier,
    note: row.note,
    updated_at: row.updated_at,
  }));
}

