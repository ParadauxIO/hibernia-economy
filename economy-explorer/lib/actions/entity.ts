'use server';
import { requireLinkedViewer } from '@/lib/auth/linked';
// Server actions behind the entity pages (PAR-537).

import { signIn } from '@/lib/auth/authjs';
import { getViewer } from '@/lib/auth/viewer';
import { canViewAccount } from '@/lib/auth/access';
import { suggestCounterparties } from '@/lib/services/account';
import { suggestItems } from '@/lib/services/market';
import type { EntityOption } from '@/lib/tables/core';

/** Only same-site paths survive the round trip through the identity provider. */
function safeReturnPath(path: unknown): string {
  return typeof path === 'string' && path.startsWith('/') && !path.startsWith('//') && !path.includes('\\') && path.length < 512 ? path : '/';
}

/** "Log in to see your own account": back to the page the visitor was on. */
export async function loginReturningTo(path: string): Promise<void> {
  await signIn('keycloak', { redirectTo: safeReturnPath(path) });
}

/**
 * Typeahead for an account history's Counterparty filter. The suggestions are
 * the account's counterparties, which are private, so the viewer must be able
 * to read the account. Bound to the account id by the page.
 */
export async function searchCounterpartiesAction(accountId: number, field: string, text: string): Promise<EntityOption[]> {
  await requireLinkedViewer();
  if (field !== 'counterparty' || typeof text !== 'string' || !Number.isInteger(accountId) || accountId <= 0) return [];
  if (!(await canViewAccount(accountId, await getViewer()))) return [];
  return suggestCounterparties(accountId, text);
}

/** Item keys are public (the catalogue). */
export async function searchItemsAction(field: string, text: string): Promise<EntityOption[]> {
  await requireLinkedViewer();
  if (field !== 'item' || typeof text !== 'string') return [];
  return suggestItems(text);
}
