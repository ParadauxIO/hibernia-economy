import 'server-only';
import type { Viewer } from './viewer';
import type { Role } from './capabilities';
import { hasCapability } from './access';
import { ForbiddenError, UnauthorizedError } from '@/lib/errors';
export type RequiredRole = Role;
/** Compatibility guard. Roles are independent, not numeric ranks. */
export function requireRole(viewer: Viewer, role: Role): asserts viewer is Extract<Viewer, { anon: false }> {
  if (viewer.anon) throw new UnauthorizedError('This page requires sign-in.');
  if (role !== 'player' && !hasCapability(viewer, role)) {
    throw new ForbiddenError(`This page requires ${role} access.`);
  }
}
