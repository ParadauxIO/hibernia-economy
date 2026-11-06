/** Public browsing is limited to the overview, directory previews and documentation. */
export function isPublicExplorerPath(path: string, searchParams?: URLSearchParams): boolean {
  // Check every value so duplicate parameters cannot bypass the boundary.
  // Noncanonical/invalid pages also require sign-in; omitted page means page 1.
  if (['/players', '/firms', '/accounts'].includes(path)
    && searchParams?.getAll('page').some(page => page !== '1')) return false;
  return ['/', '/players', '/firms', '/accounts', '/access', '/api/viewer', '/api/docs/tax-rates', '/api/docs/notes'].includes(path) || path === '/link' || path === '/docs' || path.startsWith('/docs/')
    || path.startsWith('/api/auth/') || path.startsWith('/api/avatar/')
    || path === '/robots.txt' || path === '/sitemap.xml' || path === '/favicon.ico'
    || path === '/dm-sans.ttf' || path === '/dm-sans-LICENSE.txt'
    || path === '/healthz' || path === '/metrics' || path.startsWith('/_next/static/');
}

export function validMinecraftUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}
