'use server';
import { requireLinkedViewer } from '@/lib/auth/linked';

import { revalidatePath } from 'next/cache';
import { removeGovUnit, saveGovUnit, setAccountUnit, type Result } from '@/lib/services/governmentUnits';
import type { UnitFormInput } from '@/lib/services/governmentModel';

function done(r: Result): Result {
  if (r.ok) {
    revalidatePath('/admin/gov-units');
    revalidatePath('/gov', 'layout');
  }
  return r;
}

/** Server actions receive whatever the client sends; nothing is trusted until validateUnitInput. */
type Untrusted<T> = { [K in keyof T]?: unknown };

export async function saveGovUnitAction(unitId: number | null, form: Untrusted<UnitFormInput> | null): Promise<Result> {
  await requireLinkedViewer();
  if ((unitId !== null && typeof unitId !== 'number') || !form || typeof form !== 'object') return { ok: false, error: 'Bad request.' };
  const text = (v: unknown) => (typeof v === 'string' ? v : '');
  return done(await saveGovUnit(unitId, {
    slug: text(form.slug), name: text(form.name),
    parentUnitId: typeof form.parentUnitId === 'number' ? form.parentUnitId : null,
    description: typeof form.description === 'string' ? form.description : null,
  }));
}

export async function deleteGovUnitAction(unitId: number): Promise<Result> {
  await requireLinkedViewer();
  if (typeof unitId !== 'number') return { ok: false, error: 'Bad request.' };
  return done(await removeGovUnit(unitId));
}

export async function setAccountUnitAction(accountId: number, unitId: number | null): Promise<Result> {
  await requireLinkedViewer();
  if (typeof accountId !== 'number' || (unitId !== null && typeof unitId !== 'number')) return { ok: false, error: 'Bad request.' };
  return done(await setAccountUnit(accountId, unitId));
}
