import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';
import type { EntityOption } from '@/lib/tables/core';

// Small, index-only lookups behind table filter chips: the entity typeahead
// (prefix match, never a leading wildcard) and the names shown on entity chips.

export type { EntityOption };

/** Escape LIKE wildcards in a prefix. */
function likePrefix(text: string): string {
  return `${text.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
}

/** Firms whose name starts with `prefix` (uq_firm_display_name range scan). Active firms first. */
export async function findFirmsByPrefix(prefix: string, limit = 8): Promise<EntityOption[]> {
  const r = await sql<{ firm_id: number; display_name: string; archived: number }>`
    SELECT f.firm_id, f.display_name, f.is_archived AS archived
    FROM firm f
    WHERE f.display_name LIKE ${likePrefix(prefix)}
    ORDER BY f.display_name
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({
    value: String(row.firm_id),
    label: row.display_name,
    ...(row.archived ? { detail: 'disbanded' } : {}),
  }));
}

/** Names for firm ids already on the URL (primary-key lookups). */
export async function findFirmNames(ids: readonly number[]): Promise<Map<number, string>> {
  if (ids.length === 0) return new Map();
  const r = await sql<{ firm_id: number; display_name: string }>`
    SELECT f.firm_id, f.display_name FROM firm f WHERE f.firm_id IN (${sql.join(ids)})
  `.execute(db);
  return new Map(r.rows.map((row) => [row.firm_id, row.display_name]));
}
