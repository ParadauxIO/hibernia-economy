import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CommandPalette, SEARCH_DEBOUNCE_MS, type SearchFn } from '@/components/CommandPalette';
import { closePalette, openPalette, RECENT_KEY } from '@/components/CommandPaletteStore';
import { HeaderSearch } from '@/components/HeaderSearch';
import type { SearchResult } from '@/lib/sql/search';
import { navigation, resetNavigation } from './next-navigation';
import { setViewer } from './viewer';

vi.mock('next/navigation', () => import('./next-navigation'));
const actions = vi.hoisted(() => ({ loginAction: vi.fn(async () => {}), logoutAction: vi.fn(async () => {}) }));
vi.mock('@/lib/actions/auth', () => actions);
vi.mock('@/lib/actions/search', () => ({ searchPreview: vi.fn(async () => []) }));

const account = (id: number, label: string): SearchResult => ({
  kind: 'account', label, secondary: 'PERSONAL', account_id: id, account_type: 'PERSONAL', balance: '1234.50',
  firm_id: null, firm_name: null, player_uuid: null, player_name: null,
});
const player = (name: string): SearchResult => ({
  kind: 'player', label: name, secondary: '2025-01-02', account_id: null, account_type: null, balance: null,
  firm_id: null, firm_name: null, player_uuid: '00000000-0000-0000-0000-00000000a1ce', player_name: name,
});

function renderPalette(search: SearchFn = vi.fn(async () => [])) {
  render(<><HeaderSearch /><CommandPalette serverIp="play.example.net" currency="$" search={search} /></>);
  return search;
}

const input = () => screen.getByRole('combobox');
const options = () => within(screen.getByRole('listbox')).getAllByRole('option');
const selected = () => options().find((o) => o.getAttribute('aria-selected') === 'true');
const status = () => screen.getByRole('status');

async function type(text: string) {
  fireEvent.change(input(), { target: { value: text } });
}

async function settle(ms = SEARCH_DEBOUNCE_MS) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn(); // not in jsdom
  vi.useFakeTimers({ shouldAdvanceTime: false });
  resetNavigation();
  window.localStorage.clear();
  setViewer('player');
});

afterEach(() => {
  act(() => closePalette());
  vi.useRealTimers();
});

describe('opening', () => {
  it('opens from the top-bar button and focuses the input', () => {
    renderPalette();
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(screen.getByRole('dialog', { name: 'Search and commands' })).toHaveAttribute('open');
    expect(input()).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Search' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('toggles with Ctrl+K and Cmd+K', () => {
    renderPalette();
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    expect(screen.getByRole('combobox')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'K', metaKey: true });
    expect(screen.queryByRole('combobox')).toBeNull();
  });

  it('opens with "/" unless the user is typing in a field', () => {
    render(<><input aria-label="Other field" /><CommandPalette serverIp="x" currency="$" search={vi.fn(async () => [])} /></>);
    screen.getByLabelText('Other field').focus();
    fireEvent.keyDown(window, { key: '/' });
    expect(screen.queryByRole('combobox')).toBeNull();
    screen.getByLabelText('Other field').blur();
    fireEvent.keyDown(window, { key: '/' });
    expect(screen.getByRole('combobox')).toBeInTheDocument();
  });
});

describe('pages and actions', () => {
  it('lists pages and actions before anything is typed', () => {
    renderPalette();
    act(() => openPalette());
    const names = options().map((o) => o.textContent);
    expect(names.some((n) => n.startsWith('Statements'))).toBe(true);
    expect(names.some((n) => n.startsWith('Copy server IP'))).toBe(true);
    expect(names.some((n) => n.startsWith('Toggle theme'))).toBe(true);
    // A player can't see admin-only destinations.
    expect(names.some((n) => n.startsWith('Sales'))).toBe(false);
    expect(names.some((n) => n.startsWith('Admin'))).toBe(false);
  });

  it('filters pages by the query and opens the chosen one with Enter', async () => {
    renderPalette();
    act(() => openPalette());
    await type('statem');
    expect(selected()).toHaveTextContent('Statements');
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(navigation.router.push).toHaveBeenCalledWith('/me/statements');
    expect(screen.queryByRole('combobox')).toBeNull();
  });

  it('moves the active option with the arrow keys, wrapping at both ends', () => {
    renderPalette();
    act(() => openPalette());
    const all = options();
    expect(input()).toHaveAttribute('aria-activedescendant', all[0]!.id);
    fireEvent.keyDown(input(), { key: 'ArrowDown' });
    expect(input()).toHaveAttribute('aria-activedescendant', all[1]!.id);
    fireEvent.keyDown(input(), { key: 'ArrowUp' });
    fireEvent.keyDown(input(), { key: 'ArrowUp' });
    expect(input()).toHaveAttribute('aria-activedescendant', all.at(-1)!.id);
  });

  it('copies the server address and says so', async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderPalette();
    act(() => openPalette());
    await type('copy server');
    fireEvent.keyDown(input(), { key: 'Enter' });
    await act(async () => {});
    expect(writeText).toHaveBeenCalledWith('play.example.net');
    expect(status()).toHaveTextContent('Server address copied.');
  });

  it('toggles the theme', async () => {
    document.documentElement.setAttribute('data-mode', 'dark');
    renderPalette();
    act(() => openPalette());
    await type('toggle theme');
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(document.documentElement).toHaveAttribute('data-mode', 'light');
    expect(status()).toHaveTextContent('Switched to light mode.');
  });
});

describe('entity search', () => {
  it('waits for two characters and the 200 ms debounce', async () => {
    const search = renderPalette(vi.fn(async () => [account(42, 'Alice')]));
    act(() => openPalette());
    await type('a');
    await settle();
    expect(search).not.toHaveBeenCalled();
    expect(screen.getByText(/one more character/)).toBeInTheDocument();
    await type('al');
    await settle(SEARCH_DEBOUNCE_MS - 1);
    expect(search).not.toHaveBeenCalled();
    await settle(1);
    expect(search).toHaveBeenCalledTimes(1);
    expect(search).toHaveBeenCalledWith('al', 4, expect.any(AbortSignal));
  });

  it('only searches for the last of several quick keystrokes', async () => {
    const search = renderPalette(vi.fn(async () => []));
    act(() => openPalette());
    await type('al');
    await settle(100);
    await type('ali');
    await settle(100);
    await type('alic');
    await settle();
    expect(search).toHaveBeenCalledTimes(1);
    expect(search).toHaveBeenCalledWith('alic', 4, expect.any(AbortSignal));
  });

  it('aborts a stale request so its late answer never replaces newer results', async () => {
    const pending: { q: string; signal: AbortSignal; resolve: (r: SearchResult[]) => void }[] = [];
    const search: SearchFn = (q, _limit, signal) => new Promise((resolve) => { pending.push({ q, signal, resolve }); });
    renderPalette(search);
    act(() => openPalette());
    await type('bo');
    await settle();
    await type('alice');
    await settle();
    expect(pending.map((p) => p.q)).toEqual(['bo', 'alice']);
    expect(pending[0]!.signal.aborted).toBe(true);
    expect(pending[1]!.signal.aborted).toBe(false);
    await act(async () => { pending[1]!.resolve([account(1, 'Alice')]); });
    await act(async () => { pending[0]!.resolve([account(2, 'Bob')]); });
    const results = within(screen.getByRole('group', { name: 'Results' })).getAllByRole('option').map((o) => o.textContent);
    expect(results[0]).toContain('Alice');
    expect(results.join(' ')).not.toContain('Bob');
  });

  it('announces searching and the result count', async () => {
    let resolve!: (r: SearchResult[]) => void;
    renderPalette(() => new Promise((r) => { resolve = r; }));
    act(() => openPalette());
    await type('ali');
    expect(status()).toHaveTextContent('Searching…');
    await settle();
    await act(async () => { resolve([account(1, 'Alice'), player('Alicia')]); });
    expect(status()).toHaveTextContent('2 matches for “ali”.');
  });

  it('announces when nothing matches', async () => {
    renderPalette(vi.fn(async () => []));
    act(() => openPalette());
    await type('zzz');
    await settle();
    expect(status()).toHaveTextContent('No players, accounts or firms match “zzz”.');
  });

  it('opens an account and remembers it as a recent item', async () => {
    renderPalette(vi.fn(async () => [account(42, 'Alice')]));
    act(() => openPalette());
    await type('alice');
    await settle();
    const alice = within(screen.getByRole('group', { name: 'Results' })).getAllByRole('option')[0]!;
    expect(alice).toHaveTextContent('#42 · PERSONAL · $1,234.50');
    fireEvent.click(alice);
    expect(navigation.router.push).toHaveBeenCalledWith('/accounts/42');
    expect(JSON.parse(window.localStorage.getItem(RECENT_KEY)!)).toEqual([
      { kind: 'account', label: 'Alice', href: '/accounts/42', detail: '#42 · PERSONAL · $1,234.50' },
    ]);
    act(() => openPalette());
    const recent = within(screen.getByRole('group', { name: 'Recent' })).getAllByRole('option');
    expect(recent[0]).toHaveTextContent('Alice');
  });

  it('links players to their profile and offers full-page searches', async () => {
    renderPalette(vi.fn(async () => [player('Alice')]));
    act(() => openPalette());
    await type('alice');
    await settle();
    const results = within(screen.getByRole('group', { name: 'Results' })).getAllByRole('option');
    fireEvent.click(results[0]!);
    expect(navigation.router.push).toHaveBeenLastCalledWith('/players/Alice');
    act(() => openPalette());
    await type('alice');
    await settle();
    fireEvent.click(screen.getByRole('option', { name: /Search everything for “alice”/ }));
    expect(navigation.router.push).toHaveBeenLastCalledWith('/search?q=alice');
  });

  it('ignores tampered recent items that point off-site', () => {
    window.localStorage.setItem(RECENT_KEY, JSON.stringify([{ kind: 'account', label: 'Evil', href: '//evil.example' }, { kind: 'firm', label: 'Acme', href: '/firms/Acme' }]));
    renderPalette();
    act(() => openPalette());
    const recent = within(screen.getByRole('group', { name: 'Recent' })).getAllByRole('option').map((o) => o.textContent);
    expect(recent).toHaveLength(1);
    expect(recent[0]).toContain('Acme');
  });
});
