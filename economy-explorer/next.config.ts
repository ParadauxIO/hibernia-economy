import type { NextConfig } from 'next';

const config: NextConfig = {
  transpilePackages: ['@democracycraft/design-system'],
  // Multi-stage Docker build copies .next/standalone — trims node_modules to
  // only what server.js actually imports.
  output: 'standalone',

  // Bot mitigation: never let an intermediate cache hold rendered HTML, and
  // keep the app out of search. The /docs documentation site is the deliberate
  // exception — it's public and indexable. Build-hashed assets must also be
  // excluded: overriding their immutable cache headers forces every visit to
  // download the same JavaScript, CSS and fonts again.
  async headers() {
    return [
      {
        source: '/((?!docs|_next/static).*)',
        headers: [
          { key: 'Cache-Control', value: 'no-store' },
          { key: 'X-Robots-Tag', value: 'noindex, nofollow' },
        ],
      },
      {
        // Public documentation: cacheable + indexable. Excluding it from the
        // rule above (rather than relying on later-rule precedence) ensures no
        // X-Robots-Tag: noindex header reaches /docs to override page metadata.
        source: '/docs/:path*',
        headers: [
          { key: 'Cache-Control', value: 'public, max-age=3600, must-revalidate' },
          { key: 'X-Robots-Tag', value: 'index, follow' },
        ],
      },
      {
        source: '/docs',
        headers: [
          { key: 'Cache-Control', value: 'public, max-age=3600, must-revalidate' },
          { key: 'X-Robots-Tag', value: 'index, follow' },
        ],
      },
    ];
  },
  poweredByHeader: false,
  reactStrictMode: true,
  // Re-enable once all pages from the migration plan exist (Phase 2).
  typedRoutes: false,
  // ESLint is wired up (`npm run lint`) but kept out of the production build so
  // a lint warning can't fail CI; run it separately in dev/PR.
};

export default config;
