// ESLint 9 flat config. Next 16 dropped `next lint`; `npm run lint` runs ESLint
// directly with Next's presets.
import nextVitals from 'eslint-config-next/core-web-vitals';
import nextTs from 'eslint-config-next/typescript';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: ['.next/**', 'node_modules/**', 'coverage/**', 'playwright-report/**', 'test-results/**', 'next-env.d.ts', 'lib/db.generated.ts'],
  },
  ...nextVitals,
  ...nextTs,
  {
    // Playwright fixtures call their `use` callback; that isn't React's use().
    files: ['test/e2e/**'],
    rules: { 'react-hooks/rules-of-hooks': 'off' },
  },
);
