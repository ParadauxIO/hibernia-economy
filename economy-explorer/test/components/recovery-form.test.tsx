import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
const actions = vi.hoisted(() => ({ begin: vi.fn(), check: vi.fn(), finish: vi.fn() }));
vi.mock('@/lib/actions/recovery', () => ({ beginRecovery: actions.begin, checkRecovery: actions.check, finishRecovery: actions.finish }));
vi.mock('@/lib/actions/auth', () => ({ loginAction: vi.fn() }));
import { RecoveryForm } from '@/app/recover/recovery-form';
beforeEach(() => {
  vi.resetAllMocks();
  actions.begin.mockResolvedValue({ state: 'pending', code: 'ABCDEFGHJK' });
  actions.check.mockResolvedValue({ state: 'approved' });
  actions.finish.mockResolvedValue({ state: 'complete', username: '.bedrock' });
});
it('requires in-game approval before showing password fields, then shows the actual login username', async () => {
  const user = userEvent.setup(); render(<RecoveryForm />);
  await user.click(screen.getByRole('button', { name: 'Start recovery' }));
  expect(await screen.findByText('/treasuryapi ui recover ABCDEFGHJK')).toBeVisible();
  expect(screen.queryByLabelText('New password')).toBeNull();
  expect(screen.getByText(/Never approve a code someone sends you/)).toBeVisible();
  await user.click(screen.getByRole('button', { name: 'I confirmed in-game' }));
  await user.type(await screen.findByLabelText('New password'), 'My new password 42!');
  await user.type(screen.getByLabelText('Confirm new password'), 'My new password 42!');
  expect(screen.getByLabelText('New password')).toHaveAttribute('type', 'password');
  await user.click(screen.getByLabelText('Show passwords'));
  expect(screen.getByLabelText('New password')).toHaveAttribute('type', 'text');
  await user.click(screen.getByRole('button', { name: 'Change password' }));
  expect(await screen.findByRole('heading', { name: 'Password changed' })).toBeVisible();
  expect(screen.getByText('.bedrock')).toBeVisible();
  expect(actions.finish.mock.calls[0]![0].get('password')).toBe('My new password 42!');
  expect(screen.queryByLabelText('New password')).toBeNull();
});
it('makes expired requests restartable without showing password fields', async () => {
  const user = userEvent.setup(); actions.check.mockResolvedValue({ state: 'expired' }); render(<RecoveryForm />);
  await user.click(screen.getByRole('button', { name: 'Start recovery' }));
  await user.click(await screen.findByRole('button', { name: 'I confirmed in-game' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('expired');
  expect(screen.getByRole('button', { name: 'Start recovery' })).toBeEnabled();
  expect(screen.queryByLabelText('New password')).toBeNull();
});
