'use client';
import { useState, useTransition } from 'react';
import { setGroupCapabilitiesAction } from '@/lib/actions/admin';
import styles from '../../admin.module.css';

export function CapabilitiesForm({
  groupId,
  all,
  selected,
  disabled = false,
}: {
  groupId: number;
  all: { value: string; label: string; description: string; permissions: number }[];
  selected: string[];
  disabled?: boolean;
}) {
  const [pending, start] = useTransition();
  const [chosen, setChosen] = useState<Set<string>>(new Set(selected));
  // The last-known-saved set, so "dirty" is correct whether or not the server
  // action revalidates the page (the screen reflects the synced state on load).
  const [savedSet, setSavedSet] = useState<Set<string>>(new Set(selected));
  const [err, setErr] = useState<string | null>(null);
  const [justSaved, setJustSaved] = useState(false);

  const dirty = chosen.size !== savedSet.size || [...chosen].some((c) => !savedSet.has(c));

  function toggle(v: string) {
    setJustSaved(false);
    setChosen((prev) => {
      const next = new Set(prev);
      if (next.has(v)) next.delete(v); else next.add(v);
      return next;
    });
  }

  function onSave() {
    setErr(null);
    start(async () => {
      const r = await setGroupCapabilitiesAction(groupId, [...chosen]);
      if (!r.ok) {
        setErr(r.error ?? 'Failed');
      } else {
        setSavedSet(new Set(chosen));
        setJustSaved(true);
        window.location.reload();
      }
    });
  }

  return (
    <div className={styles.stack}>
      <ul className={styles.checklist}>
        {all.map((c) => (
          <li key={c.value}>
            <label className={styles.check}>
              <input type="checkbox" disabled={disabled || pending} checked={chosen.has(c.value)} onChange={() => toggle(c.value)} />
              <span>
                <strong>{c.label}</strong> <span className="mono muted">{c.value}</span>
                <span className={`${styles.hint} small`}>{' '}· {c.permissions} permission{c.permissions === 1 ? '' : 's'}</span>
                {c.description && <><br /><span className={styles.hint}>{c.description}</span></>}
              </span>
            </label>
          </li>
        ))}
      </ul>
      <div className={styles.row}>
        <button type="button" className="btn btn-primary" disabled={disabled || pending || !dirty} onClick={onSave}>
          {pending ? 'Saving…' : 'Save roles'}
        </button>
        <span className="muted small" role="status">{dirty ? 'Unsaved changes.' : justSaved ? 'Saved.' : 'In sync.'}</span>
        {err && <span role="alert" className="state-error small">{err}</span>}
      </div>
    </div>
  );
}
