import 'server-only';
import { getViewer } from '@/lib/auth/viewer';
import { canViewAccount } from '@/lib/auth/access';
import { getAccountFirmId, findFirmById } from '@/lib/sql/firm';
export async function getAccountFirmContext(accountId: number) {
  const viewer = await getViewer();
  if (!await canViewAccount(accountId, viewer)) return null;
  const firmId = await getAccountFirmId(accountId);
  return firmId === null ? null : findFirmById(firmId);
}
