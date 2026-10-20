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
    // Two projects share the root plugins/aliases (`extends: true`): pure-logic and
    // DB tests stay on node; React component tests get a DOM. Coverage is configured
    // once here, so a `--coverage` run merges both projects into one report.
    projects: [
      {
        extends: true,
        test: {
          name: 'node',
          environment: 'node',
          include: ['test/**/*.test.ts'],
        },
      },
      {
        extends: true,
        test: {
          name: 'components',
          environment: 'jsdom',
          include: ['test/components/**/*.test.tsx'],
          setupFiles: ['test/components/setup.ts'],
        },
      },
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'text-summary', 'html', 'lcov'],
      // Every module vitest can meaningfully reach: lib, shared components, and
      // the client/server modules that live beside routes (forms, panels).
      include: ['lib/**/*.ts', 'components/**/*.{ts,tsx}', 'packages/design-system/src/**/*.{ts,tsx}', 'app/**/*.{ts,tsx}', 'proxy.ts'],
      exclude: [
        '**/*.d.ts',
        'lib/db.generated.ts', // kysely types only
        'lib/db.ts', // pool construction
        'lib/auth/authjs.ts', // Auth.js wiring, exercised end to end
        // Route entry points render whole pages against the DB and are what the
        // Playwright suite covers; measuring them here only dilutes the number.
        'app/**/{page,layout,loading,error,global-error,not-found,route}.tsx',
        'app/**/route.ts',
        'app/{robots,sitemap}.ts',
      ],
      // A few points under the measured figures, so a regression fails but
      // noise doesn't. CI runs with the SQL integration suite (RUN_INTEGRATION=1):
      // measured lines/statements 39.02%, functions 58.88%, branches 82.83%.
      // Without a DB (local unit + component run): 28.13%, 43.44%, 85.56%.
      thresholds: process.env.RUN_INTEGRATION === '1'
        ? { lines: 36, statements: 36, functions: 55, branches: 80 }
        : { lines: 25, statements: 25, functions: 40, branches: 82 },
    },
  },
});
