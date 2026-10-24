'use client';
import { useMemo, useState, useTransition } from 'react';
import { createWebhookAction } from '@/lib/actions/webhooks';
import { isDiscordWebhookUrl } from '@/lib/util/discord';

type FirmOption = { firmId: number; displayName: string };

export function CreateWebhookForm({ hasPersonal, firms }: { hasPersonal: boolean; firms: FirmOption[] }) {
  const options = useMemo(() => {
    const o: { value: string; label: string }[] = [];
    if (hasPersonal) o.push({ value: 'account', label: 'My personal account' });
    for (const f of firms) o.push({ value: `firm:${f.firmId}`, label: `Firm — ${f.displayName}` });
    return o;
  }, [hasPersonal, firms]);

  const [pending, start] = useTransition();
  const [scope, setScope] = useState(options[0]?.value ?? '');
  const [url, setUrl] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    setSecret(null);
    const [kind, firmId] = scope.split(':');
    start(async () => {
      const r = await createWebhookAction(
        kind === 'firm'
          ? { scope: 'firm', firmId: Number(firmId), url }
          : { scope: 'account', url },
      );
      if (!r.ok) setErr(r.error ?? 'Failed');
      else {
        setUrl('');
        setSecret(r.secret ?? null);
      }
    });
  }

  return (
    <>
      <form onSubmit={onSubmit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <select className="input" aria-label="Webhook scope" value={scope} onChange={(e) => setScope(e.target.value)} required>
          {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        <input
          className="input"
          type="url"
          placeholder="https://your-endpoint.example.com/treasury"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          required
          style={{ minWidth: 320, flex: 1 }}
        />
        <button className="btn" disabled={pending || !scope || !url.trim()}>{pending ? 'Creating…' : 'Create webhook'}</button>
        {err && <span className="state-error" style={{ fontSize: 11 }}>{err}</span>}
      </form>
      {isDiscordWebhookUrl(url) && (
        <p className="muted small" style={{ marginTop: 8, marginBottom: 0 }}>
          ✨ Looks like a Discord webhook — deliveries to this URL are sent as a rich embed, not raw JSON.
        </p>
      )}
      {secret && (
        <div className="card" style={{ marginTop: 10 }}>
          <div className="card-title">Signing secret <span className="sub">shown once — copy it now</span></div>
          <p className="muted small" style={{ marginTop: 0 }}>
            Verify each delivery by computing <span className="mono">HMAC-SHA256(body, secret)</span> and comparing it to the
            {' '}<span className="mono">X-Treasury-Signature</span> header. We don&apos;t store it in a recoverable form — if you
            lose it, rotate the secret.
          </p>
          <code className="mono" style={{ display: 'block', wordBreak: 'break-all', userSelect: 'all' }}>{secret}</code>
        </div>
      )}
    </>
  );
}
