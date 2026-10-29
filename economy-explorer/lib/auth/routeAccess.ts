/** Public browsing is limited to the overview, directories and documentation. */
export function isPublicExplorerPath(path: string): boolean {
  return ['/', '/players', '/firms', '/accounts', '/access', '/api/viewer', '/api/docs/tax-rates'].includes(path) || path === '/link' || path === '/docs' || path.startsWith('/docs/')
    || path.startsWith('/api/auth/') || path.startsWith('/api/avatar/')
    || path === '/robots.txt' || path === '/sitemap.xml' || path === '/favicon.ico'
    || path === '/dm-sans.ttf' || path === '/dm-sans-LICENSE.txt'
    || path === '/healthz' || path === '/metrics' || path.startsWith('/_next/static/');
}

export function validMinecraftUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}
