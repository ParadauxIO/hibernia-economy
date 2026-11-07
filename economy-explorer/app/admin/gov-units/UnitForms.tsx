'use client';

import { useId, useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { Dialog } from '@democracycraft/design-system/popover';
import { deleteGovUnitAction, saveGovUnitAction, setAccountUnitAction } from './actions';

export interface UnitChoice { unitId: number; name: string; slug: string; parentUnitId: number | null; description: string | null }

const slugify = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);

/** Create (no `unit`) or edit a unit in a Dialog. */
export function UnitDialogButton({ unit, units }: { unit?: UnitChoice; units: UnitChoice[] }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [pending, start] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState(unit?.name ?? '');
  const [slug, setSlug] = useState(unit?.slug ?? '');
  const [slugTouched, setSlugTouched] = useState(unit !== undefined);
  const [parent, setParent] = useState(unit?.parentUnitId === null || unit === undefined ? '' : String(unit.parentUnitId));
  const [description, setDescription] = useState(unit?.description ?? '');
  const id = useId();
  const title = unit ? `Edit ${unit.name}` : 'New unit';

  const submit = () => start(async () => {
    setError(null);
    const r = await saveGovUnitAction(unit?.unitId ?? null, { name, slug, parentUnitId: parent ? Number(parent) : null, description: description || null });
    if (!r.ok) { setError(r.error); return; }
    setOpen(false);
    if (!unit) { setName(''); setSlug(''); setSlugTouched(false); setParent(''); setDescription(''); }
    router.refresh();
  });

  return <>
    <button type="button" className={unit ? 'btn btn-quiet' : 'btn btn-primary'} onClick={() => setOpen(true)} aria-label={unit ? `Edit ${unit.name}` : undefined}>{unit ? 'Edit' : 'New unit'}</button>
    <Dialog open={open} onClose={() => setOpen(false)} title={title} size="md"
      description="Units group government accounts for the /gov workspace. They don’t give anyone access."
      actions={<>
        <button type="button" className="btn btn-quiet" onClick={() => setOpen(false)}>Cancel</button>
        <button type="submit" form={`${id}-form`} className="btn btn-primary" disabled={pending || !name.trim() || !slug.trim()}>{pending ? 'Saving…' : unit ? 'Save unit' : 'Create unit'}</button>
      </>}>
      <form id={`${id}-form`} className="gov-form" onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <label className="field"><span className="field-label">Name</span>
          <input className="input" value={name} maxLength={128} required onChange={(e) => { setName(e.target.value); if (!slugTouched) setSlug(slugify(e.target.value)); }} />
        </label>
        <label className="field"><span className="field-label">Slug</span>
          <input className="input" value={slug} maxLength={40} pattern="[a-z0-9][a-z0-9-]*" required aria-describedby={`${id}-slug`}
            onChange={(e) => { setSlug(e.target.value); setSlugTouched(true); }} />
          <span id={`${id}-slug`} className="muted small">Used in links, for example /gov?unit={slug || 'commerce'}.</span>
        </label>
        <label className="field"><span className="field-label">Parent unit</span>
          <select className="select" value={parent} onChange={(e) => setParent(e.target.value)}>
            <option value="">None (top level)</option>
            {units.filter((u) => u.unitId !== unit?.unitId).map((u) => <option key={u.unitId} value={u.unitId}>{u.name}</option>)}
          </select>
        </label>
        <label className="field"><span className="field-label">Description</span>
          <textarea className="input" rows={3} maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>
        {error && <p className="gov-form-error" role="alert">{error}</p>}
      </form>
    </Dialog>
  </>;
}

export function DeleteUnitButton({ unit }: { unit: UnitChoice }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [pending, start] = useTransition();
  const [error, setError] = useState<string | null>(null);
  return <>
    <button type="button" className="btn btn-quiet" onClick={() => setOpen(true)} aria-label={`Delete ${unit.name}`}>Delete</button>
    <Dialog open={open} onClose={() => setOpen(false)} title={`Delete ${unit.name}?`}
      description="Its accounts become unassigned and its sub-units move to the top level. No money or access changes."
      actions={<>
        <button type="button" className="btn btn-quiet" onClick={() => setOpen(false)}>Cancel</button>
        <button type="button" className="btn btn-primary" disabled={pending} onClick={() => start(async () => {
          const r = await deleteGovUnitAction(unit.unitId);
          if (!r.ok) { setError(r.error); return; }
          setOpen(false);
          router.refresh();
        })}>{pending ? 'Deleting…' : 'Delete unit'}</button>
      </>}>
      {error && <p className="gov-form-error" role="alert">{error}</p>}
    </Dialog>
  </>;
}

/** Assign or unassign one account. */
export function AccountUnitSelect({ accountId, accountName, unitId, units }: { accountId: number; accountName: string; unitId: number | null; units: UnitChoice[] }) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [error, setError] = useState<string | null>(null);
  return <span className="gov-inline-actions">
    <select className="select" aria-label={`Unit for ${accountName}`} value={unitId === null ? '' : String(unitId)} disabled={pending}
      onChange={(e) => {
        const next = e.target.value ? Number(e.target.value) : null;
        start(async () => {
          setError(null);
          const r = await setAccountUnitAction(accountId, next);
          if (!r.ok) setError(r.error); else router.refresh();
        });
      }}>
      <option value="">No unit</option>
      {units.map((u) => <option key={u.unitId} value={u.unitId}>{u.name}</option>)}
    </select>
    {error && <span className="gov-form-error" role="alert">{error}</span>}
  </span>;
}
