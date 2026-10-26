'use client';
import { useTransition, useState } from 'react';
import { useRouter } from 'next/navigation';
import { rotateApiKeyAction } from '@/lib/actions/admin';

export function RotateButton({ keyId }: { keyId: number }) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [err, setErr] = useState<string | null>(null);

  function onClick() {
    if (!confirm(`Force-rotate key #${keyId}? The current token stops working; the owner must re-export it in-game.`)) return;
    start(async () => {
      const r = await rotateApiKeyAction(keyId);
      if (!r.ok) setErr(r.error ?? 'Failed');
      else { setErr(null); router.refresh(); }
    });
  }

  return (
    <>
      <button type="button" className="btn btn-quiet" disabled={pending} onClick={onClick} aria-label={`Rotate key ${keyId}`}>
        {pending ? 'Rotating…' : 'Rotate'}
      </button>
      {err && <span role="alert" className="state-error small">{err}</span>}
    </>
  );
}
