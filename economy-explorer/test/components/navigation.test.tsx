import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HeaderNav, HeaderSectionNav } from '@/components/HeaderNav';
import { AdminNav } from '@/components/AdminNav';
import { SectionTabs } from '@/components/SectionTabs';
import { navigation, resetNavigation } from './next-navigation';
import { setViewer, viewerFor, viewerWithPermissions, type TestRole } from './viewer';
import type { ViewerCtx } from '@/lib/viewerSnapshot';

vi.mock('next/navigation', () => import('./next-navigation'));

function linkNames(nav: HTMLElement): string[] {
  return within(nav).getAllByRole('link').map((a) => a.textContent);
}

function currentLinks(nav: HTMLElement): string[] {
  return within(nav)
    .getAllByRole('link')
    .filter((a) => a.hasAttribute('aria-current'))
    .map((a) => a.textContent);
}

const primary = () => screen.getByRole('navigation', { name: 'Primary' });

beforeEach(() => resetNavigation());

describe('HeaderNav visibility', () => {
  const PUBLIC = ['Economy', 'Directory', 'Market', 'Docs'];

  it.each<[string, ViewerCtx, string[]]>([
    ['anon', viewerFor('anon'), PUBLIC],
    ['player', viewerFor('player'), PUBLIC],
    // government.read alone is not enough: the scope must hold a GOVERNMENT account (§10.1).
    ['government reader with no account in scope', viewerFor('government', { governmentInScope: false }), PUBLIC],
    ['government reader with an account in scope', viewerFor('government', { governmentInScope: true }), ['Economy', 'Directory', 'Market', 'Government', 'Docs']],
    // government.read.all needs no scope; transactions.read opens Investigate.
    ['moderator', viewerFor('moderator'), ['Economy', 'Directory', 'Market', 'Government', 'Investigate', 'Docs']],
    ['admin', viewerFor('admin'), ['Economy', 'Directory', 'Market', 'Government', 'Investigate', 'Docs']],
  ])('shows the %s exactly the right sections', (_label, viewer, expected) => {
    setViewer(viewer);
    render(<HeaderNav />);
    expect(linkNames(primary())).toEqual(expected);
  });

  it('never puts Admin in the primary nav (it is in the account menu)', () => {
    setViewer('admin');
    render(<HeaderNav />);
    expect(within(primary()).queryByRole('link', { name: 'Admin' })).toBeNull();
  });

  it('links Government to the government workspace and Investigate to the ledger', () => {
    setViewer('admin');
    render(<HeaderNav />);
    expect(within(primary()).getByRole('link', { name: 'Government' })).toHaveAttribute('href', '/gov');
    expect(within(primary()).getByRole('link', { name: 'Investigate' })).toHaveAttribute('href', '/transactions');
    expect(within(primary()).getByRole('link', { name: 'Directory' })).toHaveAttribute('href', '/accounts');
  });

  it('renders the anonymous nav with no viewer at all', () => {
    render(<HeaderNav />);
    expect(linkNames(primary())).toEqual(PUBLIC);
  });
});

describe('HeaderNav active section', () => {
  it.each([
    ['/', 'Economy', 'page'],
    ['/economy/wealth', 'Economy', 'true'],
    ['/money-flow', 'Economy', 'true'],
    ['/accounts', 'Directory', 'page'],
    ['/accounts/42', 'Directory', 'true'],
    ['/firms/Acme%20Corp', 'Directory', 'true'],
    ['/players/Alice', 'Directory', 'true'],
    ['/chestshop/shops', 'Market', 'true'],
    ['/market/activity', 'Market', 'true'],
    ['/gov', 'Government', 'page'],
    ['/government', 'Government', 'true'],
    ['/gov/revenue', 'Government', 'true'],
    ['/transactions/9', 'Investigate', 'true'],
    ['/docs/api', 'Docs', 'true'],
  ])('marks exactly one section on %s', (path, active, current) => {
    setViewer('admin');
    navigation.pathname = path;
    render(<HeaderNav />);
    expect(currentLinks(primary())).toEqual([active]);
    expect(within(primary()).getByRole('link', { name: active })).toHaveAttribute('aria-current', current);
  });

  it.each(['/me', '/admin/roles', '/link', '/search'])('marks nothing on %s', (path) => {
    setViewer('admin');
    navigation.pathname = path;
    render(<HeaderNav />);
    expect(currentLinks(primary())).toEqual([]);
  });

  it('does not prefix-match a different section ("/accountsx" is not Directory)', () => {
    navigation.pathname = '/accountsx';
    render(<HeaderNav />);
    expect(currentLinks(primary())).toEqual([]);
  });
});

describe('HeaderSectionNav', () => {
  it.each(['/me', '/docs', '/transactions', '/government', '/admin'])('renders nothing for %s', (path) => {
    setViewer('admin');
    navigation.pathname = path;
    const { container } = render(<HeaderSectionNav />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the economy pages with the current one marked', () => {
    navigation.pathname = '/economy/taxation';
    render(<HeaderSectionNav />);
    const nav = screen.getByRole('navigation', { name: 'Economy' });
    expect(linkNames(nav)).toEqual(['Overview', 'Wealth', 'Money flow', 'Taxation']);
    expect(currentLinks(nav)).toEqual(['Taxation']);
    expect(within(nav).getByRole('link', { name: 'Taxation' })).toHaveAttribute('aria-current', 'page');
  });

  it('shows the directory with Players, Accounts and Firms', () => {
    navigation.pathname = '/accounts/42';
    render(<HeaderSectionNav />);
    const nav = screen.getByRole('navigation', { name: 'Directory' });
    expect(linkNames(nav)).toEqual(['Players', 'Accounts', 'Firms']);
    expect(currentLinks(nav)).toEqual(['Accounts']);
    expect(within(nav).getByRole('link', { name: 'Accounts' })).toHaveAttribute('aria-current', 'true');
  });

  it.each<[TestRole, boolean]>([
    ['anon', false],
    ['player', false],
    ['government', false],
    ['moderator', true],
    ['admin', true],
  ])('market pages for %s include Sales: %s', (role, hasSales) => {
    setViewer(role);
    navigation.pathname = '/market';
    render(<HeaderSectionNav />);
    const nav = screen.getByRole('navigation', { name: 'Market' });
    expect(linkNames(nav)).toEqual(['Items', 'Find shops', 'Activity', 'Market firms', ...(hasSales ? ['Sales'] : [])]);
    expect(currentLinks(nav)).toEqual(['Items']);
  });

  it('restores the orphaned market firms route', () => {
    navigation.pathname = '/chestshop/firms/1';
    render(<HeaderSectionNav />);
    const nav = screen.getByRole('navigation', { name: 'Market' });
    expect(within(nav).getByRole('link', { name: 'Market firms' })).toHaveAttribute('href', '/chestshop/firms');
    expect(currentLinks(nav)).toEqual(['Market firms']);
  });

  it('gates Sales on market.sales.read, not on the admin flag', () => {
    setViewer(viewerWithPermissions(['market.sales.read']));
    navigation.pathname = '/chestshop/sales';
    render(<HeaderSectionNav />);
    const nav = screen.getByRole('navigation', { name: 'Market' });
    expect(within(nav).getByRole('link', { name: 'Sales' })).toHaveAttribute('href', '/chestshop/sales');
    expect(currentLinks(nav)).toEqual(['Sales']);
  });

  it('treats item pages as Items', () => {
    navigation.pathname = '/chestshop/items/DIAMOND';
    render(<HeaderSectionNav />);
    expect(currentLinks(screen.getByRole('navigation', { name: 'Market' }))).toEqual(['Items']);
  });
});

describe('SectionTabs', () => {
  it('is a no-op now that the chrome owns section navigation', () => {
    navigation.pathname = '/economy/wealth';
    const { container } = render(<SectionTabs />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('AdminNav', () => {
  it.each<[TestRole, string[]]>([
    ['anon', ['Overview']],
    ['player', ['Overview']],
    ['moderator', ['Overview', 'Players', 'Transactions']],
    ['admin', ['Overview', 'Players', 'Accounts', 'Firms', 'Transactions', 'Groups', 'Roles', 'Role grants', 'API keys', 'Webhooks', 'Audit log', 'Government units']],
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
    expect(currentLinks(screen.getByRole('navigation', { name: 'Admin' }))).toEqual([active]);
  });
});
