import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createWebhookAction } from '@/lib/actions/webhooks';
import { getViewer } from '@/lib/auth/viewer';

const boundary = vi.hoisted(() => ({
  createWebhook: vi.fn(),
  findAccountsForPlayer: vi.fn(),
  hasFirmFinancialAccess: vi.fn(),
  auditView: vi.fn(),
  revalidatePath: vi.fn(),
}));
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('@/lib/treasury', () => ({ createWebhook: boundary.createWebhook }));
vi.mock('@/lib/sql/me', () => ({ findAccountsForPlayer: boundary.findAccountsForPlayer }));
vi.mock('@/lib/sql/firm', () => ({
  hasFirmFinancialAccess: boundary.hasFirmFinancialAccess,
  findFirmByDisplayName: vi.fn(), findFirmById: vi.fn(), isFirmMember: vi.fn(),
}));
vi.mock('@/lib/sql/ledger', () => ({
  findAccount: vi.fn(), canReadAccount: vi.fn(), findTransactionAccountIds: vi.fn(),
}));
vi.mock('@/lib/sql/admin', () => ({ findAdminWebhook: vi.fn() }));
vi.mock('@/lib/audit', () => ({ auditView: boundary.auditView }));
vi.mock('next/cache', () => ({ revalidatePath: boundary.revalidatePath }));
// URL validation is independent of scope authorization, and already covered by
// ssrf.test.ts. No network or database is needed to exercise this real action.
vi.mock('@/lib/util/ssrf', () => ({ assertPublicHttpsUrl: vi.fn() }));

const uuid = '00000000-0000-0000-0000-00000000a1ce';
const other = '00000000-0000-0000-0000-000000000b0b';
const url = 'https://hooks.example.test/events';

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getViewer).mockResolvedValue({
    anon: false, linked: true, keycloakSub: 'alice', minecraftUuid: uuid,
    minecraftName: 'Alice', role: 'player', capabilities: ['player'], permissions: ['webhooks.manage'],
  });
});

describe('createWebhookAction resource scope', () => {
  it('rejects a client-supplied firm without financial access, with no subscription or secret', async () => {
    boundary.hasFirmFinancialAccess.mockResolvedValue(false);
    const result = await createWebhookAction({ scope: 'firm', firmId: 73, url });

    expect(result).toEqual({ ok: false, error: 'You do not have financial access to that firm.' });
    expect(result).not.toHaveProperty('secret');
    expect(boundary.hasFirmFinancialAccess).toHaveBeenCalledExactlyOnceWith(73, uuid);
    expect(boundary.createWebhook).not.toHaveBeenCalled();
    expect(boundary.findAccountsForPlayer).not.toHaveBeenCalled();
    expect(boundary.auditView).not.toHaveBeenCalled();
    expect(boundary.revalidatePath).not.toHaveBeenCalled();
  });

  it('creates a firm subscription for a permitted ordinary player using their authenticated identity', async () => {
    boundary.hasFirmFinancialAccess.mockResolvedValue(true);
    // Extra client fields cannot override the server-derived owner or scope.
    const input = { scope: 'firm' as const, firmId: 73, url, ownerUuid: other, accountId: 999 };
    const result = await createWebhookAction(input);

    expect(result).toEqual({ ok: true, secret: expect.stringMatching(/^[0-9a-f]{64}$/) });
    expect(boundary.hasFirmFinancialAccess).toHaveBeenCalledExactlyOnceWith(73, uuid);
    expect(boundary.createWebhook).toHaveBeenCalledExactlyOnceWith({
      ownerUuid: uuid, keyType: 'BUSINESS', accountId: null, firmId: 73, targetUrl: url, secret: result.secret,
    });
    expect(boundary.findAccountsForPlayer).not.toHaveBeenCalled();
    expect(JSON.stringify(boundary.auditView.mock.calls)).not.toContain(result.secret!);
  });

  it('selects only the authenticated player’s personal account, ignoring client account/owner/firm fields', async () => {
    boundary.findAccountsForPlayer.mockResolvedValue([
      { account_id: 90, account_type: 'PERSONAL', owner_uuid: other },
      { account_id: 91, account_type: 'BUSINESS', owner_uuid: uuid },
      { account_id: 12, account_type: 'PERSONAL', owner_uuid: uuid },
    ]);
    const input = { scope: 'account' as const, url, accountId: 999, ownerUuid: other, firmId: 73 };
    const result = await createWebhookAction(input);

    expect(result).toEqual({ ok: true, secret: expect.stringMatching(/^[0-9a-f]{64}$/) });
    expect(boundary.findAccountsForPlayer).toHaveBeenCalledExactlyOnceWith(uuid);
    expect(boundary.createWebhook).toHaveBeenCalledExactlyOnceWith({
      ownerUuid: uuid, keyType: 'PERSONAL', accountId: 12, firmId: null, targetUrl: url, secret: result.secret,
    });
    expect(boundary.hasFirmFinancialAccess).not.toHaveBeenCalled();
  });

  it('refuses an account subscription when none of the returned accounts is the player’s personal account', async () => {
    boundary.findAccountsForPlayer.mockResolvedValue([
      { account_id: 90, account_type: 'PERSONAL', owner_uuid: other },
      { account_id: 91, account_type: 'BUSINESS', owner_uuid: uuid },
    ]);
    expect(await createWebhookAction({ scope: 'account', url })).toEqual({ ok: false, error: 'You have no personal account.' });
    expect(boundary.createWebhook).not.toHaveBeenCalled();
    expect(boundary.auditView).not.toHaveBeenCalled();
    expect(boundary.revalidatePath).not.toHaveBeenCalled();
  });
});
