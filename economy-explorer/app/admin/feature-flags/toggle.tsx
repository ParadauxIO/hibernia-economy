'use client';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { setFeatureFlagAction } from '@/lib/actions/featureFlags';

export function FlagToggle({ name, enabled, revision }: { name: string; enabled: boolean; revision: number }) {
  const [pending, start] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const router = useRouter();
  return <>
    <button type="button" className="btn btn-quiet" disabled={pending} aria-label={`${enabled ? 'Disable' : 'Enable'} ${name}`}
      onClick={() => { setError(null); start(async () => {
        const result = await setFeatureFlagAction({ name, enabled: !enabled, revision });
        if (!result.ok) setError(result.error ?? 'Unable to save.');
        else router.refresh();
      }); }}>{pending ? 'Saving…' : enabled ? 'Disable' : 'Enable'}</button>
    {error && <p role="alert" className="state-error">{error}</p>}
  </>;
}
