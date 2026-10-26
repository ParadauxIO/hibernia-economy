'use client';
import { useState, useTransition } from 'react';
import { Dialog } from '@democracycraft/design-system/popover';
import { setRateLimitAction } from '@/lib/actions/admin';
import styles from '../admin.module.css';

interface LimitDialogProps {
  ownerUuid: string;
  ownerName: string;
  currentMultiplier: string;
}

export function LimitDialog({ ownerUuid, ownerName, currentMultiplier }: LimitDialogProps) {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState(currentMultiplier);
  const [note, setNote] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [pending, start] = useTransition();

  function save() {
    // A rate multiplier, not money: a plain number is right here.
    const mult = Number(value);
    if (!Number.isFinite(mult) || mult <= 0) {
      setErr('Enter a positive multiplier (1 = default).');
      return;
    }
    start(async () => {
      const r = await setRateLimitAction({ ownerUuid, multiplier: mult, note: note || null });
      if (!r.ok) setErr(r.error ?? 'Failed');
      else { setErr(null); setOpen(false); }
    });
  }

  return (
    <>
      <button type="button" className="btn btn-quiet" onClick={() => setOpen(true)}>Limit</button>
      {open && <Dialog
        open
        onClose={() => setOpen(false)}
        title={`Rate limit · ${ownerName}`}
        description="The multiplier applies to every key this issuer holds. 1 is the default; raise it for a trusted operator."
        actions={<>
          <button type="button" className="btn" onClick={() => setOpen(false)} disabled={pending}>Cancel</button>
          <button type="button" className="btn btn-primary" onClick={save} disabled={pending}>{pending ? 'Saving…' : 'Save'}</button>
        </>}
      >
        <div className={styles.stack}>
          <label className="field">
            <span className="field-label">Multiplier</span>
            <input type="number" min="0.1" step="0.5" className={`input ${styles.narrow}`} value={value} onChange={(e) => setValue(e.target.value)} />
          </label>
          <label className="field">
            <span className="field-label">Note (optional)</span>
            <input type="text" className="input" placeholder="why this limit was changed" value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          {err && <p role="alert" className="state-error">{err}</p>}
        </div>
      </Dialog>}
    </>
  );
}
