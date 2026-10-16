import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LinkStartForm } from '@/app/link/link-form';
import { linkStart, type LinkStartResult } from '@/lib/actions/link';

vi.mock('@/lib/actions/link', () => ({ linkStart: vi.fn() }));

function generate(): HTMLElement {
  return screen.getByRole('button', { name: /generat/i });
}

beforeEach(() => {
  vi.mocked(linkStart).mockReset();
});

describe('LinkStartForm', () => {
  it('starts with only the generate button', () => {
    render(<LinkStartForm />);
    expect(generate()).toHaveTextContent('Generate code');
    expect(generate()).toBeEnabled();
    expect(screen.queryByText('Your code')).toBeNull();
  });

  it('shows Generating… while pending, then the code and the exact in-game command', async () => {
    let resolve: (r: LinkStartResult) => void = () => {};
    vi.mocked(linkStart).mockReturnValue(new Promise((r) => { resolve = r; }));
    const user = userEvent.setup();
    render(<LinkStartForm />);
    await user.click(generate());
    expect(linkStart).toHaveBeenCalledExactlyOnceWith();
    expect(generate()).toHaveTextContent('Generating…');
    expect(generate()).toBeDisabled();

    resolve({ ok: true, code: 'AB3DE7GH' });
    expect(await screen.findByText('AB3DE7GH')).toBeInTheDocument();
    expect(screen.getByText('/treasuryapi ui link AB3DE7GH')).toBeInTheDocument();
    expect(screen.getByText(/in-game within 5 minutes/)).toBeInTheDocument();
    await waitFor(() => expect(generate()).toHaveTextContent('Generate code'));
  });

  it('replaces the code when a new one is generated', async () => {
    vi.mocked(linkStart).mockResolvedValueOnce({ ok: true, code: 'FIRST234' }).mockResolvedValueOnce({ ok: true, code: 'SECOND56' });
    const user = userEvent.setup();
    render(<LinkStartForm />);
    await user.click(generate());
    await screen.findByText('FIRST234');
    await waitFor(() => expect(generate()).toBeEnabled());
    await user.click(generate());
    expect(await screen.findByText('SECOND56')).toBeInTheDocument();
    expect(screen.queryByText('FIRST234')).toBeNull();
  });

  it('explains when the account is already linked, without a code', async () => {
    vi.mocked(linkStart).mockResolvedValue({ ok: true, alreadyLinked: true, message: 'Already linked to Steve.' });
    const user = userEvent.setup();
    render(<LinkStartForm />);
    await user.click(generate());
    expect(await screen.findByText('Already linked to Steve.')).toBeInTheDocument();
    expect(screen.queryByText('Your code')).toBeNull();
  });

  it('shows the error', async () => {
    vi.mocked(linkStart).mockResolvedValue({ ok: false, error: 'Sign in first.' });
    const user = userEvent.setup();
    render(<LinkStartForm />);
    await user.click(generate());
    expect(await screen.findByText('Sign in first.')).toHaveClass('state-error');
    expect(screen.queryByText('Your code')).toBeNull();
  });
});
