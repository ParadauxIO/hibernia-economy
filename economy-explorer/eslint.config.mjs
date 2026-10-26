// ESLint 9 flat config. Next 16 dropped `next lint`; `npm run lint` runs ESLint
// directly with Next's presets plus type-aware typescript-eslint rules for the
// bug classes the compiler can't see (dropped promises, unhandled union members).
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
    files: ['**/*.ts', '**/*.tsx', '**/*.mts'],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': ['error', { checksVoidReturn: { attributes: false } }],
      '@typescript-eslint/await-thenable': 'error',
      '@typescript-eslint/switch-exhaustiveness-check': ['error', { considerDefaultExhaustiveForUnions: true }],
      '@typescript-eslint/no-unnecessary-condition': ['error', { allowConstantLoopConditions: true }],
      '@typescript-eslint/consistent-type-imports': ['error', { fixStyle: 'inline-type-imports' }],
    },
  },
  {
    // Spacing and type come from design tokens (spec §5.4). Scoped to the design
    // system and charts for now because existing pages still carry inline styles;
    // Wave 2 (PAR-543) widens it to app/** once the pages are rebuilt.
    files: ['packages/**/*.tsx', 'components/charts/**/*.tsx'],
    rules: {
      'no-restricted-syntax': ['error', {
        selector: "JSXAttribute[name.name='style'] Property[key.name=/^(fontSize|padding|margin)/]",
        message: 'Use design tokens (classes with var(--text-*) / var(--space-*)) instead of inline fontSize, padding or margin.',
      }],
    },
  },
  {
    // Playwright fixtures call their `use` callback; that isn't React's use().
    files: ['test/e2e/**'],
    rules: { 'react-hooks/rules-of-hooks': 'off' },
  },
);
