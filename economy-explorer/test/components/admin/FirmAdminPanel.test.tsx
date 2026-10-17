import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FirmAdminPanel } from '@/app/admin/firms/firm-admin-panel';
import { disbandFirmAction, renameFirmAction, updateFirmDetailsAction } from '@/lib/actions/admin-firms';
import type { DisbandResult, FirmResult } from '@/lib/treasury';
import type { Permission } from '@/lib/auth/policy';
import { navigation, resetNavigation } from '../next-navigation';
import { setViewer, viewerWithPermissions, type TestRole } from '../viewer';

vi.mock('next/navigation', () => import('../next-navigation'));
vi.mock('@/lib/actions/admin-firms', () => ({
  disbandFirmAction: vi.fn(),
  renameFirmAction: vi.fn(),
  updateFirmDetailsAction: vi.fn(),
}));

type Props = Parameters<typeof FirmAdminPanel>[0];
const PROPS: Props = {
  firmId: 3,
  name: 'Acme',
  totalBalance: '1234567.89',
  discordUrl: null,
  hqRegion: 'Dublin',
  disabled: false,
};

const firm: FirmResult = { firmId: 3, displayName: 'Acme Holdings', archived: false };

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
  let resolve: (v: T) => void = () => {};
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

function button(name: string): HTMLElement {
  return screen.getByRole('button', { name });
}

function input(name: string): HTMLElement {
  return screen.getByRole('textbox', { name });
}

beforeEach(() => {
  resetNavigation('/admin/firms/3');
  vi.mocked(renameFirmAction).mockReset().mockResolvedValue({ ok: true, result: firm });
  vi.mocked(updateFirmDetailsAction).mockReset().mockResolvedValue({ ok: true, result: firm });
  vi.mocked(disbandFirmAction).mockReset();
});

describe('FirmAdminPanel permissions', () => {
  async function makeAllDirty() {
    const user = userEvent.setup();
    await user.type(input('New firm name'), ' Holdings');
    await user.type(input('HQ region'), ' North');
    await user.type(input('Type firm name to confirm'), 'Acme');
    return user;
  }

  it.each<TestRole>(['anon', 'player', 'moderator'])('disables every mutation for %s', async (role) => {
    setViewer(role);
    render(<FirmAdminPanel {...PROPS} />);
    await makeAllDirty();
    expect(button('Rename')).toBeDisabled();
    expect(button('Save details')).toBeDisabled();
    expect(button('Disband')).toBeDisabled();
  });

  it.each<[Permission, string]>([
    ['firm.rename', 'Rename'],
    ['firm.details.update', 'Save details'],
    ['firm.disband', 'Disband'],
  ])('%s enables only %s', async (permission, enabled) => {
    setViewer(viewerWithPermissions([permission]));
    render(<FirmAdminPanel {...PROPS} />);
    await makeAllDirty();
    for (const name of ['Rename', 'Save details', 'Disband']) {
      if (name === enabled) expect(button(name)).toBeEnabled();
      else expect(button(name)).toBeDisabled();
    }
  });

  it('disables everything for an already-disabled firm, even for an admin', async () => {
    setViewer('admin');
    render(<FirmAdminPanel {...PROPS} disabled />);
    await makeAllDirty();
    expect(button('Rename')).toBeDisabled();
    expect(button('Save details')).toBeDisabled();
    expect(button('Disband')).toBeDisabled();
  });
});

describe('FirmAdminPanel as admin', () => {
  beforeEach(() => setViewer('admin'));

  it('states the balance that a disband will sweep', () => {
    render(<FirmAdminPanel {...PROPS} />);
    expect(screen.getByText(/Sweeps every account’s balance/)).toHaveTextContent('($1.23M) to the proprietor');
  });

  it.each([
    ['0.00', '($0.00)'],
    ['999.99', '($999.99)'],
    ['-1500', '(−$1.5K)'],
  ])('formats a %s firm balance as %s', (total, expected) => {
    render(<FirmAdminPanel {...PROPS} totalBalance={total} />);
    expect(screen.getByText(/Sweeps every account’s balance/)).toHaveTextContent(expected);
  });

  it('renames only to a changed, non-blank name, then shows the server name', async () => {
    const user = userEvent.setup();
    render(<FirmAdminPanel {...PROPS} />);
    expect(button('Rename')).toBeDisabled();
    await user.clear(input('New firm name'));
    await user.type(input('New firm name'), '  ');
    expect(button('Rename')).toBeDisabled();
    await user.clear(input('New firm name'));
    await user.type(input('New firm name'), 'Acme Holdings');
    await user.click(button('Rename'));
    expect(renameFirmAction).toHaveBeenCalledWith(3, 'Acme Holdings');
    expect(await screen.findByText('Renamed to “Acme Holdings”.')).toHaveClass('state-ok');
    expect(navigation.router.refresh).toHaveBeenCalledTimes(1);
  });

  it('submits details with trimmed values and blanks as null', async () => {
    const user = userEvent.setup();
    render(<FirmAdminPanel {...PROPS} />);
    expect(button('Save details')).toBeDisabled();
    await user.clear(input('HQ region'));
    await user.type(input('Discord URL'), '  https://discord.gg/acme  ');
    await user.click(button('Save details'));
    expect(updateFirmDetailsAction).toHaveBeenCalledWith(3, { discordUrl: 'https://discord.gg/acme', hqRegion: null });
    expect(await screen.findByText('Details updated.')).toBeInTheDocument();
  });

  it('shows Working… on every button while an action is pending', async () => {
    const d = deferred<{ ok: boolean; error?: string; result?: FirmResult }>();
    vi.mocked(updateFirmDetailsAction).mockReturnValue(d.promise);
    const user = userEvent.setup();
    render(<FirmAdminPanel {...PROPS} />);
    await user.type(input('HQ region'), ' North');
    await user.click(button('Save details'));
    expect(screen.getAllByRole('button', { name: 'Working…' })).toHaveLength(3);
    for (const b of screen.getAllByRole('button', { name: 'Working…' })) expect(b).toBeDisabled();
    d.resolve({ ok: false, error: 'Invalid Discord URL' });
    expect(await screen.findByText('Invalid Discord URL')).toHaveClass('state-error');
    expect(navigation.router.refresh).not.toHaveBeenCalled();
    // The message can paint a beat before the transition settles; the lock then lifts.
    await waitFor(() => expect(button('Save details')).toBeEnabled());
  });

  it('requires the exact firm name before disbanding', async () => {
    const user = userEvent.setup();
    render(<FirmAdminPanel {...PROPS} />);
    const confirm = input('Type firm name to confirm');
    expect(button('Disband')).toBeDisabled();
    await user.type(confirm, 'acme');
    expect(button('Disband')).toBeDisabled();
    await user.clear(confirm);
    await user.type(confirm, '  Acme  ');
    expect(button('Disband')).toBeEnabled();
  });

  it('disbands with the typed confirmation and reports the swept total', async () => {
    const result: DisbandResult = {
      firmId: 3,
      displayName: 'Acme',
      archived: true,
      accounts: [
        { accountId: 1, sweptAmount: '1000000.50', toAccountId: 99, archived: true },
        { accountId: 2, sweptAmount: '250000.25', toAccountId: 99, archived: true },
        { accountId: 3, sweptAmount: null, toAccountId: null, archived: true },
      ],
    };
    vi.mocked(disbandFirmAction).mockResolvedValue({ ok: true, result });
    const user = userEvent.setup();
    render(<FirmAdminPanel {...PROPS} />);
    await user.type(input('Type firm name to confirm'), 'Acme');
    await user.click(button('Disband'));
    expect(disbandFirmAction).toHaveBeenCalledWith(3, 'Acme');
    expect(
      await screen.findByText('Disbanded. 3 account(s) archived; swept $1.25M to the proprietor.'),
    ).toHaveClass('state-ok');
    expect(input('Type firm name to confirm')).toHaveValue('');
    expect(navigation.router.refresh).toHaveBeenCalledTimes(1);
  });

  it('reports a zero sweep when no account held money', async () => {
    vi.mocked(disbandFirmAction).mockResolvedValue({
      ok: true,
      result: { firmId: 3, displayName: 'Acme', archived: true, accounts: [] },
    });
    const user = userEvent.setup();
    render(<FirmAdminPanel {...PROPS} />);
    await user.type(input('Type firm name to confirm'), 'Acme');
    await user.click(button('Disband'));
    expect(await screen.findByText('Disbanded. 0 account(s) archived; swept $0.00 to the proprietor.')).toBeInTheDocument();
  });

  it('keeps the confirmation and shows the error when disband fails', async () => {
    vi.mocked(disbandFirmAction).mockResolvedValue({ ok: false });
    const user = userEvent.setup();
    render(<FirmAdminPanel {...PROPS} />);
    await user.type(input('Type firm name to confirm'), 'Acme');
    await user.click(button('Disband'));
    expect(await screen.findByText('Disband failed.')).toHaveClass('state-error');
    expect(input('Type firm name to confirm')).toHaveValue('Acme');
  });
});
