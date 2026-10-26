'use client';
// Client viewer state for the chrome and propless client components (the nav,
// the account menu, the palette, export buttons). The root layout resolves the
// viewer on the server with the cached getViewer() and passes the snapshot in,
// so the first render already knows who is looking: no /api/viewer round trip
// per document and no logged-out flash in the header.
//
// The server value lives in a context. It only changes when the server sends a
// new one (router.refresh, a server action), never during hydration, so it
// cannot force Suspense boundaries that are still hydrating to client-render.
//
// refreshViewer() re-reads /api/viewer for the cases where the server value
// can be stale without a new render: a page restored from the back/forward
// cache after logging out (or in) elsewhere. Its result overrides the server
// value until the server sends a newer one.
import { createContext, useContext, useEffect, useSyncExternalStore, type ReactNode } from 'react';
import { ANON_VIEWER, type ViewerCtx } from '@/lib/viewerSnapshot';

export { ANON_VIEWER, type ViewerCtx };

const ServerViewer = createContext<ViewerCtx>(ANON_VIEWER);

/** A client-side value, and the server value it was fetched over. 'any' applies regardless (tests). */
type Override = { value: ViewerCtx; over: ViewerCtx | 'any' } | null;
let override: Override = null;
const listeners = new Set<() => void>();

function publish(next: Override): void {
  override = next;
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

const getSnapshot = () => override;
const getServerSnapshot = (): Override => null;

/** Re-read the viewer from /api/viewer, e.g. after a login or logout in another tab. */
export async function refreshViewer(over: ViewerCtx | 'any' = 'any'): Promise<void> {
  try {
    const r = await fetch('/api/viewer', { cache: 'no-store' });
    if (!r.ok) return;
    publish({ value: (await r.json()) as ViewerCtx, over });
  } catch {
    /* keep what we have; the next server render corrects it */
  }
}

export function ViewerProvider({ viewer, children }: { viewer: ViewerCtx; children: ReactNode }) {
  useEffect(() => {
    // Back/forward cache restores the old DOM and JS heap without a request.
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) void refreshViewer(viewer);
    };
    window.addEventListener('pageshow', onPageShow);
    return () => window.removeEventListener('pageshow', onPageShow);
  }, [viewer]);
  return <ServerViewer.Provider value={viewer}>{children}</ServerViewer.Provider>;
}

export function useViewer(): ViewerCtx {
  const server = useContext(ServerViewer);
  const local = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
  if (local && (local.over === 'any' || local.over === server)) return local.value;
  return server;
}

export function useIsAdmin(): boolean {
  return useViewer().isAdmin;
}

export function useIsLoggedIn(): boolean {
  return useViewer().loggedIn;
}

/** Test seam: render as `next` without a provider (null restores the anonymous default). */
export function resetViewerStoreForTests(next: ViewerCtx | null = null): void {
  override = next ? { value: next, over: 'any' } : null;
}
