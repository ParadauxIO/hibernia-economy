import { render, screen, fireEvent } from '@testing-library/react';
import { it, expect, vi } from 'vitest';
import Loading from '@/app/admin/role-grants/loading';
import ErrorState from '@/app/admin/role-grants/error';
it('announces loading and offers recovery without exposing the database error', () => {
  const loading = render(<Loading />);
  expect(screen.getByRole('status')).toHaveTextContent('Loading role grants');
  loading.unmount();
  const reset = vi.fn();
  render(<ErrorState reset={reset} />);
  expect(screen.getByRole('alert')).toHaveTextContent('Role grants could not be loaded');
  fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(reset).toHaveBeenCalledOnce();
});
