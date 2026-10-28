import 'server-only';
import { redirect } from 'next/navigation';
import { getViewer } from './viewer';

/** Protect market reads before entering shared caches or querying the database. */
export async function requireMarketViewer(path = '/market', search: Record<string, string | string[] | undefined> = {}) {
  const viewer = await getViewer();
  if (viewer.anon) {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(search)) {
      for (const entry of Array.isArray(value) ? value : value === undefined ? [] : [value]) query.append(key, entry);
    }
    const callbackUrl = path + (query.size ? `?${query}` : '');
    redirect(`/api/auth/signin?${new URLSearchParams({ callbackUrl })}`);
  }
  return viewer;
}
