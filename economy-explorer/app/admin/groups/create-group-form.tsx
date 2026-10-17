'use client';
import { useState, useTransition } from 'react';
import { createGroupAction } from '@/lib/actions/admin';

export function CreateGroupForm() {
  const [pending, start] = useTransition();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [err, setErr] = useState<string | null>(null);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    start(async () => {
      const r = await createGroupAction({ name, description: description || null });
      if (!r.ok) setErr(r.error ?? 'Failed');
      else window.location.reload();
    });
  }

  return (
    <form onSubmit={onSubmit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <input
        className="input"
        placeholder="Group name (e.g. DOC)"
        value={name}
        onChange={(e) => setName(e.target.value)}
        required
      />
      <input
        className="input"
        placeholder="Description (optional)"
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        style={{ minWidth: 240 }}
      />
      <button className="btn" disabled={pending || !name.trim()}>{pending ? 'Creating…' : 'Create group'}</button>
      {err && <span className="state-error" style={{ fontSize: 11 }}>{err}</span>}
    </form>
  );
}
