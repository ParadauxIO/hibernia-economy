import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FilterBar } from '@/components/FilterBar';
import { accountsTable } from '@/lib/tables/accounts';
import { navigation, resetNavigation } from './next-navigation';

vi.mock('next/navigation', () => import('./next-navigation'));

/** The last router.push target, decoded so assertions read like the URL a user sees. */
function pushed(): { path: string; f: string[]; q: string | null; params: URLSearchParams } {
  const calls = navigation.router.push.mock.calls;
  expect(calls.length).toBeGreaterThan(0);
  const href = calls[calls.length - 1]?.[0] ?? '';
  const [path = '', query = ''] = href.split('?');
  const params = new URLSearchParams(query);
  return { path, f: params.getAll('f'), q: params.get('q'), params };
}

const COLUMNS = [
  { key: 'name', label: 'Account', optional: false },
  { key: 'type', label: 'Type', optional: true },
  { key: 'created', label: 'Created', optional: true },
];

function setup(search = '', props: Partial<Parameters<typeof FilterBar>[0]> = {}) {
  resetNavigation('/accounts', search);
  const user = userEvent.setup();
  render(<FilterBar table={accountsTable} total={1204} currency="$" counts={{ type: { personal: 900, business: 250, government: 40, system: 14 } }} {...props} />);
  return { user, search: screen.getByRole('searchbox') };
}

beforeEach(() => {
  window.localStorage.clear();
});

describe('FilterBar chips', () => {
  it('renders one chip per URL filter with readable text', () => {
    setup('f=type:in:business,government&f=balance:gt:10000&f=archived:eq:true');
    const chips = within(screen.getByRole('list', { name: 'Active filters' })).getAllByRole('listitem');
    expect(chips.map((c) => c.textContent.replace('×', '').trim())).toEqual(['Type:Business, Government', 'Balance> $10k', 'Archived']);
    expect(screen.getByRole('button', { name: 'Balance > $10k. Edit filter' })).toBeInTheDocument();
  });

  it('turns typed field:value tokens into chips as the user types', async () => {
    const { user, search } = setup('q=acme');
    await user.clear(search);
    await user.type(search, 'type:business ');
    const { f, q } = pushed();
    expect(f).toEqual(['type:in:business']);
    expect(q).toBe('acme'); // the committed search is untouched
    expect(search).toHaveValue('');
  });

  it('does not chip an unfinished token, and commits text plus tokens on Enter', async () => {
    const { user, search } = setup();
    await user.type(search, 'acme balance:>10k');
    expect(navigation.router.push).not.toHaveBeenCalled();
    await user.keyboard('{Enter}');
    const { f, q } = pushed();
    expect(f).toEqual(['balance:gt:10000']);
    expect(q).toBe('acme');
  });

  it('removes a chip with its × button and keeps the others', async () => {
    const { user } = setup('f=type:in:business&f=balance:gt:10000&sort=-created');
    await user.click(screen.getByRole('button', { name: 'Remove filter Type: Business' }));
    expect(pushed().f).toEqual(['balance:gt:10000']);
    expect(pushed().params.get('sort')).toBe('-created');
  });

  it('removes the last chip on Backspace in an empty search box', async () => {
    const { user, search } = setup('f=type:in:business&f=balance:gt:10000');
    await user.click(search);
    await user.keyboard('{Backspace}');
    expect(pushed().f).toEqual(['type:in:business']);
  });

  it('clears everything but the sort with Clear all', async () => {
    const { user } = setup('q=acme&f=type:in:business&sort=name&page=3');
    await user.click(screen.getByRole('button', { name: 'Clear all' }));
    expect(pushed().params.toString()).toBe('sort=name');
  });

  it('drops the page when a filter changes and carries unrelated params', async () => {
    const { user } = setup('f=type:in:business&page=4&tab=x');
    await user.click(screen.getByRole('button', { name: 'Remove filter Type: Business' }));
    expect(pushed().params.toString()).toBe('tab=x');
  });
});

describe('FilterBar popovers', () => {
  it('edits a chip: opens its popover with counts, applies the change, and returns focus', async () => {
    const { user } = setup('f=type:in:business');
    const chip = screen.getByRole('button', { name: 'Type: Business. Edit filter' });
    await user.click(chip);
    const dialog = screen.getByRole('dialog', { name: 'Filter by Type' });
    expect(within(dialog).getByRole('checkbox', { name: /Business/ })).toBeChecked();
    expect(within(dialog).getByText('250')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('checkbox', { name: /Government/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(pushed().f).toEqual(['type:in:business,government']);
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(chip).toHaveFocus();
  });

  it('closes on Escape and returns focus to the trigger', async () => {
    const { user } = setup('f=type:in:business');
    const chip = screen.getByRole('button', { name: 'Type: Business. Edit filter' });
    await user.click(chip);
    expect(screen.getAllByRole('checkbox')[0]).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(chip).toHaveFocus();
    expect(navigation.router.push).not.toHaveBeenCalled();
  });

  it('adds a money filter from the + Filter menu, validating the amount', async () => {
    const { user } = setup();
    const add = screen.getByRole('button', { name: 'Filter' });
    await user.click(add);
    expect(add).toHaveAttribute('aria-expanded', 'true');
    await user.click(within(screen.getByRole('dialog', { name: 'Add a filter' })).getByRole('button', { name: /^Balance/ }));
    const dialog = screen.getByRole('dialog', { name: 'Filter by Balance' });
    const value = within(dialog).getByRole('textbox', { name: 'Value' });
    expect(value).toHaveFocus();
    await user.type(value, 'lots');
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent(/amount like 10k/);
    expect(navigation.router.push).not.toHaveBeenCalled();
    await user.clear(value);
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Condition' }), 'At least');
    await user.type(value, '5k{Enter}');
    expect(pushed().f).toEqual(['balance:gte:5000']);
  });

  it('applies a date preset in one click', async () => {
    const { user } = setup();
    await user.click(screen.getByRole('button', { name: 'Filter' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Add a filter' })).getByRole('button', { name: /^Created/ }));
    await user.click(screen.getByRole('button', { name: 'Last 30 days' }));
    expect(pushed().f).toEqual(['created:gte:-30d']);
  });

  it('removes a filter from its own popover', async () => {
    const { user } = setup('f=archived:eq:true');
    await user.click(screen.getByRole('button', { name: 'Archived. Edit filter' }));
    await user.click(screen.getByRole('button', { name: 'Remove' }));
    expect(pushed().f).toEqual([]);
  });

  it('searches entities through the pluggable action and labels the chip with the name', async () => {
    const searchEntity = vi.fn(async (_field: string, text: string) => (text.startsWith('Ac') ? [{ value: '7', label: 'Acme Corp' }, { value: '9', label: 'Acorn Ltd' }] : []));
    const { user } = setup('', { searchEntity });
    await user.click(screen.getByRole('button', { name: 'Filter' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Add a filter' })).getByRole('button', { name: /^Firm/ }));
    const input = screen.getByRole('combobox', { name: 'Search' });
    await user.type(input, 'Ac');
    await screen.findByRole('option', { name: 'Acme Corp' });
    expect(searchEntity).toHaveBeenLastCalledWith('firm', 'Ac');
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('option', { name: 'Acorn Ltd' })).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Enter}');
    expect(pushed().f).toEqual(['firm:in:9']);
  });
});

describe('FilterBar quick filters, columns and summary', () => {
  it('suggests quick filters under an empty search, and hides them once filtering', async () => {
    const { user } = setup();
    const group = screen.getByRole('group', { name: 'Suggested filters' });
    await user.click(within(group).getByRole('button', { name: 'Businesses' }));
    expect(pushed().f).toEqual(['type:in:business']);
  });

  it('has no suggestions while a filter is active', () => {
    setup('f=balance:gt:1');
    expect(screen.queryByRole('group', { name: 'Suggested filters' })).toBeNull();
  });

  it('shows the count and the sort in words', () => {
    setup('sort=-created');
    expect(screen.getByRole('status')).toHaveTextContent('1,204 accounts');
    expect(screen.getByText('Sorted by created, newest first')).toBeInTheDocument();
  });

  it('can show a floor count for unbounded feeds', () => {
    resetNavigation('/accounts', '');
    render(<FilterBar table={accountsTable} total={1000} totalIsFloor />);
    expect(screen.getByRole('status')).toHaveTextContent('1,000+ accounts');
  });

  it('hides optional columns from the Columns menu and remembers the choice', async () => {
    const { user } = setup('', { columns: COLUMNS, tableId: 'accounts-table' });
    await user.click(screen.getByRole('button', { name: 'Columns' }));
    const dialog = screen.getByRole('dialog', { name: 'Columns' });
    expect(within(dialog).queryByRole('checkbox', { name: 'Account' })).toBeNull(); // not optional
    await user.click(within(dialog).getByRole('checkbox', { name: 'Created' }));
    expect(JSON.parse(window.localStorage.getItem('explorer:table-columns:accounts') ?? '[]')).toEqual(['created']);
    await waitFor(() => expect(document.querySelector('style')?.textContent).toContain('#accounts-table [data-col="created"]'));
    expect(within(dialog).getByRole('checkbox', { name: 'Created' })).not.toBeChecked();
  });

  it('ignores tampered localStorage column state', () => {
    window.localStorage.setItem('explorer:table-columns:accounts', '["name","x\\"]{}","type"]');
    setup('', { columns: COLUMNS, tableId: 'accounts-table' });
    expect(document.querySelector('style')?.textContent).toBe('#accounts-table [data-col="type"]{display:none}');
  });

  it('renders the export slot', () => {
    setup('', { exportSlot: <button type="button">CSV</button> });
    expect(screen.getByRole('button', { name: 'CSV' })).toBeInTheDocument();
  });
});
