import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HeaderNav } from '@/components/HeaderNav';
import { SectionTabs } from '@/components/SectionTabs';
import { AdminNav } from '@/components/AdminNav';
import { navigation, resetNavigation } from './next-navigation';
import { setViewer, viewerWithPermissions, type TestRole } from './viewer';

vi.mock('next/navigation', () => import('./next-navigation'));

function linkNames(nav: HTMLElement): string[] {
  return within(nav).getAllByRole('link').map((a) => a.textContent);
}

function activeLinks(nav: HTMLElement): string[] {
  return within(nav)
    .getAllByRole('link')
    .filter((a) => a.classList.contains('active'))
    .map((a) => a.textContent);
}

beforeEach(() => resetNavigation());

describe('HeaderNav', () => {
  const PUBLIC = ['Economy', 'Accounts', 'Firms', 'Market', 'Docs'];

  it.each<[TestRole, string[]]>([
    ['anon', PUBLIC],
    ['player', PUBLIC],
    // player.read.all and transactions.read are admin tools, so a moderator gets the
    // Admin entry (which also covers /transactions) rather than "Investigate".
    ['moderator', [...PUBLIC, 'Admin']],
    ['admin', [...PUBLIC, 'Admin']],
  ])('shows the %s viewer exactly %j', (role, expected) => {
    setViewer(role);
    render(<HeaderNav />);
    expect(linkNames(screen.getByRole('navigation', { name: 'Primary' }))).toEqual(expected);
  });

  it('shows Investigate (not Admin) for a viewer who can investigate but holds no admin tool', () => {
    setViewer({ ...viewerWithPermissions([]), canInvestigate: true, isAdmin: false });
    render(<HeaderNav />);
    const nav = screen.getByRole('navigation', { name: 'Primary' });
    expect(within(nav).getByRole('link', { name: 'Investigate' })).toHaveAttribute('href', '/transactions');
    expect(within(nav).queryByRole('link', { name: 'Admin' })).toBeNull();
  });

  it('renders the anonymous baseline while the viewer is still loading', () => {
    // setup.ts resets to ANON_VIEWER (loading: true) — no admin flash before the fetch.
    render(<HeaderNav />);
    expect(linkNames(screen.getByRole('navigation', { name: 'Primary' }))).toEqual(PUBLIC);
  });

  it.each([
    ['/', 'Economy'],
    ['/economy/wealth', 'Economy'],
    ['/money-flow', 'Economy'],
    ['/government/fines', 'Economy'],
    ['/accounts/42', 'Accounts'],
    ['/firms', 'Firms'],
    ['/chestshop/shops', 'Market'],
    ['/market/activity', 'Market'],
    ['/docs/api', 'Docs'],
    ['/transactions/9', 'Admin'],
    ['/admin/roles', 'Admin'],
  ])('highlights exactly one section for %s', (path, active) => {
    setViewer('admin');
    navigation.pathname = path;
    render(<HeaderNav />);
    expect(activeLinks(screen.getByRole('navigation', { name: 'Primary' }))).toEqual([active]);
  });

  it('highlights nothing on an unrelated page', () => {
    navigation.pathname = '/me';
    render(<HeaderNav />);
    expect(activeLinks(screen.getByRole('navigation', { name: 'Primary' }))).toEqual([]);
  });
});

describe('SectionTabs', () => {
  it('renders nothing outside a section group', () => {
    navigation.pathname = '/accounts';
    const { container } = render(<SectionTabs />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the economy tabs with the current one active', () => {
    navigation.pathname = '/economy/taxation';
    render(<SectionTabs />);
    const nav = screen.getByRole('navigation', { name: 'Section' });
    expect(linkNames(nav)).toEqual(['Overview', 'Wealth', 'Money Flow', 'Taxation', 'Government']);
    expect(activeLinks(nav)).toEqual(['Taxation']);
  });

  it.each<[TestRole, boolean]>([
    ['anon', false],
    ['player', false],
    ['moderator', true],
    ['admin', true],
  ])('market tabs for %s include Sales: %s', (role, hasSales) => {
    setViewer(role);
    navigation.pathname = '/market';
    render(<SectionTabs />);
    const nav = screen.getByRole('navigation', { name: 'Section' });
    expect(linkNames(nav)).toEqual(['Browse Items', 'Find Shops', 'Activity', ...(hasSales ? ['Sales'] : [])]);
    expect(activeLinks(nav)).toEqual(['Browse Items']);
  });

  it('gates Sales on market.sales.read, not on the admin flag', () => {
    setViewer(viewerWithPermissions(['market.sales.read']));
    navigation.pathname = '/chestshop/sales';
    render(<SectionTabs />);
    const nav = screen.getByRole('navigation', { name: 'Section' });
    expect(within(nav).getByRole('link', { name: 'Sales' })).toHaveAttribute('href', '/chestshop/sales');
    expect(activeLinks(nav)).toEqual(['Sales']);
  });
});

describe('AdminNav', () => {
  it.each<[TestRole, string[]]>([
    ['anon', ['Overview']],
    ['player', ['Overview']],
    ['moderator', ['Overview', 'Players', 'Transactions']],
    ['admin', ['Overview', 'Players', 'Accounts', 'Transactions', 'API keys', 'Groups', 'Roles', 'Firms', 'Webhooks']],
  ])('lists only the tools the %s viewer holds', (role, expected) => {
    setViewer(role);
    navigation.pathname = '/admin';
    render(<AdminNav />);
    expect(linkNames(screen.getByRole('navigation', { name: 'Admin' }))).toEqual(expected);
  });

  it('shows a single tool for a single permission', () => {
    setViewer(viewerWithPermissions(['webhooks.read.all']));
    render(<AdminNav />);
    const nav = screen.getByRole('navigation', { name: 'Admin' });
    expect(linkNames(nav)).toEqual(['Overview', 'Webhooks']);
    expect(within(nav).getByRole('link', { name: 'Webhooks' })).toHaveAttribute('href', '/admin/webhooks');
  });

  it.each([
    ['/admin', 'Overview'],
    ['/admin/groups/12', 'Groups'],
    ['/transactions/77', 'Transactions'],
  ])('marks only the active tool on %s', (path, active) => {
    setViewer('admin');
    navigation.pathname = path;
    render(<AdminNav />);
    expect(activeLinks(screen.getByRole('navigation', { name: 'Admin' }))).toEqual([active]);
  });
});
