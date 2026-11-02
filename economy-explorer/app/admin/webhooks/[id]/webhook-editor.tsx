'use client';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { useViewer } from '@/components/ViewerContext';
import { adminRotateWebhookSecretAction, adminSetWebhookUrlAction } from '@/lib/actions/webhooks';

export function WebhookEditor({ id, url, disabled }: { id: number; url: string; disabled: boolean }) {
  const { permissions } = useViewer();
  const router = useRouter();
  const [value, setValue] = useState(url);
  const [pending, start] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const blocked = disabled || pending || !permissions.includes('webhooks.update.all');
  return <div className="stack">
    <form onSubmit={e => {
      e.preventDefault(); setError(null); setSaved(false);
      start(async () => {
        const r = await adminSetWebhookUrlAction(id, value);
        if (!r.ok) setError(r.error ?? 'Update failed.');
        else { setSaved(true); router.refresh(); }
      });
    }}>
      <label className="field"><span className="field-label">Delivery URL</span><input className="input" type="url" required value={value} onChange={e => setValue(e.target.value)} /></label>
      <button className="btn" disabled={blocked || value.trim() === url}>Save URL</button>
    </form>
    <p>Rotating the secret invalidates the previous signing secret. Update the receiver before resuming delivery.</p>
    <button className="btn" disabled={blocked} onClick={() => {
      if (!confirm('Rotate this webhook’s signing secret? Update the receiver with the new secret.')) return;
      setError(null); setSecret(null);
      start(async () => {
        const r = await adminRotateWebhookSecretAction(id);
        if (!r.ok) setError(r.error ?? 'Rotation failed.');
        else setSecret(r.secret ?? null);
      });
    }}>Rotate secret</button>
    {saved && <p role="status">URL saved.</p>}
    {secret && <div role="status"><p>Copy this secret now. It is shown only once.</p><code>{secret}</code><button className="btn" onClick={() => setSecret(null)}>Dismiss secret</button></div>}
    {error && <p role="alert">{error}</p>}
  </div>;
}
