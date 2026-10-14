import 'server-only';
import { ForbiddenError } from '@/lib/errors';
import { hasPermission, PERMISSIONS } from './policy';
import type { Viewer } from '@/lib/auth/viewer';

/** Legacy all-permissions guard. New consumers should require a specific action. */
export function requireAdmin(viewer: Viewer): asserts viewer is Extract<Viewer, { anon: false }> {
  if (viewer.anon || !PERMISSIONS.every(permission => hasPermission(viewer, permission))) {
    throw new ForbiddenError('Admin only.');
  }
}
