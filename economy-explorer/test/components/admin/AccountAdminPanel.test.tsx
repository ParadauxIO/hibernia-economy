import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountAdminPanel } from '@/app/admin/accounts/account-admin-panel';
import {
  archiveAccountAction,
  changeAccountOwnerAction,
  renameAccountAction,
  unarchiveAccountAction,
} from '@/lib/actions/admin-accounts';
import type { AccountResult } from '@/lib/treasury';
import type { Permission } from '@/lib/auth/policy';
import { navigation, resetNavigation } from '../next-navigation';
import { setViewer, viewerWithPermissions, type TestRole } from '../viewer';

vi.mock('next/navigation', () => import('../next-navigation'));
vi.mock('@/lib/actions/admin-accounts', () => ({
  renameAccountAction: vi.fn(),
  changeAccountOwnerAction: vi.fn(),
  archiveAccountAction: vi.fn(),
  unarchiveAccountAction: vi.fn(),
}));

type Result = { ok: boolean; error?: string; result?: AccountResult };

/** A promise the test resolves by hand, to observe the pending state. */
function deferred(): { promise: Promise<Result>; resolve: (r: Result) => void } {
  let resolve: (r: Result) => void = () => {};
  const promise = new Promise<Result>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

const ok: Result = { ok: true };

function button(name: string): HTMLElement {
  return screen.getByRole('button', { name });
}

beforeEach(() => {
  resetNavigation('/admin/accounts');
  for (const fn of [renameAccountAction, changeAccountOwnerAction, archiveAccountAction, unarchiveAccountAction]) {
    vi.mocked(fn).mockReset().mockResolvedValue(ok);
  }
});

describe('AccountAdminPanel permissions', () => {
  it.each<TestRole>(['anon', 'player', 'moderator'])('disables every mutation for %s', async (role) => {
    setViewer(role);
    const user = userEvent.setup();
    render(<AccountAdminPanel accountId={7} displayName="Old" archived={false} />);
    await user.clear(screen.getByRole('textbox', { name: 'Display name' }));
    await user.type(screen.getByRole('textbox', { name: 'Display name' }), 'New');
    await user.type(screen.getByRole('textbox', { name: 'New owner' }), 'Alex');
    expect(button('Rename')).toBeDisabled();
    expect(button('Change owner')).toBeDisabled();
    expect(button('Archive')).toBeDisabled();
  });

  it.each<[Permission, string]>([
    ['account.rename', 'Rename'],
    ['account.owner.change', 'Change owner'],
    ['account.archive', 'Archive'],
  ])('%s enables only %s', async (permission, enabled) => {
    setViewer(viewerWithPermissions([permission]));
    const user = userEvent.setup();
    render(<AccountAdminPanel accountId={7} displayName="Old" archived={false} />);
    await user.type(screen.getByRole('textbox', { name: 'Display name' }), '!');
    await user.type(screen.getByRole('textbox', { name: 'New owner' }), 'Alex');
    for (const name of ['Rename', 'Change owner', 'Archive']) {
      if (name === enabled) expect(button(name)).toBeEnabled();
      else expect(button(name)).toBeDisabled();
    }
  });
});

describe('AccountAdminPanel as admin', () => {
  beforeEach(() => setViewer('admin'));

  it('only allows rename when the name is non-blank and changed', async () => {
    const user = userEvent.setup();
    render(<AccountAdminPanel accountId={7} displayName="Old" archived={false} />);
    const input = screen.getByRole('textbox', { name: 'Display name' });
    expect(input).toHaveValue('Old');
    expect(button('Rename')).toBeDisabled();
    await user.clear(input);
    await user.type(input, '   ');
    expect(button('Rename')).toBeDisabled();
    await user.clear(input);
    await user.type(input, 'Treasury Reserve');
    expect(button('Rename')).toBeEnabled();
  });

  it('starts with an empty name when the account has none', () => {
    render(<AccountAdminPanel accountId={7} displayName={null} archived={false} />);
    expect(screen.getByRole('textbox', { name: 'Display name' })).toHaveValue('');
    expect(button('Rename')).toBeDisabled();
  });

  it('renames with the account id and new name, shows pending, then success and refreshes', async () => {
    const d = deferred();
    vi.mocked(renameAccountAction).mockReturnValue(d.promise);
    const user = userEvent.setup();
    render(<AccountAdminPanel accountId={7} displayName="Old" archived={false} />);
    const input = screen.getByRole('textbox', { name: 'Display name' });
    await user.clear(input);
    await user.type(input, 'Treasury Reserve');
    await user.click(button('Rename'));

    expect(renameAccountAction).toHaveBeenCalledWith(7, 'Treasury Reserve');
    // Every mutation is locked while one is in flight.
    expect(button('Rename')).toBeDisabled();
    expect(button('Archive')).toBeDisabled();

    d.resolve(ok);
    expect(await screen.findByText('Renamed.')).toHaveClass('state-ok');
    expect(navigation.router.refresh).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(button('Archive')).toBeEnabled());
  });

  it('changes owner with the raw identifier', async () => {
    const user = userEvent.setup();
    render(<AccountAdminPanel accountId={42} displayName="Acct" archived={false} />);
    expect(button('Change owner')).toBeDisabled();
    await user.type(screen.getByRole('textbox', { name: 'New owner' }), 'Notch');
    await user.click(button('Change owner'));
    expect(changeAccountOwnerAction).toHaveBeenCalledWith(42, 'Notch');
    expect(await screen.findByText('Owner changed.')).toBeInTheDocument();
  });

  it('archives an active account and unarchives an archived one', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<AccountAdminPanel accountId={9} displayName="Acct" archived={false} />);
    expect(screen.queryByRole('button', { name: 'Unarchive' })).toBeNull();
    await user.click(button('Archive'));
    expect(archiveAccountAction).toHaveBeenCalledWith(9);
    expect(await screen.findByText('Archived.')).toBeInTheDocument();

    rerender(<AccountAdminPanel accountId={9} displayName="Acct" archived />);
    expect(screen.queryByRole('button', { name: 'Archive' })).toBeNull();
    await user.click(button('Unarchive'));
    expect(unarchiveAccountAction).toHaveBeenCalledWith(9);
    expect(await screen.findByText('Unarchived.')).toBeInTheDocument();
  });

  it('shows the server error and does not refresh on failure', async () => {
    vi.mocked(archiveAccountAction).mockResolvedValue({ ok: false, error: 'Account has a non-zero balance.' });
    const user = userEvent.setup();
    render(<AccountAdminPanel accountId={9} displayName="Acct" archived={false} />);
    await user.click(button('Archive'));
    expect(await screen.findByText('Account has a non-zero balance.')).toHaveClass('state-error');
    expect(navigation.router.refresh).not.toHaveBeenCalled();
  });

  it('falls back to a generic error message', async () => {
    vi.mocked(archiveAccountAction).mockResolvedValue({ ok: false });
    const user = userEvent.setup();
    render(<AccountAdminPanel accountId={9} displayName="Acct" archived={false} />);
    await user.click(button('Archive'));
    expect(await screen.findByText('Failed.')).toHaveClass('state-error');
  });

  it('clears the previous message when a new action starts', async () => {
    vi.mocked(archiveAccountAction).mockResolvedValueOnce({ ok: false, error: 'Nope' });
    const d = deferred();
    const user = userEvent.setup();
    render(<AccountAdminPanel accountId={9} displayName="Acct" archived={false} />);
    await user.click(button('Archive'));
    await screen.findByText('Nope');
    vi.mocked(archiveAccountAction).mockReturnValue(d.promise);
    await user.click(button('Archive'));
    await waitFor(() => expect(screen.queryByText('Nope')).toBeNull());
    d.resolve(ok);
    expect(await screen.findByText('Archived.')).toBeInTheDocument();
  });
});
