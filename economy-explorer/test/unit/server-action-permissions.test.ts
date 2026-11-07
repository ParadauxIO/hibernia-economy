import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Viewer } from '@/lib/auth/viewer';
import { getViewer } from '@/lib/auth/viewer';
import { PERMISSIONS, type Permission } from '@/lib/auth/policy';
import * as accounts from '@/lib/actions/admin-accounts';
import * as firms from '@/lib/actions/admin-firms';
import * as admin from '@/lib/actions/admin';
import * as roles from '@/lib/actions/roles';
import * as webhooks from '@/lib/actions/webhooks';

// Keep actions, linked-viewer guards and permission policy real. Replace only
// downstream services/SQL/REST and framework effects; this checks the action
// boundary independently of the services' additional authorization checks.
const boundary = vi.hoisted(() => {
  const functions = (names: string[]) => Object.fromEntries(names.map(name => [name, vi.fn()]));
  return {
    rest: functions(['adminTransfer', 'renameAccount', 'changeAccountOwner', 'archiveAccount', 'unarchiveAccount',
      'disbandFirm', 'renameFirm', 'updateFirmDetails', 'changeFirmProprietor', 'releaseFirmName', 'setFirmTaxExempt', 'syncFirmAccounts',
      'setRateLimitOverride', 'clearRateLimitOverride', 'revokeApiKey', 'rotateApiKey',
      'createWebhook', 'setWebhookActive', 'setWebhookUrl', 'setWebhookSecret', 'deleteWebhook']),
    group: functions(['updateGroupMetadata', 'createGroup', 'deleteGroup', 'getGroup', 'isManualMember',
      'setGroupCapabilities', 'setGroupSyncSource', 'setLuckpermsNode', 'addManualMember', 'removeManualMember', 'resolvePlayerUuid']),
    role: functions(['createRole', 'updateRole', 'deleteRole', 'setDirectRoleMember']),
    ledger: functions(['findAccount', 'canReadAccount', 'findTransactionAccountIds', 'findReadableAccountIds']),
    firm: functions(['getFirmHeader', 'getFirmOperationState', 'findFirmById', 'findFirmByDisplayName', 'isFirmMember', 'hasFirmFinancialAccess']),
    admin: functions(['findAdminApiKey', 'findRateLimitOverride', 'findAdminWebhook']),
    me: functions(['findAccountsForPlayer']),
    audit: functions(['auditMutation', 'auditView']),
    cache: { revalidatePath: vi.fn() },
    ssrf: { assertPublicHttpsUrl: vi.fn() },
  };
});
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('@/lib/treasury', () => boundary.rest);
vi.mock('@/lib/services/group', () => boundary.group);
vi.mock('@/lib/services/role', () => boundary.role);
vi.mock('@/lib/sql/ledger', () => boundary.ledger);
vi.mock('@/lib/sql/firm', () => boundary.firm);
vi.mock('@/lib/sql/admin', () => boundary.admin);
vi.mock('@/lib/sql/me', () => boundary.me);
vi.mock('@/lib/audit', () => boundary.audit);
vi.mock('@/lib/util/ssrf', () => boundary.ssrf);
vi.mock('next/cache', () => boundary.cache);
vi.mock('next/navigation', () => ({
  redirect: (url: string) => { throw new Error(`NEXT_REDIRECT:${url}`); },
}));

const uuid = '00000000-0000-0000-0000-00000000a1ce';
const url = 'https://hooks.example.test/events';
const role = { key: 'reader', name: 'Reader', description: null, permissions: [] };
const retryKey = '11111111-1111-4111-8111-111111111111';
const member = (permissions: readonly Permission[]): Extract<Viewer, { anon: false }> => ({
  anon: false, linked: true, keycloakSub: 'player', minecraftUuid: uuid,
  minecraftName: 'Alice', role: 'player', capabilities: ['player'], permissions,
});
const anonymous: Viewer = {
  anon: true, linked: false, keycloakSub: null, minecraftUuid: null,
  minecraftName: null, role: null, capabilities: [], permissions: [],
};

type ActionCase = {
  name: string;
  permission: Permission;
  invoke: () => Promise<{ ok: boolean; error?: string }>;
  write: ReturnType<typeof vi.fn>;
};
const cases: ActionCase[] = [
  { name: 'transfer money', permission: 'account.transfer', invoke: () => accounts.adminTransferAction({ fromAccountId: 1, toAccountId: 2, amount: '1.00', memo: 'test', idempotencyKey: retryKey }), write: boundary.rest.adminTransfer! },
  { name: 'rename account', permission: 'account.rename', invoke: () => accounts.renameAccountAction(1, 'Savings'), write: boundary.rest.renameAccount! },
  { name: 'change account owner', permission: 'account.owner.change', invoke: () => accounts.changeAccountOwnerAction(1, uuid), write: boundary.rest.changeAccountOwner! },
  { name: 'archive account', permission: 'account.archive', invoke: () => accounts.archiveAccountAction(1), write: boundary.rest.archiveAccount! },
  { name: 'unarchive account', permission: 'account.archive', invoke: () => accounts.unarchiveAccountAction(1), write: boundary.rest.unarchiveAccount! },
  { name: 'disband firm', permission: 'firm.disband', invoke: () => firms.disbandFirmAction(7, 'Acme'), write: boundary.rest.disbandFirm! },
  { name: 'rename firm', permission: 'firm.rename', invoke: () => firms.renameFirmAction(7, 'New Acme'), write: boundary.rest.renameFirm! },
  { name: 'update firm details', permission: 'firm.details.update', invoke: () => firms.updateFirmDetailsAction(7, { discordUrl: null, hqRegion: null }), write: boundary.rest.updateFirmDetails! },
  { name: 'change proprietor', permission: 'firm.owner.change', invoke: () => firms.operateFirmAction(7, { operation: 'proprietor', owner: uuid, confirmName: 'Acme' }), write: boundary.rest.changeFirmProprietor! },
  { name: 'release firm name', permission: 'firm.name.release', invoke: () => firms.operateFirmAction(7, { operation: 'release-name', confirmName: 'Acme' }), write: boundary.rest.releaseFirmName! },
  { name: 'set firm tax exemption', permission: 'firm.tax.update', invoke: () => firms.operateFirmAction(7, { operation: 'tax-exemption', exempt: true }), write: boundary.rest.setFirmTaxExempt! },
  { name: 'sync firm accounts', permission: 'firm.accounts.sync', invoke: () => firms.operateFirmAction(7, { operation: 'sync-accounts' }), write: boundary.rest.syncFirmAccounts! },
  { name: 'create group', permission: 'groups.create', invoke: () => admin.createGroupAction({ name: 'Readers', description: null }), write: boundary.group.createGroup! },
  { name: 'delete group', permission: 'groups.delete', invoke: () => admin.deleteGroupAction(3), write: boundary.group.deleteGroup! },
  { name: 'update group metadata', permission: 'groups.update', invoke: () => admin.updateGroupMetadataAction(3, { name: 'Readers', description: null }), write: boundary.group.updateGroupMetadata! },
  { name: 'assign group roles', permission: 'groups.roles.update', invoke: () => admin.setGroupCapabilitiesAction(3, ['reader']), write: boundary.group.setGroupCapabilities! },
  { name: 'set LuckPerms source', permission: 'groups.sync.update', invoke: () => admin.setLuckpermsNodeAction(3, 'group.reader'), write: boundary.group.setLuckpermsNode! },
  { name: 'set Business source', permission: 'groups.sync.update', invoke: () => admin.setGroupSyncSourceAction(3, 'business', '7'), write: boundary.group.setGroupSyncSource! },
  { name: 'add group member', permission: 'groups.members.update', invoke: () => admin.addGroupMemberAction(3, uuid), write: boundary.group.addManualMember! },
  { name: 'remove group member', permission: 'groups.members.update', invoke: () => admin.removeGroupMemberAction(3, uuid), write: boundary.group.removeManualMember! },
  { name: 'revoke API key', permission: 'api_keys.revoke', invoke: () => admin.revokeApiKeyAction(4), write: boundary.rest.revokeApiKey! },
  { name: 'rotate API key', permission: 'api_keys.rotate', invoke: () => admin.rotateApiKeyAction(4), write: boundary.rest.rotateApiKey! },
  { name: 'set rate limit', permission: 'rate_limits.update', invoke: () => admin.setRateLimitAction({ ownerUuid: uuid, multiplier: 2, note: null }), write: boundary.rest.setRateLimitOverride! },
  { name: 'clear rate limit', permission: 'rate_limits.update', invoke: () => admin.setRateLimitAction({ ownerUuid: uuid, multiplier: 1, note: null }), write: boundary.rest.clearRateLimitOverride! },
  { name: 'create role', permission: 'roles.create', invoke: () => roles.createRoleAction(role), write: boundary.role.createRole! },
  { name: 'update role', permission: 'roles.update', invoke: () => roles.updateRoleAction(role, 1), write: boundary.role.updateRole! },
  { name: 'delete role', permission: 'roles.delete', invoke: () => roles.deleteRoleAction('reader', 1), write: boundary.role.deleteRole! },
  { name: 'grant direct role', permission: 'roles.assign', invoke: () => roles.setDirectRoleMemberAction('reader', uuid, true), write: boundary.role.setDirectRoleMember! },
  { name: 'revoke direct role', permission: 'roles.assign', invoke: () => roles.setDirectRoleMemberAction('reader', uuid, false), write: boundary.role.setDirectRoleMember! },
  { name: 'create fleet webhook', permission: 'webhooks.create.all', invoke: () => webhooks.adminCreateWebhookAction({ accountId: 1, url }), write: boundary.rest.createWebhook! },
  { name: 'activate fleet webhook', permission: 'webhooks.update.all', invoke: () => webhooks.adminSetWebhookActiveAction(5, true), write: boundary.rest.setWebhookActive! },
  { name: 'update fleet webhook URL', permission: 'webhooks.update.all', invoke: () => webhooks.adminSetWebhookUrlAction(5, url), write: boundary.rest.setWebhookUrl! },
  { name: 'rotate fleet webhook secret', permission: 'webhooks.update.all', invoke: () => webhooks.adminRotateWebhookSecretAction(5), write: boundary.rest.setWebhookSecret! },
  { name: 'delete fleet webhook', permission: 'webhooks.delete.all', invoke: () => webhooks.adminDeleteWebhookAction(5), write: boundary.rest.deleteWebhook! },
  { name: 'create personal webhook', permission: 'webhooks.manage', invoke: () => webhooks.createWebhookAction({ scope: 'account', url }), write: boundary.rest.createWebhook! },
  { name: 'create firm webhook', permission: 'webhooks.manage', invoke: () => webhooks.createWebhookAction({ scope: 'firm', firmId: 7, url }), write: boundary.rest.createWebhook! },
  { name: 'activate own webhook', permission: 'webhooks.manage', invoke: () => webhooks.setWebhookActiveAction(5, true), write: boundary.rest.setWebhookActive! },
  { name: 'update own webhook URL', permission: 'webhooks.manage', invoke: () => webhooks.setWebhookUrlAction(5, url), write: boundary.rest.setWebhookUrl! },
  { name: 'rotate own webhook secret', permission: 'webhooks.manage', invoke: () => webhooks.rotateWebhookSecretAction(5), write: boundary.rest.setWebhookSecret! },
  { name: 'delete own webhook', permission: 'webhooks.manage', invoke: () => webhooks.deleteWebhookAction(5), write: boundary.rest.deleteWebhook! },
];

beforeEach(() => {
  vi.resetAllMocks();
  for (const fn of Object.values(boundary.rest)) {
    fn.mockResolvedValue({ affected: 1, txnId: 9, amount: '1.00', memo: 'test', displayName: 'Acme', accounts: [], expiresAt: '2027-01-01' });
  }
  boundary.ledger.findAccount!.mockResolvedValue({ account_id: 1, owner_uuid: uuid, account_type: 'PERSONAL', display_name: 'Savings', balance: '5.00', archived: 0 });
  boundary.firm.findFirmById!.mockResolvedValue({ firm_id: 7, display_name: 'Acme', archived: 0 });
  boundary.firm.getFirmHeader!.mockResolvedValue({ firm_id: 7, display_name: 'Acme', archived: 0 });
  boundary.firm.getFirmOperationState!.mockResolvedValue({ nameReleased: false, taxExempt: false, accounts: [] });
  boundary.firm.hasFirmFinancialAccess!.mockResolvedValue(true);
  boundary.group.getGroup!.mockResolvedValue({ capabilities: [], syncSources: [], memberCount: 0 });
  boundary.group.resolvePlayerUuid!.mockResolvedValue(uuid);
  boundary.admin.findAdminApiKey!.mockResolvedValue(null);
  boundary.admin.findAdminWebhook!.mockResolvedValue(null);
  boundary.me.findAccountsForPlayer!.mockResolvedValue([{ account_id: 1, account_type: 'PERSONAL', owner_uuid: uuid }]);
});

async function outcome(invoke: ActionCase['invoke']) {
  try { return await invoke(); }
  catch (error) {
    if (!(error instanceof Error)) throw error;
    return { ok: false, error: error.message };
  }
}

function expectNoBoundaryAccess() {
  for (const boundaryGroup of Object.values(boundary)) {
    for (const fn of Object.values(boundaryGroup)) expect(fn).not.toHaveBeenCalled();
  }
}

describe.each(cases)('$name requires $permission', ({ permission, invoke, write }) => {
  it('rejects an anonymous caller before any downstream access', async () => {
    vi.mocked(getViewer).mockResolvedValue(anonymous);
    expect(await outcome(invoke)).toMatchObject({ ok: false, error: expect.stringContaining('NEXT_REDIRECT:/api/auth/signin?') });
    expectNoBoundaryAccess();
  });

  it('rejects an unlinked caller even with every permission', async () => {
    vi.mocked(getViewer).mockResolvedValue({ ...member(PERMISSIONS), linked: false, minecraftUuid: null });
    expect(await outcome(invoke)).toMatchObject({ ok: false, error: expect.stringContaining('NEXT_REDIRECT:/link?') });
    expectNoBoundaryAccess();
  });

  it('rejects a linked caller with no permissions', async () => {
    vi.mocked(getViewer).mockResolvedValue(member([]));
    expect(await outcome(invoke)).toMatchObject({ ok: false, error: 'Forbidden.' });
    expectNoBoundaryAccess();
  });

  it('rejects every other grant, even for a viewer labelled admin', async () => {
    vi.mocked(getViewer).mockResolvedValue({ ...member(PERMISSIONS.filter(p => p !== permission)), role: 'admin', capabilities: ['admin'] });
    expect(await outcome(invoke)).toMatchObject({ ok: false, error: 'Forbidden.' });
    expectNoBoundaryAccess();
  });

  it('allows a linked player with the specific grant to reach the downstream write', async () => {
    vi.mocked(getViewer).mockResolvedValue(member([permission]));
    expect(await invoke()).toMatchObject({ ok: true });
    expect(write).toHaveBeenCalledTimes(1);
  });
});
