import { permissionsFor } from '../role-fixtures';
import { expect, it, vi } from 'vitest';
import { getViewer } from '@/lib/auth/viewer';
import { createGroup, deleteGroup, setGroupCapabilities, setLuckpermsNode, addManualMember, removeManualMember } from '@/lib/services/group';
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('@/lib/db', () => ({ db: {} }));
vi.mock('@/lib/sql/group', () => ({}));
it('rejects every group mutation inside the service for a moderator', async () => {
  vi.mocked(getViewer).mockResolvedValue({ anon: false, keycloakSub: 's', minecraftUuid: 'u', minecraftName: null, linked: true, role: 'moderator', capabilities: ['player', 'moderator'], permissions: permissionsFor('player', 'moderator') });
  for (const operation of [
    () => createGroup({ name: 'Admin', description: null, createdBy: 'u' }),
    () => deleteGroup(1), () => setGroupCapabilities(1, ['admin']),
    () => setLuckpermsNode(1, 'group.admin'), () => addManualMember(1, 'u', 'u'),
    () => removeManualMember(1, 'u'),
  ]) await expect(operation()).rejects.toThrow('Forbidden');
});
