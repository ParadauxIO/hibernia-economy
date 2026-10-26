import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LimitDialog } from '@/app/admin/api-keys/limit-dialog';
import { setRateLimitAction } from '@/lib/actions/admin';

vi.mock('@/lib/actions/admin', () => ({ setRateLimitAction: vi.fn() }));

type Result = Awaited<ReturnType<typeof setRateLimitAction>>;

const OWNER = '11111111-2222-3333-4444-555555555555';

function multiplier(): HTMLElement {
  return screen.getByRole('spinbutton');
}

function note(): HTMLElement {
  return screen.getByPlaceholderText(/why this limit/);
}

async function openDialog(currentMultiplier = '1') {
  const user = userEvent.setup();
  render(<LimitDialog ownerUuid={OWNER} ownerName="Notch" currentMultiplier={currentMultiplier} />);
  await user.click(screen.getByRole('button', { name: 'Limit' }));
  return user;
}

beforeEach(() => {
  vi.mocked(setRateLimitAction).mockReset().mockResolvedValue({ ok: true });
});

describe('LimitDialog', () => {
  it('is closed until opened, then shows the owner and current multiplier', async () => {
    render(<LimitDialog ownerUuid={OWNER} ownerName="Notch" currentMultiplier="2.5" />);
    expect(screen.queryByText(/Rate limit ·/)).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Limit' }));
    expect(screen.getByText('Rate limit · Notch')).toBeInTheDocument();
    expect(multiplier()).toHaveValue(2.5);
  });

  it('saves the parsed multiplier and the note, then closes', async () => {
    const user = await openDialog();
    await user.clear(multiplier());
    await user.type(multiplier(), '3.5');
    await user.type(note(), 'trusted bot');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(setRateLimitAction).toHaveBeenCalledExactlyOnceWith({ ownerUuid: OWNER, multiplier: 3.5, note: 'trusted bot' });
    await waitFor(() => expect(screen.queryByText(/Rate limit ·/)).toBeNull());
  });

  it('sends a null note when none is given', async () => {
    const user = await openDialog('2');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(setRateLimitAction).toHaveBeenCalledExactlyOnceWith({ ownerUuid: OWNER, multiplier: 2, note: null });
  });

  it.each([
    ['zero', '0'],
    ['negative', '-1'],
    ['empty', ''],
  ])('rejects a %s multiplier without calling the server', async (_label, value) => {
    const user = await openDialog();
    await user.clear(multiplier());
    if (value) await user.type(multiplier(), value);
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByText('Enter a positive multiplier (1 = default).')).toHaveClass('state-error');
    expect(setRateLimitAction).not.toHaveBeenCalled();
    // Still open so the admin can fix it.
    expect(screen.getByText('Rate limit · Notch')).toBeInTheDocument();
  });

  it('shows Saving… and locks both buttons while pending', async () => {
    let resolve: (r: Result) => void = () => {};
    vi.mocked(setRateLimitAction).mockReturnValue(new Promise((r) => { resolve = r; }));
    const user = await openDialog();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    resolve({ ok: true });
    await waitFor(() => expect(screen.queryByText(/Rate limit ·/)).toBeNull());
  });

  it('keeps the dialog open with the server error on failure', async () => {
    vi.mocked(setRateLimitAction).mockResolvedValue({ ok: false, error: 'Multiplier too large.' });
    const user = await openDialog();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Multiplier too large.')).toBeInTheDocument();
    expect(screen.getByText('Rate limit · Notch')).toBeInTheDocument();
  });

  it('clears a validation error after a successful save', async () => {
    const user = await openDialog();
    await user.clear(multiplier());
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByText(/Enter a positive multiplier/)).toBeInTheDocument();
    await user.type(multiplier(), '2');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByText(/Rate limit ·/)).toBeNull());
    await user.click(screen.getByRole('button', { name: 'Limit' }));
    expect(screen.queryByText(/Enter a positive multiplier/)).toBeNull();
  });

  it('closes on Cancel and on a backdrop click, but not on a click inside', async () => {
    const user = await openDialog();
    await user.click(screen.getByText('Rate limit · Notch'));
    expect(screen.getByText('Rate limit · Notch')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByText(/Rate limit ·/)).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Limit' }));
    // A click on the <dialog> element itself (not its content) is a backdrop click.
    const backdrop = document.querySelector('dialog');
    if (!backdrop) throw new Error('no dialog');
    await user.click(backdrop);
    expect(screen.queryByText(/Rate limit ·/)).toBeNull();
    expect(setRateLimitAction).not.toHaveBeenCalled();
  });
});
