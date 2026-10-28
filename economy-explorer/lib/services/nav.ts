import 'server-only';
import { memo } from '@/lib/cache';
import { sum, toDbString } from '@/lib/money';
import { hasGovernmentAccountInScope, findPersonalBalances } from '@/lib/sql/nav';
import type { Viewer } from '@/lib/auth/viewer';
import { needsGovernmentScope, viewerSnapshot, type ViewerCtx } from '@/lib/viewerSnapshot';

// Chrome data for the root layout. Both lookups are per viewer and memoised in
// the shared cache, so a page view costs at most one cheap query for each, and
// usually none. They are presentation only: the government workspace and /me
// re-check access on every request, so a stale minute here can only show or
// hide a link, never data.
const GOV_SCOPE_MS = 60_000;
const BALANCE_MS = 30_000;
// Never show a nav decision or balance older than five minutes, even on a quiet key.
const MAX_STALE_MS = 5 * 60_000;

/** Whether the viewer's ReBAC scope holds a GOVERNMENT account. Only queried for government.read holders without .all. */
export async function governmentInScope(viewer: Viewer): Promise<boolean> {
  if (!needsGovernmentScope(viewer)) return false;
  const uuid = viewer.minecraftUuid;
  try {
    return await memo(`nav:gov-scope:${uuid}`, GOV_SCOPE_MS, () => hasGovernmentAccountInScope(uuid), { maxStaleMs: MAX_STALE_MS });
  } catch (err) {
    // Fail closed: hide the entry rather than fail the layout.
    console.warn('[nav] government scope lookup failed; hiding the Government entry:', err);
    return false;
  }
}

/** The client-safe viewer for the chrome. */
export async function chromeViewer(viewer: Viewer): Promise<ViewerCtx> {
  return viewerSnapshot(viewer, { governmentInScope: await governmentInScope(viewer) });
}

/**
 * The viewer's personal balance as a DECIMAL string: the sum of the PERSONAL
 * accounts they own, never firm or government money (§3 rule 5). null when
 * there is no linked player, they own no personal account, or the lookup fails.
 */
export async function personalBalance(viewer: Viewer): Promise<string | null> {
  if (viewer.anon || !viewer.linked || !viewer.minecraftUuid) return null;
  const uuid = viewer.minecraftUuid;
  try {
    return await memo(`nav:personal-balance:${uuid}`, BALANCE_MS, async () => {
      const balances = await findPersonalBalances(uuid);
      return balances.length === 0 ? null : toDbString(sum(balances));
    }, { maxStaleMs: MAX_STALE_MS });
  } catch (err) {
    console.warn('[nav] personal balance lookup failed:', err);
    return null;
  }
}
