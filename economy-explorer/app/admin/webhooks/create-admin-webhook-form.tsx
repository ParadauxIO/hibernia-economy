'use client';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { adminCreateWebhookAction } from '@/lib/actions/webhooks';
import { isDiscordWebhookUrl } from '@/lib/util/discord';
import styles from '../admin.module.css';

export function CreateAdminWebhookForm() {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [accountId, setAccountId] = useState('');
  const [url, setUrl] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    setSecret(null);
    const id = Number(accountId);
    if (!Number.isInteger(id) || id <= 0) {
      setErr('Enter a valid account id.');
      return;
    }
    start(async () => {
      const r = await adminCreateWebhookAction({ accountId: id, url });
      if (!r.ok) setErr(r.error ?? 'Failed');
      else {
        setUrl('');
        setAccountId('');
        setSecret(r.secret ?? null);
        router.refresh();
      }
    });
  }

  return (
    <div className={styles.stack}>
      <form onSubmit={onSubmit} className={styles.row}>
        <label className="field">
          <span className="field-label">Account</span>
          <input className={`input ${styles.narrow}`} type="number" min="1" placeholder="Account #" value={accountId} onChange={(e) => setAccountId(e.target.value)} required />
        </label>
        <label className={`field ${styles.grow}`}>
          <span className="field-label">Endpoint URL</span>
          <input className="input" type="url" placeholder="https://endpoint.example.com/hook" value={url} onChange={(e) => setUrl(e.target.value)} required />
        </label>
        <button className="btn" disabled={pending || !accountId || !url.trim()}>{pending ? 'Creating…' : 'Create'}</button>
      </form>
      {err && <p role="alert" className="state-error">{err}</p>}
      {isDiscordWebhookUrl(url) && <p className={styles.hint}>This is a Discord webhook, so deliveries are sent as a rich embed.</p>}
      {secret && (
        <div className="field" role="status">
          <span className="field-label">Signing secret, shown once. Copy it now.</span>
          <code className="mono">{secret}</code>
        </div>
      )}
    </div>
  );
}
