'use client';
import { useState, useTransition } from 'react';
import { deleteGroupAction } from '@/lib/actions/admin';

export function DeleteGroupButton({ groupId, name }: { groupId: number; name: string }) {
  const [pending, start] = useTransition();
  const [err, setErr] = useState<string | null>(null);

  function onClick() {
    if (!confirm(`Delete group "${name}"? Its capabilities and memberships are removed. This cannot be undone.`)) return;
    setErr(null);
    start(async () => {
      const r = await deleteGroupAction(groupId);
      if (!r.ok) setErr(r.error ?? 'Failed'); else window.location.reload();
    });
  }

  return (
    <>
      <button className="btn" disabled={pending} onClick={onClick}>{pending ? 'Deleting…' : 'Delete'}</button>
      {err && <div className="state-error" style={{ fontSize: 11 }}>{err}</div>}
    </>
  );
}
