import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { DocsSidebar } from '@/components/docs/DocsSidebar';
import type { DocsNavGroup } from '@/lib/docs';
import { navigation, resetNavigation } from './next-navigation';
import { setViewer, viewerWithPermissions, type TestRole } from './viewer';

vi.mock('next/navigation', () => import('./next-navigation'));

const TREE: DocsNavGroup[] = [
  {
    title: null,
    href: null,
    order: 0,
    items: [
      { title: 'Overview', href: '/docs', order: 0 },
      { title: 'Getting started', href: '/docs/getting-started', order: 1 },
    ],
  },
  {
    title: 'API',
    href: '/docs/api',
    order: 1,
    items: [{ title: 'Authentication', href: '/docs/api/authentication', order: 1 }],
  },
  {
    title: 'Guides',
    href: null,
    order: 2,
    items: [{ title: 'Webhooks', href: '/docs/guides/webhooks', order: 1 }],
  },
];

const ADMIN_LINKS = ['Managing Explorer access', 'Operator commands', 'Government accounts', 'Configuration'];

function docsNav(): HTMLElement {
  return screen.getByRole('navigation', { name: 'Documentation' });
}

beforeEach(() => resetNavigation('/docs'));

describe('DocsSidebar admin section', () => {
  it.each<[TestRole, boolean]>([
    ['anon', false],
    ['player', false],
    ['moderator', false],
    ['admin', true],
  ])('for %s shows the admin docs: %s', (role, shown) => {
    setViewer(role);
    render(<DocsSidebar tree={TREE} />);
    const nav = docsNav();
    expect(within(nav).queryByRole('link', { name: 'Admin' }) !== null).toBe(shown);
    for (const title of ADMIN_LINKS) {
      expect(within(nav).queryByRole('link', { name: title }) !== null).toBe(shown);
    }
  });

  it('is gated on docs.admin.read alone', () => {
    setViewer(viewerWithPermissions(['docs.admin.read']));
    render(<DocsSidebar tree={TREE} />);
    expect(within(docsNav()).getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/docs/admin');
    expect(within(docsNav()).getByRole('link', { name: 'Operator commands' })).toHaveAttribute('href', '/docs/admin/operator-commands');
  });

  it('appends the admin section after the public tree', () => {
    setViewer('admin');
    render(<DocsSidebar tree={TREE} />);
    const sections = within(docsNav())
      .getAllByRole('link')
      .filter((a) => a.classList.contains('docs-nav-section'))
      .map((a) => a.textContent);
    expect(sections).toEqual(['API', 'Admin']);
  });
});

describe('DocsSidebar rendering', () => {
  it('renders linked and unlinked section headings and every page', () => {
    render(<DocsSidebar tree={TREE} />);
    const nav = docsNav();
    expect(within(nav).getByRole('link', { name: 'API' })).toHaveAttribute('href', '/docs/api');
    // A section without an index page is a plain heading, not a link.
    expect(within(nav).getByText('Guides').tagName).toBe('SPAN');
    expect(within(nav).queryByRole('link', { name: 'Guides' })).toBeNull();
    expect(within(nav).getAllByRole('link').map((a) => a.getAttribute('href'))).toEqual([
      '/docs',
      '/docs/getting-started',
      '/docs/api',
      '/docs/api/authentication',
      '/docs/guides/webhooks',
    ]);
  });

  it.each([
    ['/docs/api/authentication', ['Authentication']],
    ['/docs/api', ['API']],
    ['/docs', ['Overview']],
  ])('marks only the exact current page active on %s', (path, active) => {
    navigation.pathname = path;
    render(<DocsSidebar tree={TREE} />);
    const actives = within(docsNav())
      .getAllByRole('link')
      .filter((a) => a.classList.contains('active'))
      .map((a) => a.textContent);
    expect(actives).toEqual(active);
  });

  it('toggles the mobile menu and closes it when a page is chosen', async () => {
    const user = userEvent.setup();
    // No app router in jsdom: stop the anchor's default navigation (Link still runs onClick).
    const preventNavigation = (e: MouseEvent) => e.preventDefault();
    document.addEventListener('click', preventNavigation, true);
    onTestFinished(() => document.removeEventListener('click', preventNavigation, true));
    render(<DocsSidebar tree={TREE} />);
    const toggle = screen.getByRole('button', { name: 'Documentation menu' });
    const aside = toggle.closest('aside');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(aside).not.toHaveClass('open');

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(aside).toHaveClass('open');

    await user.click(within(docsNav()).getByRole('link', { name: 'Authentication' }));
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(aside).not.toHaveClass('open');

    await user.click(toggle);
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });
});
