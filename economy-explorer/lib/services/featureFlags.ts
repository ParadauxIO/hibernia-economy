import 'server-only';
import { z } from 'zod';
import { getViewer } from '@/lib/auth/viewer';
import { requirePermission } from '@/lib/auth/access';
import { withPolicyMutation } from '@/lib/auth/delegation';
import { auditMutation, auditView } from '@/lib/audit';
import { validFlagKey } from '@/lib/featureFlagSnapshot';
import { listFeatureFlags, lockFeatureFlag, writeFeatureFlag } from '@/lib/sql/featureFlags';
import { NotFoundError } from '@/lib/errors';

export async function getFeatureFlagsForAdmin() {
  const viewer = await getViewer();
  requirePermission(viewer, 'feature_flags.read');
  await auditView(viewer, { path: '/admin/feature-flags', targetType: 'global' });
  return listFeatureFlags();
}
const inputSchema = z.object({ name: z.string().refine(validFlagKey, 'Invalid flag key.'), enabled: z.boolean(), revision: z.number().int().positive().max(2147483646) });
export async function setFeatureFlag(input: { name: string; enabled: boolean; revision: number }): Promise<void> {
  const { name, enabled, revision } = inputSchema.parse(input);
  const change = await withPolicyMutation('feature_flags.update', async (trx, actor) => {
    const before = await lockFeatureFlag(name, trx);
    if (!before) throw new NotFoundError('Unknown feature flag.');
    if (before.revision !== revision) throw new Error('This flag changed since you opened it. Refresh before saving.');
    await writeFeatureFlag(name, enabled, actor.keycloakSub ?? 'unknown', trx);
    const after = await lockFeatureFlag(name, trx);
    return { actor, before, after };
  });
  await auditMutation(change.actor, { action: 'feature_flag.update', path: '/admin/feature-flags',
    targetType: 'feature_flag', targetId: name, before: change.before, after: change.after });
}
