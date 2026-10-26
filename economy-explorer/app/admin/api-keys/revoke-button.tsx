'use client';
import { useTransition, useState } from 'react';
import { useRouter } from 'next/navigation';
import { revokeApiKeyAction } from '@/lib/actions/admin';

export function RevokeButton({ keyId }: { keyId: number }) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [err, setErr] = useState<string | null>(null);

  function onClick() {
    if (!confirm(`Revoke API key #${keyId}? Cannot be undone.`)) return;
    start(async () => {
      const r = await revokeApiKeyAction(keyId);
      if (!r.ok) setErr(r.error ?? 'Failed');
      else router.refresh();
    });
  }

  return (
    <>
      <button type="button" className="btn btn-quiet" disabled={pending} onClick={onClick} aria-label={`Revoke key ${keyId}`}>
        {pending ? 'Revoking…' : 'Revoke'}
      </button>
      {err && <span role="alert" className="state-error small">{err}</span>}
    </>
  );
}
