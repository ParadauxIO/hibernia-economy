'use client';
import { useViewer } from '@/components/ViewerContext';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { adminSetWebhookActiveAction, adminDeleteWebhookAction } from '@/lib/actions/webhooks';

export function AdminWebhookRowActions({ id, active, url }: { id: number; active: boolean; url: string }) {
  const router = useRouter();
  const { permissions } = useViewer();
  const [pending, start] = useTransition();
  const [err, setErr] = useState<string | null>(null);

  function toggle() {
    setErr(null);
    start(async () => {
      const r = await adminSetWebhookActiveAction(id, !active);
      if (!r.ok) setErr(r.error ?? 'Failed');
      else router.refresh();
    });
  }

  function remove() {
    if (!confirm(`Delete webhook #${id} → ${url}? Its delivery history is removed and no further events are sent.`)) return;
    setErr(null);
    start(async () => {
      const r = await adminDeleteWebhookAction(id);
      if (!r.ok) setErr(r.error ?? 'Failed');
      else router.refresh();
    });
  }

  return (
    <>
      <button type="button" className="btn btn-quiet" disabled={!permissions.includes('webhooks.update.all') || pending} onClick={toggle}
        aria-label={`${active ? 'Pause' : 'Enable'} webhook ${id}`}>{pending ? '…' : active ? 'Pause' : 'Enable'}</button>
      <button type="button" className="btn btn-quiet" disabled={!permissions.includes('webhooks.delete.all') || pending} onClick={remove}
        aria-label={`Delete webhook ${id}`}>Delete</button>
      {err && <span role="alert" className="state-error small">{err}</span>}
    </>
  );
}
