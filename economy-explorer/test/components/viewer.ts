// Seeds the client viewer the way the root layout would for a given role set,
// so permission-gated components can be rendered as anon / player / moderator /
// admin without a provider. Built with the same viewerSnapshot() the server uses.
import { resetViewerStoreForTests } from '@/components/ViewerContext';
import type { Viewer } from '@/lib/auth/viewer';
import type { Permission } from '@/lib/auth/policy';
import { ANON_VIEWER, viewerSnapshot, type ViewerCtx } from '@/lib/viewerSnapshot';
import { permissionsFor } from '../role-fixtures';

export type TestRole = 'anon' | 'player' | 'moderator' | 'government' | 'admin';

export const ALICE_UUID = '00000000-0000-0000-0000-00000000a1ce';

export function signedInViewer(permissions: readonly Permission[], role = 'custom', overrides: Partial<Extract<Viewer, { anon: false }>> = {}): Extract<Viewer, { anon: false }> {
  return {
    anon: false,
    keycloakSub: 'test-sub',
    minecraftUuid: ALICE_UUID,
    minecraftName: 'Steve',
    linked: true,
    role,
    capabilities: [role],
    permissions,
    ...overrides,
  };
}

/** The chrome snapshot for a signed-in viewer holding exactly `permissions`. */
export function viewerWithPermissions(permissions: readonly Permission[], role: string | null = 'custom', options: { governmentInScope?: boolean; linked?: boolean } = {}): ViewerCtx {
  return viewerSnapshot(signedInViewer(permissions, role ?? 'custom', { linked: options.linked ?? true }), { governmentInScope: options.governmentInScope ?? false });
}

export function viewerFor(role: TestRole, options: { governmentInScope?: boolean } = {}): ViewerCtx {
  if (role === 'anon') return ANON_VIEWER;
  // Moderators are players too (LP groups stack), matching how roles are granted.
  const roles = role === 'player' ? ['player'] : ['player', role];
  return viewerWithPermissions(permissionsFor(...roles), role, options);
}

/** Render as `viewer` without a provider. */
export function setViewer(viewer: TestRole | ViewerCtx): void {
  resetViewerStoreForTests(typeof viewer === 'string' ? viewerFor(viewer) : viewer);
}
