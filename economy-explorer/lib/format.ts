import { CURRENCY_SYMBOL } from '@/lib/currency';

// Server-safe formatters. No DOM, no React, no recharts. Mirrors the
// equivalents in treasury-ui/src/explorer/lib.tsx so a 1:1 port produces
// identical strings.

export function fmtN(n: number): string {
  return n.toLocaleString('en-US');
}

export function fmtAmt(s: string | number): string {
  const v = typeof s === 'number' ? s : parseFloat(s);
  if (!Number.isFinite(v)) return '—';
  const abs = Math.abs(v);
  if (abs >= 1_000_000_000) return `${v < 0 ? '−' : ''}${CURRENCY_SYMBOL}${(abs / 1_000_000_000).toFixed(2)}B`;
  if (abs >= 1_000_000) return `${v < 0 ? '−' : ''}${CURRENCY_SYMBOL}${(abs / 1_000_000).toFixed(2)}M`;
  if (abs >= 1_000) return `${v < 0 ? '−' : ''}${CURRENCY_SYMBOL}${(abs / 1_000).toFixed(1)}K`;
  return `${v < 0 ? '−' : ''}${CURRENCY_SYMBOL}${abs.toFixed(2)}`;
}

export function fmtAmtFull(s: string | number): string {
  const v = typeof s === 'number' ? s : parseFloat(s);
  if (!Number.isFinite(v)) return '—';
  return (
    (v < 0 ? '−' : '') +
    CURRENCY_SYMBOL +
    Math.abs(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  );
}

export function fmtDate(d: string): string {
  return new Date(d.length === 10 ? d + 'T00:00:00Z' : d).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
  });
}

export function fmtTs(ts: string | Date | null): string {
  if (!ts) return '—';
  return new Date(ts).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
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
