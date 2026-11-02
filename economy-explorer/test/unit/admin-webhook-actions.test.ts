import { beforeEach, expect, it, vi } from 'vitest';
import { adminRotateWebhookSecretAction, adminSetWebhookUrlAction } from '@/lib/actions/webhooks';
import { getViewer } from '@/lib/auth/viewer';
import { setWebhookSecret, setWebhookUrl } from '@/lib/treasury';
import { auditMutation } from '@/lib/audit';
import { assertPublicHttpsUrl } from '@/lib/util/ssrf';
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('@/lib/treasury', () => ({ setWebhookSecret: vi.fn(), setWebhookUrl: vi.fn() }));
vi.mock('@/lib/sql/admin', () => ({ findAdminWebhook: vi.fn().mockResolvedValue(null) }));
vi.mock('@/lib/sql/me', () => ({}));
vi.mock('@/lib/sql/firm', () => ({ findFirmByDisplayName: vi.fn(), findFirmById: vi.fn(), isFirmMember: vi.fn(), hasFirmFinancialAccess: vi.fn() }));
vi.mock('@/lib/sql/ledger', () => ({ findAccount: vi.fn(), canReadAccount: vi.fn(), findTransactionAccountIds: vi.fn() }));
vi.mock('@/lib/audit', () => ({ auditMutation: vi.fn() }));
vi.mock('@/lib/util/ssrf', () => ({ assertPublicHttpsUrl: vi.fn() }));
vi.mock('next/cache', () => ({ revalidatePath: vi.fn() }));
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getViewer).mockResolvedValue({ anon: false, linked: true, keycloakSub: 'operator', minecraftUuid: '00000000-0000-0000-0000-00000000a1ce', minecraftName: null, role: 'admin', capabilities: ['admin'], permissions: ['webhooks.update.all'] });
  vi.mocked(setWebhookSecret).mockResolvedValue({ affected: 1 });
  vi.mocked(setWebhookUrl).mockResolvedValue({ affected: 1 });
  vi.mocked(assertPublicHttpsUrl).mockResolvedValue(new URL('https://example.com'));
});
it('rotates across owners and returns the new secret once without auditing it', async () => {
  const result = await adminRotateWebhookSecretAction(17);
  expect(result.secret).toMatch(/^[0-9a-f]{64}$/);
  expect(setWebhookSecret).toHaveBeenCalledWith(17, result.secret);
  expect(JSON.stringify(vi.mocked(auditMutation).mock.calls)).not.toContain(result.secret);
});
it('requires fleet permission even for a self-service webhook manager', async () => {
  const viewer = await getViewer();
  vi.mocked(getViewer).mockResolvedValue({ ...viewer, permissions: ['webhooks.manage'] });
  await expect(adminRotateWebhookSecretAction(17)).rejects.toThrow('Forbidden');
  await expect(adminSetWebhookUrlAction(17, 'https://example.com')).rejects.toThrow('Forbidden');
  expect(setWebhookSecret).not.toHaveBeenCalled();
  expect(setWebhookUrl).not.toHaveBeenCalled();
});
it('rejects unsafe URLs before mutation and does not return a secret for a missing webhook', async () => {
  vi.mocked(assertPublicHttpsUrl).mockRejectedValue(new Error('Private destination'));
  expect(await adminSetWebhookUrlAction(17, 'https://127.0.0.1')).toEqual({ ok: false, error: 'Private destination' });
  expect(setWebhookUrl).not.toHaveBeenCalled();
  vi.mocked(setWebhookSecret).mockResolvedValue({ affected: 0 });
  expect(await adminRotateWebhookSecretAction(17)).toEqual({ ok: false, error: 'Webhook not found.' });
  expect(auditMutation).not.toHaveBeenCalled();
});
