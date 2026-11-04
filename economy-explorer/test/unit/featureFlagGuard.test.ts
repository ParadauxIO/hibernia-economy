import { expect, it, vi } from 'vitest';

vi.mock('next/server', () => ({ connection: vi.fn(async () => {}) }));
vi.mock('@/lib/sql/featureFlags', () => ({ readFlagValues: vi.fn(async () => []) }));
import { connection } from 'next/server';
import { isFeatureEnabled, requireFeatureEnabled } from '@/lib/featureFlags';

it('forces request-time evaluation and rejects direct service calls before effects', async () => {
  const initialize = vi.fn();
  const service = async () => {
    await requireFeatureEnabled('business.preview');
    initialize();
  };
  expect(await isFeatureEnabled('business.preview')).toBe(false);
  await expect(service()).rejects.toMatchObject({ code: 'FEATURE_DISABLED', status: 404 });
  expect(initialize).not.toHaveBeenCalled();
  expect(connection).toHaveBeenCalled();
});
