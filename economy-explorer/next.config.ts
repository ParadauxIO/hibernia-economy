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
  // download the same JavaScript, CSS and fonts again. Player heads
  // (/api/avatar) are excluded too: they set a 24 h public cache themselves.
  async headers() {
    return [
      {
        // /api/avatar sets its own 24 h public cache (player heads hold no private data).
        source: '/((?!docs|_next/static|api/avatar/).*)',
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
  // Keep server function names: explorer_db_query_seconds{fn} labels each query
  // with the DAL function that ran it, read from the call stack (lib/metrics.ts).
  // Minified names would all collapse to one-letter labels. Client bundles are
  // still minified; this only affects the Node server output.
  experimental: { serverMinification: false },
  poweredByHeader: false,
  reactStrictMode: true,
  typedRoutes: true,
};

export default config;
