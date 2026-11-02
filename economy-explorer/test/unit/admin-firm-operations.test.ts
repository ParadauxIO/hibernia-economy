import { beforeEach, expect, it, vi } from 'vitest';
import { getViewer } from '@/lib/auth/viewer';
import { getFirmHeader, getFirmOperationState } from '@/lib/sql/firm';
import { changeFirmProprietor, releaseFirmName, setFirmTaxExempt, syncFirmAccounts } from '@/lib/treasury';
import { operateFirmAction } from '@/lib/actions/admin-firms';
import { auditMutation } from '@/lib/audit';
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('next/cache', () => ({ revalidatePath: vi.fn() }));
vi.mock('@/lib/audit', () => ({ auditMutation: vi.fn() }));
vi.mock('@/lib/sql/firm', () => ({ findFirmByDisplayName: vi.fn(), findFirmById: vi.fn(), isFirmMember: vi.fn(), hasFirmFinancialAccess: vi.fn(), getFirmHeader: vi.fn(), getFirmOperationState: vi.fn() }));
vi.mock('@/lib/sql/ledger', () => ({ findAccount: vi.fn(), canReadAccount: vi.fn(), findTransactionAccountIds: vi.fn() }));
vi.mock('@/lib/treasury', () => ({ changeFirmProprietor: vi.fn(), releaseFirmName: vi.fn(), setFirmTaxExempt: vi.fn(), syncFirmAccounts: vi.fn() }));
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getViewer).mockResolvedValue({ anon: false, linked: true, keycloakSub: 'operator', minecraftUuid: '00000000-0000-0000-0000-00000000a1ce', minecraftName: null, role: 'custom', capabilities: [], permissions: ['firm.owner.change'] });
  vi.mocked(getFirmHeader).mockResolvedValue({ firm_id: 1, display_name: 'Acme', archived: 0, discord_url: null, hq_region: null, proprietor_uuid: 'old', proprietor_name: null, total_balance: '10.00' });
  vi.mocked(getFirmOperationState).mockResolvedValue({ nameReleased: false, taxExempt: false, accounts: [] });
  vi.mocked(changeFirmProprietor).mockResolvedValue({ firmId: 1, proprietorUuid: 'next', nameReleased: false, taxExempt: false, accountIds: [3] });
});
it('requires confirmation and the specific permission, even on direct action calls', async () => {
  expect(await operateFirmAction(1, { operation: 'proprietor', owner: 'Next', confirmName: 'wrong' })).toMatchObject({ ok: false });
  expect(changeFirmProprietor).not.toHaveBeenCalled();
  for (const input of [{ operation: 'release-name', confirmName: 'Acme' }, { operation: 'tax-exemption', exempt: true }, { operation: 'sync-accounts' }]) {
    await expect(operateFirmAction(1, input)).rejects.toThrow('Forbidden');
  }
  expect(releaseFirmName).not.toHaveBeenCalled();
  expect(setFirmTaxExempt).not.toHaveBeenCalled();
  expect(syncFirmAccounts).not.toHaveBeenCalled();
});
it('validates untrusted inputs and audits a confirmed handover', async () => {
  expect(await operateFirmAction(1, { operation: 'tax-exemption', exempt: 'false' })).toMatchObject({ ok: false });
  expect(await operateFirmAction(1, { operation: 'proprietor', owner: ' Next ', confirmName: 'Acme' })).toMatchObject({ ok: true });
  expect(changeFirmProprietor).toHaveBeenCalledWith(1, 'Next');
  expect(auditMutation).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ action: 'firm.owner.change', before: expect.objectContaining({ proprietorUuid: 'old' }), after: expect.objectContaining({ result: expect.objectContaining({ proprietorUuid: 'next', accountIds: [3] }) }) }));
});
