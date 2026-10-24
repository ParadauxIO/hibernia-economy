'use client';
import { useState, useTransition } from 'react';
import { setLuckpermsNodeAction } from '@/lib/actions/admin';

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
    <form onSubmit={onSave} style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
      <input
        disabled={disabled || pending}
        className="input"
        placeholder="LuckPerms group name (e.g. doc) — blank for manual only"
        value={value}
        onChange={(e) => { setValue(e.target.value); setSaved(false); }}
        style={{ minWidth: 320 }}
      />
      <button className="btn" disabled={disabled || pending}>{pending ? 'Saving…' : 'Save'}</button>
      {saved && <span className="sub">Saved.</span>}
      {err && <span className="state-error" style={{ fontSize: 11 }}>{err}</span>}
    </form>
  );
}
