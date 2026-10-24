import { permissionsFor } from '../role-fixtures';
import { expect, it, vi } from 'vitest';
import { getViewer } from '@/lib/auth/viewer';
import * as groupDal from '@/lib/sql/group';
import { createGroup, deleteGroup, setGroupCapabilities, setLuckpermsNode, addManualMember, removeManualMember } from '@/lib/services/group';
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
// Any database access at all fails the test: the session-level permission check
// must reject before a transaction opens or a DAL function runs. (The live-grant
// re-check inside the transaction is covered against a real DB in
// test/integration/sql.test.ts.)
vi.mock('@/lib/db', () => ({
  db: new Proxy({}, { get: (_, prop) => { throw new Error(`database touched: db.${String(prop)}`); } }),
}));
vi.mock('@/lib/sql/group', () => ({
  clearSyncedMembers: vi.fn(), listGroups: vi.fn(), selectGroupById: vi.fn(), createGroup: vi.fn(),
  deleteGroup: vi.fn(), deleteGroupCapabilities: vi.fn(), insertGroupCapability: vi.fn(), setLuckpermsNode: vi.fn(),
  listGroupMembers: vi.fn(), addManualMember: vi.fn(), removeManualMember: vi.fn(), findPlayerUuidByName: vi.fn(),
}));
it('rejects every group mutation inside the service for a moderator, before any database access', async () => {
  vi.mocked(getViewer).mockResolvedValue({ anon: false, keycloakSub: 's', minecraftUuid: 'u', minecraftName: null, linked: true, role: 'moderator', capabilities: ['player', 'moderator'], permissions: permissionsFor('player', 'moderator') });
  for (const operation of [
    () => createGroup({ name: 'Admin', description: null, createdBy: 'u' }),
    () => deleteGroup(1), () => setGroupCapabilities(1, ['admin']),
    () => setLuckpermsNode(1, 'group.admin'), () => addManualMember(1, 'u', 'u'),
    () => removeManualMember(1, 'u'),
  ]) await expect(operation()).rejects.toThrow('Forbidden');
  for (const dalFunction of Object.values(groupDal)) {
    if (vi.isMockFunction(dalFunction)) expect(dalFunction).not.toHaveBeenCalled();
  }
});
