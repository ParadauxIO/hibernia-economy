// Controllable stand-in for `next/navigation` in component tests. Use with
//   vi.mock('next/navigation', () => import('./next-navigation'));
// then set `navigation.pathname` / `navigation.search` per test and assert on
// `navigation.router.*` calls. Reset in beforeEach with `resetNavigation()`.
import { vi } from 'vitest';

export const navigation = {
  pathname: '/',
  search: new URLSearchParams(),
  router: {
    push: vi.fn<(href: string) => void>(),
    replace: vi.fn<(href: string) => void>(),
    refresh: vi.fn<() => void>(),
    back: vi.fn<() => void>(),
    forward: vi.fn<() => void>(),
    prefetch: vi.fn<(href: string) => void>(),
  },
};

export function resetNavigation(pathname = '/', search = ''): void {
  navigation.pathname = pathname;
  navigation.search = new URLSearchParams(search);
  for (const fn of Object.values(navigation.router)) fn.mockReset();
}

export function usePathname(): string {
  return navigation.pathname;
}

export function useSearchParams(): URLSearchParams {
  return navigation.search;
}

export function useRouter(): typeof navigation.router {
  return navigation.router;
}
