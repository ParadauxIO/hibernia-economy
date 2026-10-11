import 'server-only';
import type { Viewer } from '@/lib/auth/viewer';
import { ForbiddenError, UnauthorizedError } from '@/lib/errors';

export type RequiredRole = 'player' | 'government' | 'admin';

const RANK: Record<RequiredRole, number> = { player: 1, government: 2, admin: 3 };

/**
 * Throws ForbiddenError if the viewer doesn't meet the required tier.
 * Throws UnauthorizedError if the viewer is anonymous.
 *
 * Use sparingly: most pages branch on viewer state in-line (anonymous baseline
 * stays public; login only unlocks more). Reserve requireRole for pages that
 * have no anonymous variant — /me, /admin/*, /government.
 */
export function requireRole(viewer: Viewer, min: RequiredRole): asserts viewer is Extract<Viewer, { anon: false }> {
  if (viewer.anon) throw new UnauthorizedError(`This page requires sign-in.`);
  if (RANK[viewer.role === 'player' ? 'player' : viewer.role] < RANK[min]) {
    throw new ForbiddenError(`This page requires ${min} access.`);
  }
}
