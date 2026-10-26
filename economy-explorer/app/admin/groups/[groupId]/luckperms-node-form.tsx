'use client';
import { useState, useTransition } from 'react';
import { setLuckpermsNodeAction } from '@/lib/actions/admin';
import styles from '../../admin.module.css';

export function LuckpermsNodeForm({ groupId, node, disabled = false }: { groupId: number; node: string | null; disabled?: boolean }) {
  const [pending, start] = useTransition();
  const [value, setValue] = useState(node ?? '');
  const [err, setErr] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  function onSave(e: React.FormEvent) {
    e.preventDefault();
    setErr(null); setSaved(false);
    start(async () => {
      const r = await setLuckpermsNodeAction(groupId, value || null);
      if (!r.ok) setErr(r.error ?? 'Failed'); else { setSaved(true); window.location.reload(); }
    });
  }

  return (
    <form onSubmit={onSave} className={styles.row}>
      <label className={`field ${styles.grow}`}>
        <span className="field-label">LuckPerms group or permission node</span>
        <input
          disabled={disabled || pending}
          className="input mono"
          placeholder="LuckPerms group name (e.g. doc) — blank for manual only"
          value={value}
          onChange={(e) => { setValue(e.target.value); setSaved(false); }}
        />
      </label>
      <button className="btn" disabled={disabled || pending}>{pending ? 'Saving…' : 'Save'}</button>
      {saved && <span role="status" className="muted small">Saved.</span>}
      {err && <span role="alert" className="state-error small">{err}</span>}
    </form>
  );
}
