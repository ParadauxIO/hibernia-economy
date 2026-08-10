import 'server-only';
import { ForbiddenError } from '@/lib/errors';
import type { Viewer } from '@/lib/auth/viewer';

/**
 * Assert the viewer is an authenticated admin, narrowing the type to the
 * non-anonymous variant. Throws {@link ForbiddenError} otherwise. Shared by the
 * admin server-action modules so the gate is defined once rather than copied
 * per file (ADT-152).
 */
export function requireAdmin(viewer: Viewer): asserts viewer is Extract<Viewer, { anon: false }> {
  if (viewer.anon || viewer.role !== 'admin') {
    throw new ForbiddenError('Admin only.');
  }
}
