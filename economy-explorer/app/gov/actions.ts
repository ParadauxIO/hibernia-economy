'use server';
import { requireLinkedViewer } from '@/lib/auth/linked';
// Typeaheads behind the /gov filter chips. Account suggestions come only from
// the viewer's scope; player names are public (economy_players).

import { findPlayersByPrefix } from '@/lib/sql/government';
import { govScopeOrNull } from '@/lib/services/governmentScope';
import type { EntityOption } from '@/lib/tables/core';

const PLAYER_FIELDS = new Set(['issuer', 'player', 'initiator', 'actor']);

export async function searchGovEntitiesAction(field: string, text: string): Promise<EntityOption[]> {
  await requireLinkedViewer();
  if (typeof field !== 'string' || typeof text !== 'string') return [];
  const scope = await govScopeOrNull();
  if (!scope) return [];
  const term = text.trim().toLowerCase().slice(0, 64);
  if (!term) return [];
  if (PLAYER_FIELDS.has(field)) return findPlayersByPrefix(term, 8);
  if (field === 'account') {
    return scope.accounts
      .filter((a) => (a.display_name ?? '').toLowerCase().includes(term) || String(a.account_id) === term.replace(/^#/, ''))
      .slice(0, 8)
      .map((a) => ({ value: String(a.account_id), label: a.display_name ?? `#${a.account_id}`, detail: `#${a.account_id}` }));
  }
  return [];
}
