'use client';
import { useState, useTransition } from 'react';
import { createGroupAction } from '@/lib/actions/admin';
import styles from '../admin.module.css';

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
    <form onSubmit={onSubmit} className={styles.row}>
      <label className="field">
        <span className="field-label">Name</span>
        <input className="input" placeholder="Group name (e.g. DOC)" value={name} onChange={(e) => setName(e.target.value)} required />
      </label>
      <label className={`field ${styles.grow}`}>
        <span className="field-label">Description (optional)</span>
        <input className="input" placeholder="Description (optional)" value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      <button className="btn" disabled={pending || !name.trim()}>{pending ? 'Creating…' : 'Create group'}</button>
      {err && <span role="alert" className="state-error small">{err}</span>}
    </form>
  );
}
