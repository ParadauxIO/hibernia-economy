import 'server-only';
import { sql, type RawBuilder, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { compileTableState, defineColumnMap } from '@/lib/sql/tableState';
import { AUDIT_ACTIONS, actionValue, auditTable, type AuditTableState } from '@/lib/tables/audit';

// explorer_audit reads for /admin/audit. Every filter is index-backed:
//   actor   → idx_audit_actor (actor_uuid_bin, at)
//   action  → idx_audit_action (action, at)            (V45)
//   target  → idx_audit_target (target_type, target_id, at)
//   time    → idx_audit_at (at)                        (V9)
// and the feed is keyset-paged on (at, audit_id) so deep pages never OFFSET.

export interface AuditEntry {
  audit_id: number;
  at: Date;
  actor_sub: string;
  actor_uuid: string | null;
  actor_name: string | null;
  actor_role: string | null;
  action: string;
  method: string | null;
  path: string | null;
  target_type: string | null;
  target_id: string | null;
  outcome: number;
  source_ip: string | null;
  /** True when the row carries before/after values (detail dialog). */
  has_detail: boolean;
}

export interface AuditDetail extends AuditEntry {
  before_json: string | null;
  after_json: string | null;
}

interface RawRow {
  audit_id: number;
  at: Date;
  actor_sub: string;
  actor_uuid_bin: Buffer | null;
  actor_name: string | null;
  actor_role: string | null;
  action: string;
  method: string | null;
  path: string | null;
  target_type: string | null;
  target_id: string | null;
  outcome: number;
  source_ip: string | null;
  has_detail: number;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ACTION_VALUES: Record<string, string> = Object.fromEntries(AUDIT_ACTIONS.map((a) => [actionValue(a.action), a.action]));

/** Actor chip values are canonical UUIDs or player names (lib/tables/core.ts). */
function actorCondition(values: readonly string[]): RawBuilder<SqlBool> | null {
  const uuids = values.filter((v) => UUID_RE.test(v));
  const names = values.filter((v) => !UUID_RE.test(v));
  const parts: RawBuilder<SqlBool>[] = [];
  if (uuids.length) parts.push(sql<SqlBool>`e.actor_uuid_bin IN (${sql.join(uuids.map(uuidToBin))})`);
  // A name resolves through the player directory (unique name index), so the
  // actor index still drives the scan; the stored actor_name covers players
  // who have since been renamed.
  if (names.length) {
    parts.push(sql<SqlBool>`(e.actor_uuid_bin IN (SELECT p.player_uuid_bin FROM economy_players p WHERE p.name_lower IN (${sql.join(names.map((n) => n.toLowerCase()))}))
      OR e.actor_name IN (${sql.join(names)}))`);
  }
  if (parts.length === 0) return null;
  return parts.length === 1 ? (parts[0] as RawBuilder<SqlBool>) : sql<SqlBool>`(${sql.join(parts, sql` OR `)})`;
}

/** Escape LIKE wildcards in a prefix. */
function likePrefix(text: string): string {
  return `${text.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
}

export const auditColumnMap = defineColumnMap({
  table: auditTable,
  filters: {
    actor: { compile: (f) => (f.op === 'in' ? actorCondition(f.values) : null) },
    action: { column: sql`e.action`, values: ACTION_VALUES },
    target: { column: sql`e.target_type` },
    targetId: { column: sql`e.target_id` },
    at: { column: sql`e.at` },
  },
  sorts: { at: sql`e.at` },
  tiebreak: { column: sql`e.audit_id`, dir: 'desc' },
  // Prefix matches only, so the search can't turn into a scan of the path text.
  search: (q) => {
    const term = q.trim().slice(0, 64);
    if (!term) return null;
    return sql<SqlBool>`(e.actor_name LIKE ${likePrefix(term)} OR e.target_id = ${term} OR e.path LIKE ${likePrefix(term)})`;
  },
});

function mapRow(row: RawRow): AuditEntry {
  return {
    audit_id: Number(row.audit_id),
    at: row.at,
    actor_sub: row.actor_sub,
    actor_uuid: row.actor_uuid_bin ? binToUuid(row.actor_uuid_bin) : null,
    actor_name: row.actor_name,
    actor_role: row.actor_role,
    action: row.action,
    method: row.method,
    path: row.path,
    target_type: row.target_type,
    target_id: row.target_id,
    outcome: row.outcome,
    source_ip: row.source_ip,
    has_detail: Number(row.has_detail) === 1,
  };
}

const COLUMNS = sql`e.audit_id, e.at, e.actor_sub, e.actor_uuid_bin, e.actor_name, e.actor_role, e.action,
  e.method, e.path, e.target_type, e.target_id, e.outcome, e.source_ip,
  (e.before_json IS NOT NULL OR e.after_json IS NOT NULL) AS has_detail`;

/**
 * One page of the audit feed, newest first, starting after `after` (the last
 * row of the previous page). Returns up to `limit + 1` rows so the caller can
 * tell whether an older page exists.
 */
export async function listAuditPage(state: AuditTableState, after: KeysetCursor | null, now: Date = new Date()): Promise<AuditEntry[]> {
  const compiled = compileTableState(state, auditColumnMap, now);
  const conditions = [...compiled.conditions];
  // The feed is always (at DESC, audit_id DESC); the only declared sort is `at`.
  const asc = state.sort.dir === 'asc';
  if (after) {
    conditions.push(asc
      ? sql<SqlBool>`(e.at > ${after.at} OR (e.at = ${after.at} AND e.audit_id > ${after.id}))`
      : sql<SqlBool>`(e.at < ${after.at} OR (e.at = ${after.at} AND e.audit_id < ${after.id}))`);
  }
  const where = conditions.length ? sql`WHERE ${sql.join(conditions, sql` AND `)}` : sql``;
  const dir = sql.raw(asc ? 'ASC' : 'DESC');
  const r = await sql<RawRow>`
    SELECT ${COLUMNS}
    FROM explorer_audit e
    ${where}
    ORDER BY e.at ${dir}, e.audit_id ${dir}
    LIMIT ${state.limit + 1}
  `.execute(db);
  return r.rows.map(mapRow);
}

/** One row with its before/after values, for the detail dialog. */
export async function findAuditEntry(auditId: number): Promise<AuditDetail | null> {
  const r = await sql<RawRow & { before_json: string | null; after_json: string | null }>`
    SELECT ${COLUMNS}, e.before_json, e.after_json
    FROM explorer_audit e
    WHERE e.audit_id = ${auditId}
  `.execute(db);
  const row = r.rows[0];
  return row ? { ...mapRow(row), before_json: row.before_json, after_json: row.after_json } : null;
}

/** Rows per action, for the Action chip's counts. Reads only idx_audit_action. */
export async function countAuditByAction(): Promise<Record<string, number>> {
  const r = await sql<{ action: string; c: string | number }>`
    SELECT e.action, COUNT(*) AS c FROM explorer_audit e GROUP BY e.action
  `.execute(db);
  const out: Record<string, number> = {};
  for (const row of r.rows) out[actionValue(row.action)] = Number(row.c);
  return out;
}
