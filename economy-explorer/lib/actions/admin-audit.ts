'use server';

import { getViewer } from '@/lib/auth/viewer';
import { hasPermission } from '@/lib/auth/policy';
import { getAuditEntryDetail, suggestPlayers, type AuditEntryDetail } from '@/lib/services/audit';
import type { EntityOption } from '@/lib/tables/core';

/** The detail dialog on /admin/audit: one entry and its before/after diff. */
export async function getAuditEntryAction(auditId: number): Promise<{ ok: boolean; error?: string; detail?: AuditEntryDetail }> {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'audit.read')) return { ok: false, error: 'You need the audit.read permission.' };
  const detail = await getAuditEntryDetail(viewer, Number(auditId));
  return detail ? { ok: true, detail } : { ok: false, error: 'That entry no longer exists.' };
}

/**
 * Player typeahead for the admin tables' player chips (audit actor, API key
 * owner). Gated to admins so it isn't an open name-enumeration endpoint.
 */
export async function searchAdminPlayersAction(field: string, text: string): Promise<EntityOption[]> {
  if ((field !== 'actor' && field !== 'owner') || typeof text !== 'string') return [];
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'audit.read') && !hasPermission(viewer, 'api_keys.read')) return [];
  return suggestPlayers(text);
}
