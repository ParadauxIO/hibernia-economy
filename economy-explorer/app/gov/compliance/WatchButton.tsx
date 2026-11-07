'use client';

import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { setWatchedAction } from './actions';

/** Watch / Stop watching toggle for one account. */
export function WatchButton({ accountId, watched, label }: { accountId: number; watched: boolean; label: string }) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [error, setError] = useState<string | null>(null);
  return <span className="gov-inline-actions">
    <button type="button" className="btn btn-quiet" disabled={pending} aria-pressed={watched}
      aria-label={`${watched ? 'Stop watching' : 'Watch'} ${label}`}
      onClick={() => start(async () => {
        setError(null);
        const r = await setWatchedAction(accountId, !watched);
        if (!r.ok) setError(r.error ?? 'Could not update the watchlist.');
        else router.refresh();
      })}>{pending ? 'Saving…' : watched ? 'Stop watching' : 'Watch'}</button>
    {error && <span className="gov-form-error" role="alert">{error}</span>}
  </span>;
}
