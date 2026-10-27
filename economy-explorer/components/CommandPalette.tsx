'use client';
// ⌘K command palette (spec §9.1): players, accounts and firms from the
// searchPreview server action, pages and actions from lib/nav, and recent
// picks from localStorage. Mounted once by the root layout; opened by ⌘K /
// Ctrl+K, "/" outside a text field, or the top-bar search button.
//
// Accessibility: a native modal <dialog> (focus trap, inert page, Escape) with
// the APG combobox pattern inside: focus stays in the input, arrow keys move
// aria-activedescendant through a grouped listbox, Enter opens. A polite live
// region announces searching, result counts and action feedback.

import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { PlayerHead } from '@democracycraft/design-system';
import { searchPreview } from '@/lib/actions/search';
import { loginAction, logoutAction } from '@/lib/actions/auth';
import type { SearchResult } from '@/lib/sql/search';
import { useViewer } from '@/components/ViewerContext';
import { toggleMode, useModeChoice } from '@/components/ModeToggle';
import { ClockIcon, SearchIcon } from '@/components/HeaderIcons';
import { closePalette, openPalette, readRecent, rememberRecent, usePaletteOpen, type RecentItem } from './CommandPaletteStore';
import { actionEntries, buildGroups, pageEntries, recentEntries, resultEntry, type PaletteEntry } from './CommandPaletteEntries';

export const SEARCH_DEBOUNCE_MS = 200;
export const SEARCH_MIN_CHARS = 2;
const RESULTS_PER_KIND = 4;

/** The search call. Swappable in tests. */
export type SearchFn = (query: string, limit: number, signal: AbortSignal) => Promise<SearchResult[]>;
/**
 * GET /api/search/preview (the searchPreview action's twin), so a superseded
 * request is cancelled on the wire. Falls back to the action if the route fails.
 */
const defaultSearch: SearchFn = async (query, limit, signal) => {
  const res = await fetch(`/api/search/preview?q=${encodeURIComponent(query)}&limit=${limit}`, { signal });
  if (!res.ok) return searchPreview(query, limit);
  return ((await res.json()) as { results: SearchResult[] }).results;
};

type Status = 'idle' | 'loading' | 'done' | 'error';

/**
 * Debounced entity search. Each new query aborts the previous request's
 * controller, so a slow response for "al" can never overwrite the results
 * for "alice". (A server action can't be cancelled on the wire; aborting
 * discards its result. A signal-aware SearchFn also stops the request.)
 */
function useEntitySearch(query: string, search: SearchFn): { results: SearchResult[]; status: Status } {
  const [state, setState] = useState<{ query: string; results: SearchResult[]; status: Status }>({ query: '', results: [], status: 'idle' });
  const term = query.trim();
  useEffect(() => {
    if (term.length < SEARCH_MIN_CHARS) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      setState((s) => ({ query: term, results: s.query === term ? s.results : [], status: 'loading' }));
      search(term, RESULTS_PER_KIND, controller.signal).then(
        (results) => { if (!controller.signal.aborted) setState({ query: term, results, status: 'done' }); },
        () => { if (!controller.signal.aborted) setState({ query: term, results: [], status: 'error' }); },
      );
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [term, search]);
  if (term.length < SEARCH_MIN_CHARS) return { results: [], status: 'idle' };
  // Until the debounced request for this term starts, the last term's results are stale.
  if (state.query !== term) return { results: [], status: 'loading' };
  return state;
}

function isTextField(el: Element | null): boolean {
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (el as HTMLElement).isContentEditable;
}

export function CommandPalette({ serverIp, currency, search = defaultSearch }: { serverIp: string; currency: string; search?: SearchFn }) {
  const open = usePaletteOpen();
  const viewer = useViewer();
  const router = useRouter();
  const dialog = useRef<HTMLDialogElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const opener = useRef<Element | null>(null);
  const ids = useId();
  const [query, setQuery] = useState('');
  // The active option is tracked by id, so it stays put when results arrive or reorder.
  const [activeId, setActiveId] = useState<string | null>(null);
  const [recent, setRecent] = useState<RecentItem[]>([]);
  const [announcement, setAnnouncement] = useState('');
  const [copied, setCopied] = useState(false);
  const mode = useModeChoice();

  // Global shortcuts: ⌘K / Ctrl+K toggles; "/" opens when not typing.
  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        if (dialog.current?.open) closePalette();
        else openPalette();
      } else if (e.key === '/' && !e.metaKey && !e.ctrlKey && !e.altKey && !isTextField(document.activeElement) && !dialog.current?.open) {
        e.preventDefault();
        openPalette();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // Open/close the native dialog with the store. Reset per opening.
  useEffect(() => {
    const d = dialog.current;
    if (!d) return;
    if (open && !d.open) {
      opener.current = document.activeElement;
      // Each opening starts fresh.
      setQuery('');
      setActiveId(null);
      setCopied(false);
      setAnnouncement('');
      setRecent(readRecent());
      if (typeof d.showModal === 'function') d.showModal();
      else d.setAttribute('open', '');
      input.current?.focus();
    } else if (!open && d.open) {
      if (typeof d.close === 'function') d.close();
      else d.removeAttribute('open');
      // Back to whatever opened it; if that is gone (a closed menu), to the search button.
      const back = opener.current?.isConnected ? opener.current as HTMLElement : document.querySelector<HTMLElement>('.shell-search');
      back?.focus();
    }
  }, [open]);

  const { results, status } = useEntitySearch(open ? query : '', search);

  // 'system' shows whatever the OS resolved to; the content only renders while open (client side).
  const showing = mode !== 'system' ? mode
    : typeof document !== 'undefined' && document.documentElement.getAttribute('data-mode') === 'light' ? 'light' : 'dark';
  const nextMode = showing === 'dark' ? 'light' : 'dark';
  const groups = useMemo(() => {
    return buildGroups({
      query,
      recent: recentEntries(recent),
      results: results.map((r) => resultEntry(r, currency)),
      pages: pageEntries(viewer),
      actions: actionEntries(viewer, { serverIp, nextMode, copied }),
    });
  }, [query, recent, results, currency, viewer, serverIp, copied, nextMode]);

  const flat = useMemo(() => groups.flatMap((g) => g.entries), [groups]);
  const found = activeId === null ? -1 : flat.findIndex((e) => e.id === activeId);
  const active = found >= 0 ? found : 0;
  const goTo = (i: number) => setActiveId(flat[i]?.id ?? null);
  const optionId = (i: number) => `${ids}-opt-${i}`;

  // Announce state changes that aren't otherwise spoken.
  const term = query.trim();
  const entityCount = results.length;
  const liveText = announcement
    || (term.length >= SEARCH_MIN_CHARS
      ? status === 'loading' ? 'Searching…'
        : status === 'error' ? 'Search failed. Pages and actions are still listed.'
        : status === 'done' ? (entityCount === 0 ? `No players, accounts or firms match “${term}”.` : `${entityCount} ${entityCount === 1 ? 'match' : 'matches'} for “${term}”.`)
        : ''
      : '');

  // Keep the active option in view.
  useEffect(() => {
    if (!open) return;
    const el = list.current?.querySelector<HTMLElement>(`[id="${optionId(active)}"]`);
    el?.scrollIntoView({ block: 'nearest' });
  });

  const choose = useCallback((entry: PaletteEntry) => {
    if (entry.type === 'link') {
      if (entry.kind !== 'page' || entry.recent) {
        const item: RecentItem = { kind: entry.kind, label: entry.label, href: entry.href, detail: entry.detail };
        if (!entry.id.startsWith('search:')) rememberRecent(item);
      }
      closePalette();
      router.push(entry.href);
      return;
    }
    switch (entry.action) {
      case 'copy-ip':
        (navigator.clipboard as Clipboard | undefined)?.writeText(serverIp).then(
          () => { setCopied(true); setAnnouncement('Server address copied.'); },
          () => setAnnouncement(`Copy failed. The address is ${serverIp}.`),
        );
        return;
      case 'toggle-theme': {
        const now = toggleMode();
        setAnnouncement(`Switched to ${now} mode.`);
        return;
      }
      case 'log-in':
        closePalette();
        void loginAction();
        return;
      case 'log-out':
        closePalette();
        void logoutAction();
        return;
    }
  }, [router, serverIp]);

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (flat.length === 0) return;
      const step = e.key === 'ArrowDown' ? 1 : -1;
      goTo((active + step + flat.length) % flat.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const entry = flat[active];
      if (entry) choose(entry);
    } else if (e.key === 'PageDown' || e.key === 'PageUp') {
      e.preventDefault();
      const step = e.key === 'PageDown' ? 5 : -5;
      goTo(Math.max(0, Math.min(flat.length - 1, active + step)));
    }
  };

  // Each group's first option index in the flat list.
  const offsets = groups.map((_, gi) => groups.slice(0, gi).reduce((n, g) => n + g.entries.length, 0));
  return (
    <dialog
      ref={dialog}
      className="palette"
      aria-label="Search and commands"
      onCancel={(e) => { e.preventDefault(); closePalette(); }}
      onClose={() => closePalette()}
      onClick={(e) => { if (e.target === dialog.current) closePalette(); }}
    >
      {open && <>
      <div className="palette-field">
        <SearchIcon size={18} />
        <input
          ref={input}
          className="palette-input"
          type="text"
          role="combobox"
          aria-label="Search players, accounts, firms, pages and actions"
          aria-expanded={flat.length > 0}
          aria-controls={`${ids}-list`}
          aria-autocomplete="list"
          aria-activedescendant={flat.length > 0 ? optionId(active) : undefined}
          placeholder="Search players, accounts, firms, pages…"
          autoComplete="off"
          spellCheck={false}
          value={query}
          onChange={(e) => { setQuery(e.target.value); setActiveId(null); setAnnouncement(''); }}
          onKeyDown={onKeyDown}
        />
        <kbd className="shell-kbd palette-esc" aria-hidden="true">esc</kbd>
      </div>
      <div ref={list} id={`${ids}-list`} role="listbox" aria-label="Suggestions" className="palette-list">
        {groups.map((group, gi) => (
          group.entries.length === 0 ? null : (
            <div key={group.id} role="group" aria-labelledby={`${ids}-${group.id}`} className="palette-group">
              <div id={`${ids}-${group.id}`} role="presentation" className="palette-group-label">{group.label}</div>
              {group.entries.map((entry, ei) => {
                const i = (offsets[gi] ?? 0) + ei;
                return (
                  <div
                    key={entry.id}
                    id={optionId(i)}
                    role="option"
                    aria-selected={i === active}
                    className="palette-option"
                    onMouseMove={() => { if (i !== active) goTo(i); }}
                    onMouseDown={(e) => e.preventDefault() /* keep focus in the input */}
                    onClick={() => choose(entry)}
                  >
                    <EntryIcon entry={entry} />
                    <span className="palette-option-label">{entry.label}</span>
                    {entry.detail && <span className="palette-option-detail">{entry.detail}</span>}
                    <span className="palette-option-kind">{kindLabel(entry)}</span>
                  </div>
                );
              })}
            </div>
          )
        ))}
        {term.length >= SEARCH_MIN_CHARS && status === 'loading' && results.length === 0 && (
          <p className="palette-note" aria-hidden="true">Searching…</p>
        )}
        {term.length > 0 && term.length < SEARCH_MIN_CHARS && (
          <p className="palette-note">Type one more character to search players, accounts and firms.</p>
        )}
      </div>
      <div className="palette-foot" aria-hidden="true">
        <span><kbd className="shell-kbd">↑</kbd><kbd className="shell-kbd">↓</kbd> move</span>
        <span><kbd className="shell-kbd">↵</kbd> open</span>
        <span><kbd className="shell-kbd">esc</kbd> close</span>
      </div>
      </>}
      <div role="status" aria-live="polite" className="sr-only">{open ? liveText : ''}</div>
    </dialog>
  );
}

function kindLabel(entry: PaletteEntry): string {
  if (entry.type === 'action') return 'Action';
  switch (entry.kind) {
    case 'account': return 'Account';
    case 'firm': return 'Firm';
    case 'player': return 'Player';
    case 'item': return 'Market';
    case 'page': return entry.id.startsWith('search:') ? 'Search' : 'Page';
  }
}

function EntryIcon({ entry }: { entry: PaletteEntry }) {
  if (entry.type === 'link' && entry.kind === 'player' && entry.uuid) {
    return <span className="palette-option-icon"><PlayerHead uuid={entry.uuid} name={entry.label} size={18} /></span>;
  }
  if (entry.type === 'link' && entry.recent) return <span className="palette-option-icon"><ClockIcon size={16} /></span>;
  return <span className="palette-option-icon"><span className={`palette-glyph is-${entry.type === 'action' ? 'action' : entry.kind}`} /></span>;
}
