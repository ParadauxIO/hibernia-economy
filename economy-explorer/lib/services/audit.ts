import 'server-only';
import { memo } from '@/lib/cache';
import { requirePermission } from '@/lib/auth/access';
import type { Viewer } from '@/lib/auth/viewer';
import { diffAuditValues, parseAuditValue, type AuditDiffRow, type AuditValue } from '@/lib/auditDiff';
import { countAuditByAction, findAuditEntry, listAuditPage, type AuditEntry } from '@/lib/sql/audit';
import { findPlayerNamesByUuid, findPlayersByPrefix } from '@/lib/sql/admin';
import { stateCursor, type EntityOption } from '@/lib/tables/core';
import type { AuditTableState } from '@/lib/tables/audit';

export interface AuditLog {
  rows: AuditEntry[];
  /** Keyset position after the last row shown, or null on the last page. */
  older: { at: Date; id: number } | null;
  actionCounts: Record<string, number>;
  entityLabels: Record<string, string>;
}

/** One page of the audit feed for `/admin/audit`. Requires `audit.read`. */
export async function getAuditLog(viewer: Viewer, state: AuditTableState): Promise<AuditLog> {
  requirePermission(viewer, 'audit.read');
  const actorUuids = state.filters.filter((f) => f.field === 'actor').flatMap((f) => f.values);
  const [page, actionCounts, names] = await Promise.all([
    listAuditPage(state, stateCursor(state)),
    memo('admin-audit:action-facets', 5 * 60_000, countAuditByAction),
    findPlayerNamesByUuid(actorUuids),
  ]);
  const rows = page.slice(0, state.limit);
  const last = rows.at(-1);
  const entityLabels: Record<string, string> = {};
  for (const [uuid, name] of names) entityLabels[`actor:${uuid}`] = name;
  return {
    rows,
    older: page.length > state.limit && last ? { at: last.at, id: last.audit_id } : null,
    actionCounts,
    entityLabels,
  };
}

export interface AuditEntryDetail {
  entry: Omit<AuditEntry, 'at'> & { at: string };
  before: AuditValue;
  after: AuditValue;
  diff: AuditDiffRow[];
}

/** One entry with its before/after diff. Requires `audit.read`. */
export async function getAuditEntryDetail(viewer: Viewer, auditId: number): Promise<AuditEntryDetail | null> {
  requirePermission(viewer, 'audit.read');
  if (!Number.isSafeInteger(auditId) || auditId <= 0) return null;
  const row = await findAuditEntry(auditId);
  if (!row) return null;
  const before = parseAuditValue(row.before_json);
  const after = parseAuditValue(row.after_json);
  const { before_json: _b, after_json: _a, ...entry } = row;
  void _b; void _a;
  return { entry: { ...entry, at: row.at.toISOString() }, before, after, diff: diffAuditValues(before, after) };
}

/** Typeahead for player chips (actor, owner). Player names are public. */
export function suggestPlayers(prefix: string): Promise<EntityOption[]> {
  return findPlayersByPrefix(prefix, 8);
}
