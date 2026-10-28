import { beforeEach, expect, it, vi } from 'vitest';
import { getViewer } from '@/lib/auth/viewer';
import { renameAccountAction, adminTransferAction, archiveAccountAction, changeAccountOwnerAction } from '@/lib/actions/admin-accounts';
import { renameAccount, adminTransfer, archiveAccount, changeAccountOwner } from '@/lib/treasury';
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('@/lib/treasury', () => ({ renameAccount: vi.fn(), adminTransfer: vi.fn(), archiveAccount: vi.fn(), unarchiveAccount: vi.fn(), changeAccountOwner: vi.fn() }));
vi.mock('@/lib/audit', () => ({ auditView: vi.fn(), auditMutation: vi.fn() }));
vi.mock('@/lib/sql/ledger', () => ({ findAccount: vi.fn(), canReadAccount: vi.fn(), findTransactionAccountIds: vi.fn(), findReadableAccountIds: vi.fn() }));
vi.mock('next/cache', () => ({ revalidatePath: vi.fn() }));
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getViewer).mockResolvedValue({ anon: false, keycloakSub: 'custom', minecraftUuid: '00000000-0000-0000-0000-00000000a1ce', minecraftName: null, linked: true, role: 'renamer', capabilities: ['renamer'], permissions: ['account.rename'] });
});
it('permits only the configured action even when other server actions are called directly', async () => {
  expect(await renameAccountAction(12, 'New name')).toMatchObject({ ok: true });
  expect(renameAccount).toHaveBeenCalledWith(12, 'New name');
  await expect(adminTransferAction({ fromAccountId: 12, toAccountId: 13, amount: '50', memo: 'forged' })).rejects.toThrow('Forbidden');
  await expect(archiveAccountAction(12)).rejects.toThrow('Forbidden');
  await expect(changeAccountOwnerAction(12, 'someone')).rejects.toThrow('Forbidden');
  expect(adminTransfer).not.toHaveBeenCalled();
  expect(archiveAccount).not.toHaveBeenCalled();
  expect(changeAccountOwner).not.toHaveBeenCalled();
});

it('denies an unlinked capability holder before an action invoked through a public page can read or mutate data', async () => {
  vi.mocked(getViewer).mockResolvedValue({ anon: false, keycloakSub: 'custom', minecraftUuid: null, minecraftName: null, linked: false, role: 'renamer', capabilities: ['renamer'], permissions: ['account.rename'] });
  await expect(renameAccountAction(12, 'New name')).rejects.toThrow('NEXT_REDIRECT');
  expect(renameAccount).not.toHaveBeenCalled();
});
