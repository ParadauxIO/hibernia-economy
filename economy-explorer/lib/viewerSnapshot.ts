// The client-safe view of the viewer that the chrome renders from. Built on the
// server (root layout and /api/viewer) from the cached getViewer() plus the nav
// facts that need a query. Pure, so component tests build exactly what the
// server sends. Presentation only: pages and actions re-check permissions.
import { canAccessAdmin } from '@/lib/auth/admin';
import { hasPermission, type Permission } from '@/lib/auth/policy';
import type { Viewer } from '@/lib/auth/viewer';

export interface ViewerCtx {
  anon: boolean;
  loggedIn: boolean;
  /** Has at least one admin tool. */
  isAdmin: boolean;
  /** transactions.read: the Investigate section. */
  canInvestigate: boolean;
  /** government.read.all, or government.read with a GOVERNMENT account in scope. */
  canSeeGovernment: boolean;
  /** A Minecraft identity is linked to this login. */
  linked: boolean;
  role: string | null;
  permissions: readonly Permission[];
  minecraftName: string | null;
  minecraftUuid: string | null;
}

export const ANON_VIEWER: ViewerCtx = {
  anon: true,
  loggedIn: false,
  isAdmin: false,
  canInvestigate: false,
  canSeeGovernment: false,
  linked: false,
  role: null,
  permissions: [],
  minecraftName: null,
  minecraftUuid: null,
};

export function viewerSnapshot(viewer: Viewer, nav: { governmentInScope: boolean }): ViewerCtx {
  if (viewer.anon) return ANON_VIEWER;
  return {
    anon: false,
    loggedIn: true,
    isAdmin: canAccessAdmin(viewer),
    canInvestigate: hasPermission(viewer, 'transactions.read'),
    canSeeGovernment: canSeeGovernment(viewer, nav.governmentInScope),
    linked: viewer.linked,
    role: viewer.role,
    permissions: viewer.permissions,
    minecraftName: viewer.minecraftName,
    minecraftUuid: viewer.minecraftUuid,
  };
}

/** §10.1: the workspace opens with government.read, and the scope must hold at least one GOVERNMENT account. */
export function canSeeGovernment(viewer: Viewer, governmentInScope: boolean): boolean {
  if (hasPermission(viewer, 'government.read.all')) return true;
  return hasPermission(viewer, 'government.read') && governmentInScope;
}

/** Whether the government-scope query is worth running for this viewer. */
export function needsGovernmentScope(viewer: Viewer): viewer is Extract<Viewer, { anon: false }> & { minecraftUuid: string } {
  return !viewer.anon && !!viewer.minecraftUuid
    && !hasPermission(viewer, 'government.read.all') && hasPermission(viewer, 'government.read');
}
