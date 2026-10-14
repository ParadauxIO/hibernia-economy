'use client';
// Client viewer context for the header and propless client components (the nav,
// SectionTabs, export buttons). Fetched once from /api/viewer on mount rather
// than seeded by the layout — that's what keeps the root layout free of the
// session cookie, so static pages (e.g. /docs) can render as static HTML.
//
// `loading` is true until the fetch resolves; consumers render the anonymous
// baseline until then (a brief header settle, never a content gate — page-level
// access is still decided server-side via getViewer in each page).
import type { Permission } from '@/lib/auth/policy';
import { createContext, useContext, useEffect, useState } from 'react';

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

const ANON: ViewerCtx = {
  anon: true,
  loggedIn: false,
  isAdmin: false,
  canInvestigate: false,
  role: null,
  permissions: [],
  minecraftName: null,
  loading: true,
};

const ViewerContext = createContext<ViewerCtx>(ANON);

export function ViewerProvider({ children }: { children: React.ReactNode }) {
  const [viewer, setViewer] = useState<ViewerCtx>(ANON);

  useEffect(() => {
    let alive = true;
    fetch('/api/viewer', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((d) => alive && setViewer({ ...d, loading: false }))
      .catch(() => alive && setViewer({ ...ANON, loading: false }));
    return () => {
      alive = false;
    };
  }, []);

  return <ViewerContext.Provider value={viewer}>{children}</ViewerContext.Provider>;
}

export function useViewer(): ViewerCtx {
  return useContext(ViewerContext);
}

export function useIsAdmin(): boolean {
  return useContext(ViewerContext).isAdmin;
}

export function useIsLoggedIn(): boolean {
  return useContext(ViewerContext).loggedIn;
}
