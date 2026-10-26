'use client';

import { lazy, Suspense, useState, type ReactElement } from 'react';
import { HoverCard } from './popover/HoverCard';

/** JSON returned by an entity preview endpoint. One shape for every entity type. */
export interface EntityPreviewData {
  title: string;
  /** "Business account · #1204" */
  subtitle?: string | undefined;
  /** Up to four facts, already formatted by the host. */
  rows?: { label: string; value: string }[] | undefined;
}

// The card body is its own chunk: pages pay for it only once someone hovers.
const PreviewBody = lazy(() => import('./EntityPreviewBody'));

type State = { status: 'idle' | 'loading' | 'error' } | { status: 'ready'; data: EntityPreviewData };

/** Wraps a link in a hover card whose data is fetched from `src` on first open. */
export function EntityPreview({ src, children }: { src: string; children: ReactElement }) {
  const [state, setState] = useState<State>({ status: 'idle' });
  const load = () => {
    setState({ status: 'loading' });
    fetch(src, { headers: { accept: 'application/json' } })
      .then((res) => (res.ok ? (res.json() as Promise<EntityPreviewData>) : Promise.reject(new Error(String(res.status)))))
      .then((data) => setState({ status: 'ready', data }))
      .catch(() => setState({ status: 'error' }));
  };
  return <HoverCard trigger={children} onFirstOpen={load}>
    {state.status === 'ready'
      ? <Suspense fallback={<span className="hover-card-status">Loading…</span>}><PreviewBody data={state.data} /></Suspense>
      : <span className="hover-card-status">{state.status === 'error' ? 'Preview unavailable' : 'Loading…'}</span>}
  </HoverCard>;
}
