import { cleanup, render } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ExplorerAnalytics } from '@/components/ExplorerAnalytics';

const navigation = vi.hoisted(() => ({ pathname: '/' }));
vi.mock('next/navigation', () => ({ usePathname: () => navigation.pathname }));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); navigation.pathname = '/'; });

it('records navigation once using sections without disclosing account IDs or firm names', () => {
  const track = vi.fn();
  Object.defineProperty(window, 'umami', { configurable: true, value: { track } });
  const { rerender } = render(<ExplorerAnalytics />);
  expect(track).not.toHaveBeenCalled();
  navigation.pathname = '/accounts/123456';
  rerender(<ExplorerAnalytics />);
  expect(track).toHaveBeenLastCalledWith('explorer-follow-through', { from: 'overview', to: 'accounts' });
  rerender(<ExplorerAnalytics />);
  expect(track).toHaveBeenCalledTimes(1);
  navigation.pathname = '/firms/Private%20Company/financials';
  rerender(<ExplorerAnalytics />);
  expect(track).toHaveBeenLastCalledWith('explorer-follow-through', { from: 'accounts', to: 'firms' });
  delete (window as Window & { umami?: unknown }).umami;
});

it('does not break navigation when the analytics script is unavailable', () => {
  const { rerender } = render(<ExplorerAnalytics />);
  navigation.pathname = '/me';
  expect(() => rerender(<ExplorerAnalytics />)).not.toThrow();
});
