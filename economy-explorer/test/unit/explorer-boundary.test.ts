import { beforeEach, expect, it, vi } from 'vitest';
import { NextRequest, type NextFetchEvent } from 'next/server';
const { identity, upsert } = vi.hoisted(() => ({ identity: vi.fn(), upsert: vi.fn() }));
vi.mock('@/lib/auth/authjs', () => ({ auth: (handler: unknown) => handler }));
vi.mock('@/lib/sql/identity', () => ({ findIdentityBySub: identity, upsertIdentity: upsert }));
import proxy from '@/proxy';
import { isPublicExplorerPath } from '@/lib/auth/routeAccess';
const UUID = '00000000-0000-0000-0000-00000000a1ce';
function request(path: string, auth: Record<string, unknown> | null = null, headers = {}) {
  return Object.assign(new NextRequest(`http://localhost${path}`, { headers }), { auth });
}
const event = {} as NextFetchEvent;
beforeEach(() => { vi.clearAllMocks(); identity.mockResolvedValue(null); delete process.env.E2E_TEST_AUTH; });
const dataPaths = ['/', '/market', '/market/activity', '/chestshop', '/chestshop/items/DIAMOND?compare=IRON_INGOT', '/chestshop/shops', '/accounts', '/accounts/1', '/firms/Acme', '/players/Alice', '/economy/wealth', '/transactions', '/search?q=a', '/api/search/preview?q=al', '/api/preview/player/Alice', '/api/preview/item/DIAMOND', '/api/export/accounts', '/api/viewer', '/api/docs/tax-rates', '/accounts/1/opengraph-image', '/_next/image?url=%2Faccounts%2F1'];
it.each(dataPaths)('rejects anonymous HTML/RSC/data requests before identity or economy reads: %s', async path => {
  const response = await proxy(request(path, null, { RSC: '1', 'Next-Router-Prefetch': '1' }), event);
  expect(response?.status).toBe(path.startsWith('/api/') ? 401 : 307);
  expect(identity).not.toHaveBeenCalled();
  expect(isPublicExplorerPath(path.split('?')[0]!)).toBe(false);
});
it.each(['/market', '/api/preview/item/DIAMOND'])('rejects a signed-in unlinked identity: %s', async path => {
  const response = await proxy(request(path, { keycloakSub: 'unlinked' }), event);
  expect(response?.status).toBe(path.startsWith('/api/') ? 403 : 307);
  if (!path.startsWith('/api/')) expect(response?.headers.get('location')).toContain('/link?callbackUrl=');
});
it('allows a durable linked identity even with an older session lacking its claim', async () => {
  identity.mockResolvedValue({ playerUuid: UUID });
  const response = await proxy(request('/market', { keycloakSub: 'linked' }), event);
  expect(response?.headers.get('x-middleware-next')).toBe('1');
  expect(upsert).not.toHaveBeenCalled();
});
it('establishes a verified provider claim durably before admitting the request', async () => {
  identity.mockResolvedValueOnce(null).mockResolvedValueOnce({ playerUuid: UUID });
  const response = await proxy(request('/market', { keycloakSub: 'new-linked', minecraftUuid: UUID }), event);
  expect(upsert).toHaveBeenCalledWith(expect.objectContaining({ sub: 'new-linked', playerUuid: UUID, linkedBy: 'token-claim' }));
  expect(response?.headers.get('x-middleware-next')).toBe('1');
});
it('fails closed if identity verification is unavailable, even with a signed claim', async () => {
  identity.mockRejectedValue(new Error('unavailable'));
  const response = await proxy(request('/market', { keycloakSub: 'linked', minecraftUuid: UUID }), event);
  expect(response?.status).toBe(503);
  expect(response?.headers.get('x-middleware-next')).toBeNull();
});
it.each(['/link', '/docs', '/docs/guides/tax', '/api/auth/signin', '/api/auth/callback/keycloak', '/api/avatar/test', '/healthz', '/metrics', '/sitemap.xml', '/dm-sans.ttf'])('keeps safe public route available: %s', async path => {
  const response = await proxy(request(path), event);
  expect(response?.headers.get('x-middleware-next')).toBe('1');
  expect(identity).not.toHaveBeenCalled();
});
