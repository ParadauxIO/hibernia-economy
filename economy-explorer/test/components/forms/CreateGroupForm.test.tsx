import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CreateGroupForm } from '@/app/admin/groups/create-group-form';
import { createGroupAction } from '@/lib/actions/admin';

vi.mock('@/lib/actions/admin', () => ({ createGroupAction: vi.fn() }));

type Result = Awaited<ReturnType<typeof createGroupAction>>;

const reload = vi.fn();

beforeEach(() => {
  vi.mocked(createGroupAction).mockReset();
  reload.mockReset();
  // jsdom can't reload; window.location's own props are non-configurable, so swap the object.
  vi.stubGlobal('location', { ...window.location, reload });
  return () => {
    vi.unstubAllGlobals();
  };
});

// The inputs are only identified by placeholder (no label / aria-label yet).
function nameInput(): HTMLElement {
  return screen.getByPlaceholderText(/group name/i);
}

function descriptionInput(): HTMLElement {
  return screen.getByPlaceholderText(/description/i);
}

function submit(): HTMLElement {
  return screen.getByRole('button', { name: /create group|creating/i });
}

describe('CreateGroupForm', () => {
  it('cannot be submitted without a non-blank name', async () => {
    const user = userEvent.setup();
    render(<CreateGroupForm />);
    expect(nameInput()).toBeRequired();
    expect(submit()).toBeDisabled();
    await user.type(nameInput(), '   ');
    expect(submit()).toBeDisabled();
    await user.type(nameInput(), 'DOC');
    expect(submit()).toBeEnabled();
  });

  it('sends the name and a null description when none is given, then reloads', async () => {
    vi.mocked(createGroupAction).mockResolvedValue({ ok: true });
    const user = userEvent.setup();
    render(<CreateGroupForm />);
    await user.type(nameInput(), 'DOC');
    await user.click(submit());
    expect(createGroupAction).toHaveBeenCalledExactlyOnceWith({ name: 'DOC', description: null });
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  });

  it('sends the description when given and submits on Enter', async () => {
    vi.mocked(createGroupAction).mockResolvedValue({ ok: true });
    const user = userEvent.setup();
    render(<CreateGroupForm />);
    await user.type(descriptionInput(), 'Department of Commerce');
    await user.type(nameInput(), 'DOC{Enter}');
    expect(createGroupAction).toHaveBeenCalledExactlyOnceWith({ name: 'DOC', description: 'Department of Commerce' });
  });

  it('shows Creating… and locks the button while pending', async () => {
    let resolve: (r: Result) => void = () => {};
    vi.mocked(createGroupAction).mockReturnValue(new Promise<Result>((r) => { resolve = r; }));
    const user = userEvent.setup();
    render(<CreateGroupForm />);
    await user.type(nameInput(), 'DOC');
    await user.click(submit());
    expect(submit()).toHaveTextContent('Creating…');
    expect(submit()).toBeDisabled();
    resolve({ ok: true });
    await waitFor(() => expect(reload).toHaveBeenCalled());
  });

  it('shows the server error, keeps the input and does not reload', async () => {
    vi.mocked(createGroupAction).mockResolvedValue({ ok: false, error: 'A group named DOC already exists.' });
    const user = userEvent.setup();
    render(<CreateGroupForm />);
    await user.type(nameInput(), 'DOC');
    await user.click(submit());
    expect(await screen.findByText('A group named DOC already exists.')).toHaveClass('state-error');
    expect(nameInput()).toHaveValue('DOC');
    expect(reload).not.toHaveBeenCalled();
    await waitFor(() => expect(submit()).toHaveTextContent('Create group'));
  });

  it('shows a generic error when the server gives none, and clears it on retry', async () => {
    vi.mocked(createGroupAction).mockResolvedValueOnce({ ok: false }).mockResolvedValueOnce({ ok: true });
    const user = userEvent.setup();
    render(<CreateGroupForm />);
    await user.type(nameInput(), 'DOC');
    await user.click(submit());
    expect(await screen.findByText('Failed')).toBeInTheDocument();
    await waitFor(() => expect(submit()).toBeEnabled());
    await user.click(submit());
    await waitFor(() => expect(screen.queryByText('Failed')).toBeNull());
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  });
});
