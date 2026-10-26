// Component-test setup (jsdom project only): jest-dom matchers on `expect`, and
// unmount everything between tests. Testing Library only auto-cleans when the
// runner exposes a global `afterEach`, which Vitest doesn't unless `globals` is on.
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { resetViewerStoreForTests } from '@/components/ViewerContext';

afterEach(() => {
  cleanup();
  // The viewer is a module-level store; never let one test's viewer leak into the next.
  resetViewerStoreForTests();
});
