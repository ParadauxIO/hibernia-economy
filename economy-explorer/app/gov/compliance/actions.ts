'use server';
import { requireLinkedViewer } from '@/lib/auth/linked';

import { revalidatePath } from 'next/cache';
import { setWatched } from '@/lib/services/governmentCompliance';

/** Add or remove an account from the viewer's compliance watchlist. */
export async function setWatchedAction(accountId: number, watched: boolean): Promise<{ ok: boolean; error?: string }> {
  await requireLinkedViewer();
  if (typeof accountId !== 'number' || typeof watched !== 'boolean') return { ok: false, error: 'Bad request.' };
  const result = await setWatched(accountId, watched);
  if (result.ok) revalidatePath('/gov/compliance', 'layout');
  return result;
}
