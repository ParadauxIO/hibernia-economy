'use server';

import { getViewer } from '@/lib/auth/viewer';
import { accountLabel } from '@/lib/format';
import { meGate, myAccounts } from '@/lib/services/me';
import { suggestAccounts } from '@/lib/services/meTransactions';
import { selectAccountLabels } from '@/lib/sql/me';
import type { EntityOption } from '@/lib/tables/core';

/**
 * Typeahead for the Account and Counterparty chips on /me/transactions.
 * Accounts: only those the player can read. Counterparty: the same, or any
 * account by its number ("#42"); a name is public, its history is not.
 */
export async function searchMyAccountsAction(field: string, text: string): Promise<EntityOption[]> {
  if ((field !== 'account' && field !== 'counterparty') || typeof text !== 'string') return [];
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return [];
  const mine = suggestAccounts(await myAccounts(viewer, gate.uuid), text.slice(0, 64));
  if (field === 'account') return mine;
  const id = /^#?(\d{1,10})$/.exec(text.trim())?.[1];
  if (!id || mine.some((m) => m.value === id)) return mine;
  const [row] = await selectAccountLabels([Number(id)]);
  return row ? [{ value: String(row.account_id), label: accountLabel(row), detail: `#${row.account_id}` }, ...mine] : mine;
}
