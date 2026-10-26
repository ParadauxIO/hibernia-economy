import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HeaderAuth } from '@/components/HeaderAuth';
import { resetNavigation } from './next-navigation';
import { setViewer, viewerFor, viewerWithPermissions } from './viewer';
import { permissionsFor } from '../role-fixtures';

vi.mock('next/navigation', () => import('./next-navigation'));
const actions = vi.hoisted(() => ({ loginAction: vi.fn(async () => {}), logoutAction: vi.fn(async () => {}) }));
vi.mock('@/lib/actions/auth', () => actions);

beforeEach(() => {
  resetNavigation();
  actions.loginAction.mockClear();
  actions.logoutAction.mockClear();
});

async function openMenu() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: /Steve/ }));
  return { user, menu: screen.getByRole('menu') };
}

const itemNames = (menu: HTMLElement) => within(menu).getAllByRole('menuitem').map((el) => el.textContent);

describe('account menu', () => {
  it('shows only a quiet Log in button when logged out', () => {
    setViewer('anon');
    render(<HeaderAuth balance={<span>$9.99</span>} />);
    const login = screen.getByRole('button', { name: 'Log in' });
    expect(login).not.toHaveClass('btn-primary');
    expect(screen.queryByText('$9.99')).toBeNull();
  });

  it('shows the head, name and personal balance on the chip', () => {
    setViewer('player');
    render(<HeaderAuth balance={<span>$5,000.00</span>} />);
    const chip = screen.getByRole('button', { name: /Steve/ });
    expect(chip).toHaveAccessibleName('Steve personal balance $5,000.00');
    expect(chip).toHaveAttribute('aria-haspopup', 'menu');
    expect(chip.querySelector('img.player-head')).toHaveAttribute('src', expect.stringContaining('/api/avatar/00000000-0000-0000-0000-00000000a1ce'));
  });

  it('lists the player pages and log out, without Admin', async () => {
    setViewer('player');
    render(<HeaderAuth balance={<span>$5,000.00</span>} />);
    const { menu } = await openMenu();
    expect(itemNames(menu)).toEqual(['My finances', 'My transactions', 'Statements', 'My market', 'My firms', 'Webhooks and API', 'Link status', 'Log out']);
    expect(within(menu).getByRole('menuitem', { name: 'My finances' })).toHaveAttribute('href', '/me');
    expect(within(menu).getByRole('menuitem', { name: 'Statements' })).toHaveAttribute('href', '/me/statements');
    expect(within(menu).getByRole('menuitem', { name: 'Webhooks and API' })).toHaveAttribute('href', '/me/webhooks');
    expect(within(menu).getByRole('menuitem', { name: 'Link status' })).toHaveAttribute('href', '/link');
  });

  it('adds Admin when the viewer holds an admin tool', async () => {
    setViewer('admin');
    render(<HeaderAuth />);
    const { menu } = await openMenu();
    expect(within(menu).getByRole('menuitem', { name: 'Admin' })).toHaveAttribute('href', '/admin');
  });

  it('leads with linking for a signed-in player with no Minecraft link, and hides the balance', async () => {
    setViewer(viewerWithPermissions(permissionsFor('player'), 'player', { linked: false }));
    render(<HeaderAuth balance={<span>$5,000.00</span>} />);
    expect(screen.queryByText('$5,000.00')).toBeNull();
    const { menu } = await openMenu();
    const items = within(menu).getAllByRole('menuitem');
    expect(items[0]).toHaveTextContent('Link your Minecraft account');
    expect(items[0]).toHaveAttribute('href', '/link');
  });

  it('moves through the items with the arrow keys and closes on Escape', async () => {
    setViewer('player');
    render(<HeaderAuth />);
    const { user, menu } = await openMenu();
    const items = within(menu).getAllByRole('menuitem');
    expect(items[0]).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(items[1]).toHaveFocus();
    await user.keyboard('{End}');
    expect(items.at(-1)).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).toBeNull();
    expect(screen.getByRole('button', { name: /Steve/ })).toHaveFocus();
  });

  it('logs out through the federated logout action', async () => {
    setViewer(viewerFor('player'));
    render(<HeaderAuth />);
    const { user, menu } = await openMenu();
    await user.click(within(menu).getByRole('menuitem', { name: 'Log out' }));
    expect(actions.logoutAction).toHaveBeenCalledTimes(1);
  });
});
