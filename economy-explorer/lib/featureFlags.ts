import 'server-only';
import { connection } from 'next/server';
import { FeatureFlagCache } from '@/lib/featureFlagSnapshot';
import { readFlagValues } from '@/lib/sql/featureFlags';
import { NotFoundError } from '@/lib/errors';

const flags = new FeatureFlagCache(readFlagValues);
/** Call at the service boundary on every operation, outside shared result caches. */
export async function isFeatureEnabled(key: string): Promise<boolean> {
  // Prevent build-time rendering from freezing a flag decision into a static page.
  await connection();
  return flags.isEnabled(key);
}
export class FeatureDisabledError extends NotFoundError {
  readonly code = 'FEATURE_DISABLED';
  constructor() { super('This feature is unavailable.'); this.name = 'FeatureDisabledError'; }
}
export async function requireFeatureEnabled(key: string): Promise<void> {
  if (!await isFeatureEnabled(key)) throw new FeatureDisabledError();
}
