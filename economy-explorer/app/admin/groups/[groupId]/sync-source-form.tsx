'use client';
import { useState, useTransition } from 'react';
import { setGroupSyncSourceAction } from '@/lib/actions/admin';
import styles from '../../admin.module.css';

const COPY = {
  business: {
    label: 'Firm id',
    placeholder: 'Firm id, e.g. 7 — blank for none',
    save: 'Save firm source',
    clear: 'Remove firm source',
  },
  realty: {
    label: 'Region (world-uuid/region-id)',
    placeholder: 'world-uuid/region-id — blank for none',
    save: 'Save region source',
    clear: 'Remove region source',
  },
} as const;

/** Edit one explorer_group_sync row: a firm (business) or a Realty region. */
export function SyncSourceForm({ groupId, source, selector, disabled = false }: {
  groupId: number;
  source: 'business' | 'realty';
  selector: string | null;
  disabled?: boolean;
}) {
  const copy = COPY[source];
  const [pending, start] = useTransition();
  const [value, setValue] = useState(selector ?? '');
  const [err, setErr] = useState<string | null>(null);

  function save(next: string | null) {
    setErr(null);
    if (next !== null && selector && next.trim() !== selector
      && !confirm('Changing the source removes its current synced members now; the next sync adds the new members.')) return;
    start(async () => {
      const r = await setGroupSyncSourceAction(groupId, source, next);
      if (!r.ok) setErr(r.error ?? 'Failed'); else window.location.reload();
    });
  }

  return (
    <form onSubmit={(e) => { e.preventDefault(); save(value.trim() || null); }} className={styles.row}>
      <label className={`field ${styles.grow}`}>
        <span className="field-label">{copy.label}</span>
        <input className="input mono" disabled={disabled || pending} placeholder={copy.placeholder} value={value} onChange={(e) => setValue(e.target.value)} />
      </label>
      <button className="btn" disabled={disabled || pending || value.trim() === (selector ?? '')} aria-label={copy.save}>{pending ? 'Saving…' : 'Save'}</button>
      {selector && (
        <button type="button" className={`btn btn-quiet ${styles.danger}`} disabled={disabled || pending} aria-label={copy.clear}
          onClick={() => { if (confirm('Remove this source? Its synced members lose the group’s roles now.')) { setValue(''); save(null); } }}>Remove</button>
      )}
      {err && <span role="alert" className="state-error small">{err}</span>}
    </form>
  );
}
