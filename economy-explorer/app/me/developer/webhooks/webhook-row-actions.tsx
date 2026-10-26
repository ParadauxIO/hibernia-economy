'use client';
import { useState, useTransition } from 'react';
import { setWebhookActiveAction, deleteWebhookAction } from '@/lib/actions/webhooks';

export function WebhookRowActions({ id, active, url }: { id: number; active: boolean; url: string }) {
  const [pending, start] = useTransition();
  const [err, setErr] = useState<string | null>(null);

  function toggle() {
    setErr(null);
    start(async () => {
      const r = await setWebhookActiveAction(id, !active);
      if (!r.ok) setErr(r.error ?? 'Failed');
    });
  }

  function remove() {
    if (!confirm(`Delete the webhook to ${url}? Its delivery history is removed and no further events are sent.`)) return;
    setErr(null);
    start(async () => {
      const r = await deleteWebhookAction(id);
      if (!r.ok) setErr(r.error ?? 'Failed');
    });
  }

  return (
    <>
      <button className="btn" disabled={pending} onClick={toggle}>
        {pending ? '…' : active ? 'Pause' : 'Enable'}
      </button>{' '}
      <button className="btn" disabled={pending} onClick={remove}>Delete</button>
      {err && <div className="state-error" style={{ fontSize: 11 }}>{err}</div>}
    </>
  );
}
