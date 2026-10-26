'use client';
// Open state and recent items for the command palette. The palette is mounted
// once by the root layout; anything (the top-bar search button, ⌘K) opens it
// through openPalette().
import { useSyncExternalStore } from 'react';

let open = false;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

export function openPalette(): void {
  if (open) return;
  open = true;
  emit();
}

export function closePalette(): void {
  if (!open) return;
  open = false;
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function usePaletteOpen(): boolean {
  return useSyncExternalStore(subscribe, () => open, () => false);
}

// ── Recent items (localStorage) ─────────────────────────────────────────────

export interface RecentItem {
  kind: 'account' | 'firm' | 'player' | 'item' | 'page';
  label: string;
  href: string;
  detail?: string | undefined;
}

export const RECENT_KEY = 'explorer.palette.recent';
const RECENT_MAX = 6;
const KINDS = new Set<RecentItem['kind']>(['account', 'firm', 'player', 'item', 'page']);

function isRecent(x: unknown): x is RecentItem {
  if (typeof x !== 'object' || x === null) return false;
  const r = x as Record<string, unknown>;
  return KINDS.has(r.kind as RecentItem['kind']) && typeof r.label === 'string' && typeof r.href === 'string'
    // Only same-origin paths: a tampered entry must never become an off-site link.
    && r.href.startsWith('/') && !r.href.startsWith('//')
    && (r.detail === undefined || typeof r.detail === 'string');
}

export function readRecent(): RecentItem[] {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter(isRecent).slice(0, RECENT_MAX) : [];
  } catch {
    return [];
  }
}

/** Put `item` first, dropping an older copy of the same destination. */
export function rememberRecent(item: RecentItem): void {
  try {
    const next = [item, ...readRecent().filter((r) => r.href !== item.href)].slice(0, RECENT_MAX);
    window.localStorage.setItem(RECENT_KEY, JSON.stringify(next));
  } catch {
    /* storage disabled or full: recents are a convenience */
  }
}

export function clearRecent(): void {
  try {
    window.localStorage.removeItem(RECENT_KEY);
  } catch {
    /* ignore */
  }
}
