import 'server-only';
import { redirect } from 'next/navigation';
import { getViewer } from './viewer';

/** Guard data entry points before entering shared caches or querying economy data. */
export async function requireLinkedViewer(path = '/', search: Record<string, string | string[] | undefined> = {}) {
  const viewer = await getViewer();
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(search)) {
    for (const entry of Array.isArray(value) ? value : value === undefined ? [] : [value]) query.append(key, entry);
  }
  const callbackUrl = path + (query.size ? `?${query}` : '');
  if (viewer.anon) redirect(`/api/auth/signin?${new URLSearchParams({ callbackUrl })}`);
  if (!viewer.linked || !viewer.minecraftUuid) redirect(`/link?${new URLSearchParams({ callbackUrl })}`);
  return viewer;
}
