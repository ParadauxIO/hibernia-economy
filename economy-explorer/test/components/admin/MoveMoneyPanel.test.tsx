import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { MoveMoneyPanel } from '@/app/admin/accounts/move-money-panel';
import { adminTransferAction } from '@/lib/actions/admin-accounts';
import { resetNavigation } from '../next-navigation';
import { setViewer } from '../viewer';
vi.mock('next/navigation', () => import('../next-navigation'));
vi.mock('@/lib/actions/admin-accounts', () => ({ adminTransferAction: vi.fn() }));
beforeEach(() => { sessionStorage.clear(); resetNavigation('/admin/accounts'); setViewer('admin'); vi.mocked(adminTransferAction).mockReset(); });
async function fill() {
  const user = userEvent.setup();
  await user.type(screen.getByPlaceholderText('From account #'), '1');
  await user.type(screen.getByPlaceholderText('To account #'), '2');
  await user.type(screen.getByPlaceholderText('Amount'), '5.00');
  return user;
}
it('reuses a retry key after a lost response, including a remount', async () => {
  vi.mocked(adminTransferAction).mockRejectedValue(new Error('Lost response'));
  const view = render(<MoveMoneyPanel disabled={false} />);
  let user = await fill();
  await user.click(screen.getByRole('button', { name: 'Transfer' }));
  await screen.findByRole('alert');
  const first = vi.mocked(adminTransferAction).mock.calls[0]![0];
  view.unmount();
  render(<MoveMoneyPanel disabled={false} />);
  user = await fill();
  await user.click(screen.getByRole('button', { name: 'Transfer' }));
  await screen.findByRole('alert');
  expect(vi.mocked(adminTransferAction).mock.calls[1]![0]).toEqual(first);
  expect(first.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
});
it('uses a new key for a new transfer after success', async () => {
  vi.mocked(adminTransferAction).mockResolvedValue({ ok: true, result: { txnId: 9, fromAccountId: 1, toAccountId: 2, amount: '5.00', memo: '' } });
  render(<MoveMoneyPanel disabled={false} />);
  const user = await fill();
  await user.click(screen.getByRole('button', { name: 'Transfer' }));
  await screen.findByRole('status');
  await user.type(screen.getByPlaceholderText('Amount'), '5.00');
  await user.click(screen.getByRole('button', { name: 'Transfer' }));
  expect(vi.mocked(adminTransferAction).mock.calls[1]![0].idempotencyKey).not.toBe(vi.mocked(adminTransferAction).mock.calls[0]![0].idempotencyKey);
});
