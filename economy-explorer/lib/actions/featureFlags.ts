'use server';
import { revalidatePath } from 'next/cache';
import { setFeatureFlag } from '@/lib/services/featureFlags';

export async function setFeatureFlagAction(input: { name: string; enabled: boolean; revision: number }) {
  try {
    await setFeatureFlag(input);
    revalidatePath('/admin/feature-flags');
    return { ok: true };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : 'Unable to change feature flag.' };
  }
}
