import { defineConfig } from 'vitest/config';
import tsconfigPaths from 'vite-tsconfig-paths';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  plugins: [tsconfigPaths()],
  resolve: {
    alias: {
      // `import 'server-only'` throws outside an RSC bundle; stub it so server
      // modules (lib/auth, lib/sql) can be imported under Vitest.
      'server-only': fileURLToPath(new URL('./test/stubs/server-only.ts', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'text-summary', 'html', 'lcov'],
      include: ['lib/**/*.ts'],
      exclude: ['lib/**/*.d.ts', 'lib/db.ts', 'lib/auth/authjs.ts'],
      // Measured baseline (unit tests only; DB integration tests skip without
      // RUN_INTEGRATION): lines/statements 13.82%, functions 19.04%,
      // branches 84.61%. Set a couple points below so CI fails on regression
      // without being flaky.
      thresholds: {
        lines: 11,
        statements: 11,
        functions: 17,
        branches: 75,
      },
    },
  },
});
