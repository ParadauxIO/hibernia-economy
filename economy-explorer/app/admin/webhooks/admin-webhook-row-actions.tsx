'use client';
import { useViewer } from '@/components/ViewerContext';
import { useState, useTransition } from 'react';
import { adminSetWebhookActiveAction, adminDeleteWebhookAction } from '@/lib/actions/webhooks';

export function AdminWebhookRowActions({ id, active, url }: { id: number; active: boolean; url: string }) {
  const { permissions } = useViewer();
  const [pending, start] = useTransition();
  const [err, setErr] = useState<string | null>(null);

  function toggle() {
    setErr(null);
    start(async () => {
      const r = await adminSetWebhookActiveAction(id, !active);
      if (!r.ok) setErr(r.error ?? 'Failed');
    });
  }

  function remove() {
    if (!confirm(`Delete webhook #${id} → ${url}? Its delivery history is removed and no further events are sent.`)) return;
    setErr(null);
    start(async () => {
      const r = await adminDeleteWebhookAction(id);
      if (!r.ok) setErr(r.error ?? 'Failed');
    });
  }

  return (
    <>
      <button className="btn" disabled={!permissions.includes('webhooks.update.all') || pending} onClick={toggle}>{pending ? '…' : active ? 'Pause' : 'Enable'}</button>{' '}
      <button className="btn" disabled={!permissions.includes('webhooks.delete.all') || pending} onClick={remove}>Delete</button>
      {err && <div className="state-error" style={{ fontSize: 11 }}>{err}</div>}
    </>
  );
}
