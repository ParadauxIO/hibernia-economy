import 'server-only';

// Live player count via a Minecraft server-list query. Uses the public
// mcsrvstat.us API (no key, caches server-side) rather than implementing the
// raw SLP protocol from the pod. Fails safe: on any error the footer simply
// omits the count. Override the endpoint with MC_STATUS_API.
//
// The footer is on every page, so it must never wait on a third party:
// peekServerStatus() answers from this pod's last result immediately and
// refreshes in the background at most once a minute. A pod's first request
// shows no count; the next ones do.

export interface ServerStatus {
  online: boolean;
  players: number | null;
  max: number | null;
}

const OFFLINE: ServerStatus = { online: false, players: null, max: null };
const FRESH_MS = 60_000;

export async function serverStatus(ip: string): Promise<ServerStatus> {
  const base = process.env.MC_STATUS_API?.trim() || 'https://api.mcsrvstat.us/3';
  try {
    const res = await fetch(`${base}/${encodeURIComponent(ip)}`, {
      cache: 'no-store',
      signal: AbortSignal.timeout(4000),
    });
    if (!res.ok) return OFFLINE;
    const j = (await res.json()) as { online?: boolean; players?: { online?: number; max?: number } };
    return {
      online: !!j.online,
      players: j.players?.online ?? null,
      max: j.players?.max ?? null,
    };
  } catch {
    return OFFLINE;
  }
}

const last = new Map<string, { at: number; value: ServerStatus }>();
const inflight = new Set<string>();

/** The last known status for `ip` (null before the first answer), never awaiting the network. */
export function peekServerStatus(ip: string, now = Date.now(), fetchStatus: (ip: string) => Promise<ServerStatus> = serverStatus): ServerStatus | null {
  const entry = last.get(ip);
  if ((!entry || now - entry.at >= FRESH_MS) && !inflight.has(ip)) {
    inflight.add(ip);
    fetchStatus(ip)
      .then((value) => { last.set(ip, { at: Date.now(), value }); })
      .catch(() => { /* serverStatus never throws; keep the previous value if a fake does */ })
      .finally(() => inflight.delete(ip));
  }
  return entry?.value ?? null;
}

/** Test seam. */
export function resetServerStatusForTests(): void {
  last.clear();
  inflight.clear();
}
