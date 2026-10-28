'use server';
import { requireLinkedViewer } from '@/lib/auth/linked';
// Server actions behind entity filter typeaheads. Each is passed to FilterBar
// as `searchEntity` and receives the field key and what the user has typed.

import { suggestFirms } from '@/lib/services/accountsDirectory';
import type { EntityOption } from '@/lib/sql/tableLookups';

/** Firm names for the accounts directory's Firm filter. Requires the same linked account as the firm directory. */
export async function searchFirmsAction(field: string, text: string): Promise<EntityOption[]> {
  await requireLinkedViewer();
  if (field !== 'firm' || typeof text !== 'string') return [];
  return suggestFirms(text);
}
