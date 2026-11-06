// URLs for entity pages and their hover-card previews (spec §7, DS EntityLink).
// Pure and client-safe, so server pages and client tables build the same links.

import { looksLikeUuid } from '@/lib/format';

/** A player's profile. By name when known (canonical), else by UUID (the page redirects to the name). */
export function playerHref(name: string | null | undefined, uuid?: string | null) {
  const n = name && !looksLikeUuid(name) && !/^[0-9A-F]{32}$/i.test(name) ? name : null;
  return `/players/${encodeURIComponent(n ?? uuid ?? '')}` as const;
}

export function accountHref(id: number | string) {
  return `/accounts/${id}` as const;
}

export function firmHref(name: string) {
  return `/firms/${encodeURIComponent(name)}` as const;
}

export function itemHref(itemKey: string) {
  return `/chestshop/items/${encodeURIComponent(itemKey)}` as const;
}

export type PreviewKind = 'player' | 'account' | 'firm' | 'item';

/** The JSON endpoint behind an EntityLink's hover card (app/api/preview). */
export function previewHref(kind: PreviewKind, id: string | number): string {
  return `/api/preview/${kind}/${encodeURIComponent(String(id))}`;
}

/** A player's display name: their name, or a short UUID when the directory has none yet. */
export function playerName(name: string | null | undefined, uuid: string | null | undefined): string {
  if (name && !looksLikeUuid(name) && !/^[0-9A-F]{32}$/i.test(name)) return name;
  return uuid ? `${uuid.slice(0, 8)}…` : 'Unknown player';
}
