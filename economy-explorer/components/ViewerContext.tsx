'use client';
// Client viewer state for the header and propless client components (the nav,
// SectionTabs, export buttons). Fetched once per document from /api/viewer rather
// than seeded by the layout — that's what keeps the root layout free of the
// session cookie, so static pages (e.g. /docs) can render as static HTML.
//
// `loading` is true until the fetch resolves; consumers render the anonymous
// baseline until then (a brief header settle, never a content gate — page-level
// access is still decided server-side via getViewer in each page).
//
// Why an external store and not a React context: the fetch usually resolves while
// the page body is still streaming in or waiting for React's batched Suspense
// reveal. A context value change at the root propagates into every
// not-yet-hydrated Suspense boundary, and React's only option there is to throw
// the server HTML away and client-render the boundary — the old reveal briefly
// left a second (hidden, inert) copy of the page in the DOM and could swallow a
// navigation started in that window. A useSyncExternalStore update only
// re-renders components that have already hydrated and subscribed; a boundary
// that hydrates later reads the server snapshot first, then catches up.
import type { Permission } from '@/lib/auth/policy';
import { useEffect, useSyncExternalStore, type ReactNode } from 'react';

export interface ViewerCtx {
  anon: boolean;
  loggedIn: boolean;
  isAdmin: boolean;
  // Global read-only investigations (moderator/admin).
  canInvestigate: boolean;
  role: string | null;
  permissions: readonly Permission[];
  minecraftName: string | null;
  loading: boolean;
}

export const ANON_VIEWER: ViewerCtx = {
  anon: true,
  loggedIn: false,
  isAdmin: false,
  canInvestigate: false,
  role: null,
  permissions: [],
  minecraftName: null,
  loading: true,
};

let viewer: ViewerCtx = ANON_VIEWER;
let requested = false;
const listeners = new Set<() => void>();

function publish(next: ViewerCtx): void {
  viewer = next;
  for (const listener of listeners) listener();
}

function load(): void {
  if (requested) return;
  requested = true;
  fetch('/api/viewer', { cache: 'no-store' })
    .then((r) => (r.ok ? (r.json() as Promise<Omit<ViewerCtx, 'loading'>>) : Promise.reject(new Error(String(r.status)))))
    .then((d) => publish({ ...d, loading: false }))
    .catch(() => publish({ ...ANON_VIEWER, loading: false }));
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

const getSnapshot = () => viewer;
const getServerSnapshot = () => ANON_VIEWER;

/** Starts the one /api/viewer fetch for this document once the shell has hydrated. */
export function ViewerProvider({ children }: { children: ReactNode }) {
  useEffect(load, []);
  return children;
}

export function useViewer(): ViewerCtx {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}

export function useIsAdmin(): boolean {
  return useViewer().isAdmin;
}

export function useIsLoggedIn(): boolean {
  return useViewer().loggedIn;
}

/** Test seam: reset the module-level store between component tests. */
export function resetViewerStoreForTests(next: ViewerCtx = ANON_VIEWER, fetched = false): void {
  viewer = next;
  requested = fetched;
}
