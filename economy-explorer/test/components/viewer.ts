// Seeds the client viewer store the way /api/viewer would for a given role set,
// so permission-gated components can be rendered as anon / player / moderator /
// admin without a fetch.
import { ANON_VIEWER, resetViewerStoreForTests, type ViewerCtx } from '@/components/ViewerContext';
import { ADMIN_TOOLS } from '@/lib/auth/admin';
import type { Permission } from '@/lib/auth/policy';
import { permissionsFor } from '../role-fixtures';

export type TestRole = 'anon' | 'player' | 'moderator' | 'admin';

/** Mirrors app/api/viewer/route.ts: isAdmin = any admin tool, canInvestigate = transactions.read. */
export function viewerWithPermissions(permissions: readonly Permission[], role: string | null = 'custom'): ViewerCtx {
  return {
    anon: false,
    loggedIn: true,
    isAdmin: ADMIN_TOOLS.some((tool) => permissions.includes(tool.permission)),
    canInvestigate: permissions.includes('transactions.read'),
    role,
    permissions,
    minecraftName: 'Steve',
    loading: false,
  };
}

export function viewerFor(role: TestRole): ViewerCtx {
  if (role === 'anon') return { ...ANON_VIEWER, loading: false };
  // Moderators are players too (LP groups stack), matching how roles are granted.
  const roles = role === 'player' ? ['player'] : ['player', role];
  return viewerWithPermissions(permissionsFor(...roles), role);
}

/** Publish a viewer before render. `fetched = true` keeps ViewerProvider from calling fetch. */
export function setViewer(viewer: TestRole | ViewerCtx): void {
  resetViewerStoreForTests(typeof viewer === 'string' ? viewerFor(viewer) : viewer, true);
}
