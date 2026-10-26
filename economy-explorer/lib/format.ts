import { formatMoney, tryParseMoney, type Cents } from '@/lib/money';

// Server-safe formatters. No DOM, no React, no recharts. Mirrors the
// equivalents in treasury-ui/src/explorer/lib.tsx so a 1:1 port produces
// identical strings.

export function fmtN(n: number): string {
  return n.toLocaleString('en-US');
}

// Money formatting lives in lib/money (exact cents). These keep the old call
// signatures: DB decimal strings format exactly; numbers (chart coordinates and
// other already-lossy values) are rounded to the nearest cent first.
function toCents(s: string | number): Cents | null {
  if (typeof s === 'string') return tryParseMoney(s);
  return Number.isFinite(s) ? BigInt(Math.round(s * 100)) : null;
}

export function fmtAmt(s: string | number): string {
  const cents = toCents(s);
  return cents === null ? '—' : formatMoney(cents, { compact: true });
}

export function fmtAmtFull(s: string | number): string {
  const cents = toCents(s);
  return cents === null ? '—' : formatMoney(cents);
}

export function fmtDate(d: string): string {
  return new Date(d.length === 10 ? d + 'T00:00:00Z' : d).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  });
}

export function fmtTs(ts: string | Date | null, timeZone = 'UTC'): string {
  if (!ts) return '—';
  return new Date(ts).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZone,
  });
}

export function shortenUuid(uuid: string | null): string {
  return uuid ? uuid.slice(0, 8) + '…' : '—';
}

export function fmtPct(n: number, digits = 1): string {
  return (n * 100).toFixed(digits) + '%';
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** True when a string is a bare UUID (personal accounts default display_name to one). */
export function looksLikeUuid(s: string | null | undefined): boolean {
  return !!s && UUID_RE.test(s.trim());
}

/**
 * The best human-readable identifier for an account. Prefers a real
 * displayName (BUSINESS / GOVERNMENT / SYSTEM, or an account the player named),
 * then the resolved owner name (economy_players.current_name), then a short UUID.
 *
 * Personal accounts default their display_name to the owner's raw UUID, so a
 * UUID-shaped display_name is junk — skip it and use the resolved player name.
 */
export function accountLabel(a: {
  display_name?: string | null;
  owner_name?: string | null;
  owner_uuid?: string | null;
  account_id: number;
}): string {
  const dn = a.display_name?.trim();
  if (dn && dn.length > 0 && !UUID_RE.test(dn)) return dn;
  if (a.owner_name && a.owner_name.trim().length > 0) return a.owner_name;
  if (a.owner_uuid) return shortenUuid(a.owner_uuid);
  if (dn) return shortenUuid(dn); // display_name was a UUID, no resolved name
  return `Account #${a.account_id}`;
}
