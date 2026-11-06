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
      reporter: ['text', 'text-summary', 'html', 'lcov', 'json', 'json-summary'],
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
      // AST remapping counts decisions in unexecuted code too. Keep the full
      // source scope and separate baselines for CI's SQL suite and local runs.
      // Measurements and gate rationale: ../wiki/explorer-coverage.md.
      thresholds: {
        ...(process.env.RUN_INTEGRATION === '1'
          ? { lines: 69, statements: 67, functions: 63, branches: 59 }
          : { lines: 44, statements: 42, functions: 35, branches: 40 }),
        // Aggregate headroom must not hide a loss in these critical modules.
        'lib/auth/{admin,capabilities,routeAccess}.ts': {
          perFile: true, lines: 100, statements: 100, functions: 100, branches: 100,
        },
        'lib/money.ts': {
          lines: 100, statements: 100, functions: 100, branches: 95,
        },
      },
    },
  },
});
