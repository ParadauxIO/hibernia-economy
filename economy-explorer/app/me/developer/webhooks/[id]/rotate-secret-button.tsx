'use client';
import { useState, useTransition } from 'react';
import { rotateWebhookSecretAction } from '@/lib/actions/webhooks';

export function RotateSecretButton({ id }: { id: number }) {
  const [pending, start] = useTransition();
  const [err, setErr] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);

  function onClick() {
    if (!confirm('Rotate the signing secret? Deliveries are signed with the new secret immediately — update your endpoint.')) return;
    setErr(null);
    setSecret(null);
    start(async () => {
      const r = await rotateWebhookSecretAction(id);
      if (!r.ok) setErr(r.error ?? 'Failed');
      else setSecret(r.secret ?? null);
    });
  }

  return (
    <>
      <button className="btn" disabled={pending} onClick={onClick}>{pending ? 'Rotating…' : 'Rotate secret'}</button>
      {err && <div className="state-error" style={{ fontSize: 11 }}>{err}</div>}
      {secret && (
        <div className="card" style={{ marginTop: 10 }}>
          <div className="card-title">New signing secret <span className="sub">shown once — copy it now</span></div>
          <code className="mono" style={{ display: 'block', wordBreak: 'break-all', userSelect: 'all' }}>{secret}</code>
        </div>
      )}
    </>
  );
}
