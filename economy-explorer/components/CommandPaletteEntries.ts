import type { AppHref } from '@/lib/routes';
// What the command palette can show, as plain data, and how a query ranks it.
// Pure (no React, no DOM) so the rules are unit-tested directly.
import type { SearchResult } from '@/lib/sql/search';
import { formatMoney } from '@/lib/money';
import { ACCOUNT_LINKS, visibleSections } from '@/lib/nav';
import type { ViewerCtx } from '@/lib/viewerSnapshot';
import type { RecentItem } from './CommandPaletteStore';

export type ActionId = 'copy-ip' | 'toggle-theme' | 'log-in' | 'log-out';

export type PaletteEntry =
  | { id: string; type: 'link'; kind: RecentItem['kind']; label: string; detail?: string | undefined; href: AppHref; keywords?: string | undefined; uuid?: string | null | undefined; recent?: boolean | undefined }
  | { id: string; type: 'action'; action: ActionId; label: string; detail?: string | undefined; keywords?: string | undefined };

export interface PaletteGroup {
  id: 'recent' | 'results' | 'pages' | 'actions' | 'search';
  label: string;
  entries: PaletteEntry[];
}

/** Every page the viewer can open from the palette, in nav order. */
export function pageEntries(viewer: ViewerCtx): PaletteEntry[] {
  const out: PaletteEntry[] = [];
  for (const section of visibleSections(viewer)) {
    const items = section.items?.filter((i) => !i.visible || i.visible(viewer));
    if (!items) {
      out.push({ id: `page:${section.href}`, type: 'link', kind: 'page', label: section.label, href: section.href });
      continue;
    }
    for (const item of items) {
      out.push({ id: `page:${item.href}`, type: 'link', kind: 'page', label: item.label, detail: section.label, href: item.href, keywords: section.label });
    }
  }
  if (viewer.loggedIn) {
    for (const link of ACCOUNT_LINKS) out.push({ id: `page:${link.href}`, type: 'link', kind: 'page', label: link.label, detail: 'Your account', href: link.href, keywords: 'me my account' });
    if (!viewer.linked) out.push({ id: 'page:/link#cta', type: 'link', kind: 'page', label: 'Link your Minecraft account', detail: 'Your account', href: '/link', keywords: 'connect minecraft' });
    if (viewer.isAdmin) out.push({ id: 'page:/admin', type: 'link', kind: 'page', label: 'Admin', detail: 'Administration', href: '/admin', keywords: 'tools' });
  }
  return out;
}

export function actionEntries(viewer: ViewerCtx, ctx: { serverIp: string; nextMode: 'light' | 'dark'; copied: boolean }): PaletteEntry[] {
  return [
    { id: 'action:copy-ip', type: 'action', action: 'copy-ip', label: 'Copy server IP', detail: ctx.copied ? 'Copied' : ctx.serverIp, keywords: 'address join play minecraft' },
    { id: 'action:toggle-theme', type: 'action', action: 'toggle-theme', label: 'Toggle theme', detail: `Switch to ${ctx.nextMode}`, keywords: 'dark light mode appearance' },
    viewer.loggedIn
      ? { id: 'action:log-out', type: 'action', action: 'log-out', label: 'Log out', keywords: 'sign out' }
      : { id: 'action:log-in', type: 'action', action: 'log-in', label: 'Log in', keywords: 'sign in' },
  ];
}

/** One searchPreview row as a palette link. */
export function resultEntry(r: SearchResult, currency: string): PaletteEntry {
  switch (r.kind) {
    case 'account': {
      const parts = [r.account_id != null ? `#${r.account_id}` : null, r.secondary, r.balance != null ? formatMoney(r.balance, { currency }) : null];
      return { id: `account:${r.account_id}`, type: 'link', kind: 'account', label: r.label, detail: parts.filter(Boolean).join(' · '), href: r.account_id != null ? `/accounts/${r.account_id}` : `/search?q=${encodeURIComponent(r.label)}` };
    }
    case 'firm':
      return { id: `firm:${r.firm_id}`, type: 'link', kind: 'firm', label: r.label, detail: r.secondary && r.secondary !== '—' ? r.secondary : undefined, href: `/firms/${encodeURIComponent(r.firm_name ?? r.label)}` };
    case 'player': {
      const name = r.player_name ?? r.label;
      return { id: `player:${r.player_uuid ?? name}`, type: 'link', kind: 'player', label: name, detail: r.secondary ? `First seen ${r.secondary}` : undefined, href: `/players/${encodeURIComponent(name)}`, uuid: r.player_uuid };
    }
  }
}

/** Searches that hand the query to a full page. Always last in the results. */
export function querySearches(query: string): PaletteEntry[] {
  const q = query.trim();
  if (!q) return [];
  return [
    { id: 'search:all', type: 'link', kind: 'page', label: `Search everything for “${q}”`, href: `/search?q=${encodeURIComponent(q)}` },
    { id: 'search:items', type: 'link', kind: 'item', label: `Find items matching “${q}”`, href: `/chestshop?q=${encodeURIComponent(q)}` },
  ];
}

/** Lower is better; null means no match. Every query word must appear. */
export function score(entry: PaletteEntry, query: string): number | null {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return 0;
  const label = entry.label.toLowerCase();
  const hay = `${label} ${(entry.detail ?? '').toLowerCase()} ${(entry.keywords ?? '').toLowerCase()}`;
  if (!words.every((w) => hay.includes(w))) return null;
  const q = words.join(' ');
  if (label === q) return 0;
  if (label.startsWith(q)) return 1;
  if (label.split(/\s+/).some((w) => w.startsWith(words[0]!))) return 2;
  if (label.includes(words[0]!)) return 3;
  return 4;
}

export function filterEntries(entries: PaletteEntry[], query: string, limit = Infinity): PaletteEntry[] {
  return entries
    .map((entry, i) => ({ entry, i, s: score(entry, query) }))
    .filter((x): x is { entry: PaletteEntry; i: number; s: number } => x.s !== null)
    .sort((a, b) => a.s - b.s || a.i - b.i)
    .slice(0, limit)
    .map((x) => x.entry);
}

export function recentEntries(recent: RecentItem[]): PaletteEntry[] {
  return recent.map((r) => ({ id: `recent:${r.href}`, type: 'link', kind: r.kind, label: r.label, detail: r.detail, href: r.href, recent: true }));
}

/** Assemble the visible groups for a query. Entities come from the server; everything else is local. */
export function buildGroups(args: { query: string; recent: PaletteEntry[]; results: PaletteEntry[]; pages: PaletteEntry[]; actions: PaletteEntry[] }): PaletteGroup[] {
  const q = args.query.trim();
  const groups: PaletteGroup[] = [];
  if (!q) {
    if (args.recent.length) groups.push({ id: 'recent', label: 'Recent', entries: args.recent });
    groups.push({ id: 'pages', label: 'Go to', entries: args.pages });
    groups.push({ id: 'actions', label: 'Actions', entries: args.actions });
    return groups;
  }
  const pages = filterEntries(args.pages, q, 6);
  const actions = filterEntries(args.actions, q);
  const results: PaletteGroup = { id: 'results', label: 'Results', entries: args.results };
  const local: PaletteGroup[] = [];
  if (pages.length) local.push({ id: 'pages', label: 'Go to', entries: pages });
  if (actions.length) local.push({ id: 'actions', label: 'Actions', entries: actions });
  // A query that names a page or action ("statem", "toggle") puts it first, so the
  // top option doesn't change when entity results arrive a moment later.
  const best = [...pages, ...actions][0];
  const localFirst = best !== undefined && (score(best, q) ?? Infinity) <= 1;
  groups.push(...(localFirst ? [...local, results] : [results, ...local]));
  groups.push({ id: 'search', label: 'Search', entries: querySearches(q) });
  return groups.filter((g) => g.entries.length > 0);
}
