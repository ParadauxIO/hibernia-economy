import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';

export interface EconomyConfigRow {
  config_key: string;
  value_type: 'BOOLEAN' | 'DECIMAL' | 'JSON';
  config_value: string;
  updated_at: Date;
}

/** Published plugin config (economy-flyway V32) whose key starts with `prefix`. */
export async function findConfigByPrefix(prefix: string): Promise<EconomyConfigRow[]> {
  const r = await sql<EconomyConfigRow>`
    SELECT config_key, value_type, config_value, updated_at
    FROM economy_config
    WHERE config_key LIKE CONCAT(${prefix}, '%')
  `.execute(db);
  return r.rows;
}
