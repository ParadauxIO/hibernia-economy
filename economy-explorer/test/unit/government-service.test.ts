import { permissionsFor } from '../role-fixtures';
import { beforeEach, expect, it, vi } from 'vitest';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { getGovernmentDashboard } from '@/lib/services/government';
import { getGovernmentAccounts, getRecentFines, getFineCategorySummary } from '@/lib/sql/government';
vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('@/lib/sql/government', () => ({ getGovernmentAccounts: vi.fn(), getRecentFines: vi.fn(), getFineCategorySummary: vi.fn() }));
vi.mock('@/lib/audit', () => ({ auditView: vi.fn() }));
const government: Viewer = { anon: false, keycloakSub: 'sub', minecraftUuid: 'uuid', minecraftName: null, linked: true, role: 'government', capabilities: ['player', 'government'], permissions: permissionsFor('player', 'government') };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getViewer).mockResolvedValue(government);
  vi.mocked(getGovernmentAccounts).mockResolvedValue([{ account_id: 7 }] as Awaited<ReturnType<typeof getGovernmentAccounts>>);
  vi.mocked(getRecentFines).mockResolvedValue([]);
  vi.mocked(getFineCategorySummary).mockResolvedValue([]);
});
it('rechecks RBAC in the service before issuing any query', async () => {
  vi.mocked(getViewer).mockResolvedValue({ ...government, role: 'player', capabilities: ['player', 'player'], permissions: permissionsFor('player', 'player') });
  await expect(getGovernmentDashboard(30)).rejects.toThrow('Forbidden');
  expect(getGovernmentAccounts).not.toHaveBeenCalled();
});
it('passes only related accounts to both detail and aggregate queries', async () => {
  await getGovernmentDashboard(30);
  expect(getGovernmentAccounts).toHaveBeenCalledWith({ playerUuid: 'uuid' });
  expect(getRecentFines).toHaveBeenCalledWith(30, 100, [7]);
  expect(getFineCategorySummary).toHaveBeenCalledWith(30, [7]);
});
it('empty relationships never turn into an unrestricted scope', async () => {
  vi.mocked(getGovernmentAccounts).mockResolvedValue([]);
  await getGovernmentDashboard(30);
  expect(getRecentFines).toHaveBeenCalledWith(30, 100, []);
});
it('moderators can investigate all government records', async () => {
  vi.mocked(getViewer).mockResolvedValue({ ...government, role: 'moderator', capabilities: ['player', 'moderator'], permissions: permissionsFor('player', 'moderator') });
  await getGovernmentDashboard(30);
  expect(getGovernmentAccounts).toHaveBeenCalledWith({ all: true });
  expect(getRecentFines).toHaveBeenCalledWith(30, 100, null);
});
