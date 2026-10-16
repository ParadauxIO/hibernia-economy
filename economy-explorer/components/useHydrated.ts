'use client';
// `false` during SSR and the hydrating render, `true` afterwards — without the
// `useEffect(() => setMounted(true))` round trip. React re-renders with the
// client snapshot right after hydration, so markup that differs between server
// and client (DOM-measuring charts, controls that need JS) never mismatches.

import { useSyncExternalStore } from 'react';

const subscribe = () => () => {};

export function useHydrated(): boolean {
  return useSyncExternalStore(subscribe, () => true, () => false);
}
