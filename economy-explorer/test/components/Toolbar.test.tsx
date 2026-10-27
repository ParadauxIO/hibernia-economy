import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderToString } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FilterInput, FilterSelect, Toolbar } from '@/components/Toolbar';
import { navigation, resetNavigation } from './next-navigation';

vi.mock('next/navigation', () => import('./next-navigation'));

const OPTIONS = [
  { value: '', label: 'All types' },
  { value: 'PERSONAL', label: 'Personal' },
  { value: 'BUSINESS', label: 'Business' },
];

/** The single router.replace target, parsed so param order doesn't matter. */
function replacedWith(): { path: string; params: Record<string, string> } {
  expect(navigation.router.replace).toHaveBeenCalledTimes(1);
  const href = navigation.router.replace.mock.calls[0]?.[0] ?? '';
  const url = new URL(href, `https://explorer.test${navigation.pathname}`);
  const path = url.pathname;
  const query = url.search;
  return { path, params: Object.fromEntries(new URLSearchParams(query)) };
}

beforeEach(() => resetNavigation('/accounts', 'type=BUSINESS&page=4&sort=balance'));

describe('Toolbar search', () => {
  it('removes one filter while preserving sorting and the other filters', async () => {
    navigation.search = new URLSearchParams('q=acme&type=BUSINESS&page=4&sort=balance');
    const user = userEvent.setup();
    render(<Toolbar filterLabels={{ type: 'Account type' }} />);
    await user.click(screen.getByRole('button', { name: 'Remove Account type: BUSINESS filter' }));
    expect(replacedWith()).toEqual({ path: '/accounts', params: { q: 'acme', sort: 'balance' } });
  });

  it('clears search and declared filters without discarding sort or page size', async () => {
    navigation.search = new URLSearchParams('q=acme&type=BUSINESS&page=4&sort=balance&limit=25');
    const user = userEvent.setup();
    render(<Toolbar filterLabels={{ type: 'Account type' }} />);
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(replacedWith()).toEqual({ path: '/accounts', params: { sort: 'balance', limit: '25' } });
    expect(screen.getByRole('searchbox')).toHaveValue('');
  });

  it('submits with the visible search action exactly once', async () => {
    const user = userEvent.setup();
    render(<Toolbar />);
    await user.type(screen.getByRole('searchbox'), 'acme');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(replacedWith().params).toEqual({ type: 'BUSINESS', sort: 'balance', q: 'acme' });
  });
  it('seeds the box from ?q and labels it by the placeholder', () => {
    navigation.search = new URLSearchParams('q=acme');
    render(<Toolbar searchPlaceholder="Search accounts" />);
    expect(screen.getByRole('searchbox', { name: 'Search accounts' })).toHaveValue('acme');
  });

  it('falls back to a generic label', () => {
    render(<Toolbar />);
    expect(screen.getByRole('searchbox', { name: 'Search results' })).toHaveAttribute('placeholder', 'Search…');
  });

  it('does not navigate while typing', async () => {
    const user = userEvent.setup();
    render(<Toolbar />);
    await user.type(screen.getByRole('searchbox'), 'acme');
    expect(navigation.router.replace).not.toHaveBeenCalled();
  });

  it('commits on Enter, keeping other params and resetting to page 1', async () => {
    const user = userEvent.setup();
    render(<Toolbar />);
    await user.type(screen.getByRole('searchbox'), 'acme corp{Enter}');
    expect(replacedWith()).toEqual({ path: '/accounts', params: { type: 'BUSINESS', sort: 'balance', q: 'acme corp' } });
  });

  it('commits on blur when the text changed', async () => {
    const user = userEvent.setup();
    render(<Toolbar />);
    await user.type(screen.getByRole('searchbox'), 'acme');
    await user.tab();
    expect(replacedWith().params).toEqual({ type: 'BUSINESS', sort: 'balance', q: 'acme' });
  });

  it('does not navigate on blur when the text matches the URL', async () => {
    navigation.search = new URLSearchParams('q=acme&page=2');
    const user = userEvent.setup();
    render(<Toolbar />);
    await user.click(screen.getByRole('searchbox'));
    await user.tab();
    expect(navigation.router.replace).not.toHaveBeenCalled();
  });

  it('removes q when the search is cleared', async () => {
    navigation.search = new URLSearchParams('q=acme&page=2&type=BUSINESS');
    const user = userEvent.setup();
    render(<Toolbar />);
    await user.clear(screen.getByRole('searchbox'));
    await user.keyboard('{Enter}');
    expect(replacedWith().params).toEqual({ type: 'BUSINESS' });
  });

  it('resyncs the box when the URL q changes externally (back/forward)', () => {
    navigation.search = new URLSearchParams('q=old');
    const { rerender } = render(<Toolbar />);
    expect(screen.getByRole('searchbox')).toHaveValue('old');
    navigation.search = new URLSearchParams('q=new');
    rerender(<Toolbar />);
    expect(screen.getByRole('searchbox')).toHaveValue('new');
  });

  it('renders the filters and right-hand slots', () => {
    render(<Toolbar filters={<span>filter-slot</span>} right={<button type="button">Export</button>} />);
    expect(screen.getByText('filter-slot')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Export' })).toBeInTheDocument();
  });
});

describe('FilterSelect', () => {
  it('is disabled in the server render (before hydration)', () => {
    const host = document.createElement('div');
    host.innerHTML = renderToString(<FilterSelect paramKey="type" ariaLabel="Account type" options={OPTIONS} />);
    const select = host.querySelector('select');
    expect(select).toBeDisabled();
    expect(select).toHaveAttribute('aria-label', 'Account type');
  });

  it('is enabled once hydrated and reflects the URL value', () => {
    render(<FilterSelect paramKey="type" ariaLabel="Account type" options={OPTIONS} />);
    const select = screen.getByRole('combobox', { name: 'Account type' });
    expect(select).toBeEnabled();
    expect(select).toHaveValue('BUSINESS');
  });

  it('defaults to the first option when the param is absent', () => {
    navigation.search = new URLSearchParams();
    render(<FilterSelect paramKey="type" ariaLabel="Account type" options={OPTIONS} />);
    expect(screen.getByRole('combobox', { name: 'Account type' })).toHaveValue('');
  });

  it('sets the param and drops page on change', async () => {
    const user = userEvent.setup();
    render(<FilterSelect paramKey="type" ariaLabel="Account type" options={OPTIONS} />);
    await user.selectOptions(screen.getByRole('combobox', { name: 'Account type' }), 'Personal');
    expect(replacedWith()).toEqual({ path: '/accounts', params: { type: 'PERSONAL', sort: 'balance' } });
  });

  it('removes the param when the empty "all" option is chosen', async () => {
    const user = userEvent.setup();
    render(<FilterSelect paramKey="type" ariaLabel="Account type" options={OPTIONS} />);
    await user.selectOptions(screen.getByRole('combobox', { name: 'Account type' }), 'All types');
    expect(replacedWith().params).toEqual({ sort: 'balance' });
  });

  it('does not mutate the params object it was given', async () => {
    const before = navigation.search.toString();
    const user = userEvent.setup();
    render(<FilterSelect paramKey="type" ariaLabel="Account type" options={OPTIONS} />);
    await user.selectOptions(screen.getByRole('combobox', { name: 'Account type' }), 'Personal');
    expect(navigation.search.toString()).toBe(before);
  });

});

describe('FilterInput', () => {
  it('commits on Enter and blur only when the value changed', async () => {
    const user = userEvent.setup();
    render(<FilterInput paramKey="min" ariaLabel="Minimum amount" type="number" />);
    const input = screen.getByRole('spinbutton', { name: 'Minimum amount' });
    await user.click(input);
    await user.tab();
    expect(navigation.router.replace).not.toHaveBeenCalled();
    await user.type(input, '100{Enter}');
    expect(replacedWith().params).toEqual({ type: 'BUSINESS', sort: 'balance', min: '100' });
  });

  it('removes the param when emptied', async () => {
    navigation.search = new URLSearchParams('from=2026-01-01&page=3');
    const user = userEvent.setup();
    render(<FilterInput paramKey="from" ariaLabel="From date" type="date" />);
    const input = screen.getByLabelText('From date');
    expect(input).toHaveValue('2026-01-01');
    await user.clear(input);
    await user.tab();
    expect(replacedWith().params).toEqual({});
  });
});
