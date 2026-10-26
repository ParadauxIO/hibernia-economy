import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { RangePicker } from '@democracycraft/design-system/next';
import { navigation, resetNavigation } from './next-navigation';

vi.mock('next/navigation', () => import('./next-navigation'));

beforeEach(() => {
  resetNavigation('/economy', 'page=4&unit=commerce');
  window.matchMedia = vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() });
});

it('applies inclusive calendar dates, retaining unrelated parameters and clearing pagination', async () => {
  const user = userEvent.setup();
  render(<RangePicker value={{ preset: '30d', from: '2026-09-27', to: '2026-10-26', compare: false }} />);
  await user.click(screen.getByRole('button', { name: 'Date range' }));
  fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } });
  fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-20' } });
  await user.click(screen.getByRole('button', { name: 'Apply range' }));
  expect(navigation.router.push).toHaveBeenCalledWith('/economy?unit=commerce&range=custom&from=2026-10-01&to=2026-10-20', { scroll: false });
});

it('discards cancelled drafts and follows the current URL range when reopened', async () => {
  const user = userEvent.setup();
  const { rerender } = render(<RangePicker value={{ preset: '30d', from: '2026-09-27', to: '2026-10-26', compare: false }} />);
  await user.click(screen.getByRole('button', { name: 'Date range' }));
  fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } });
  await user.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(navigation.router.push).not.toHaveBeenCalled();
  rerender(<RangePicker value={{ preset: '7d', from: '2026-10-20', to: '2026-10-26', compare: false }} />);
  await user.click(screen.getByRole('button', { name: 'Date range' }));
  expect(screen.getByLabelText('From')).toHaveValue('2026-10-20');
  expect(screen.getByLabelText('To')).toHaveAttribute('min', '2026-10-20');
});
