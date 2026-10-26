import { afterEach, describe, expect, it, vi } from 'vitest';
import { avatarUpstreamUrl, GET } from '@/app/api/avatar/[uuid]/route';

const UUID = '069a79f4-44e9-4726-a5be-fca90e38aaf5';
const call = (uuid: string, query = '', headers: Record<string, string> = {}) =>
  GET(new Request(`http://explorer.test/api/avatar/${uuid}${query}`, { headers }), { params: Promise.resolve({ uuid }) });

function png(bytes = 64): Response {
  return new Response(new Uint8Array(bytes), { status: 200, headers: { 'content-type': 'image/png' } });
}

afterEach(() => vi.unstubAllGlobals());

describe('avatar proxy', () => {
  it('rejects anything that is not a UUID before contacting the service', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    for (const bad of ['..%2F..%2Fetc', 'notch', `${UUID}x`, 'http:%2F%2Fevil']) {
      expect((await call(bad)).status).toBe(400);
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('fetches a normalised URL server-side without forwarding visitor headers, and caches for 24 h', async () => {
    const fetchMock = vi.fn(async () => png());
    vi.stubGlobal('fetch', fetchMock);
    const res = await call(UUID.toUpperCase(), '?size=40', { 'x-forwarded-for': '203.0.113.9', cookie: 'authjs.session-token=secret', referer: 'https://economy.example/accounts/1' });
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toBe('image/png');
    expect(res.headers.get('cache-control')).toContain('max-age=86400');
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit & { next?: { revalidate?: number } }];
    expect(url).toBe('https://mc-heads.net/avatar/069a79f444e94726a5befca90e38aaf5/48');
    expect(JSON.stringify(init.headers)).not.toMatch(/203\.0\.113\.9|secret|economy\.example/);
    expect(init.next?.revalidate).toBe(86400);
  });

  it('serves a neutral placeholder when the service fails or returns something that is not a PNG', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>', { status: 200, headers: { 'content-type': 'text/html' } })));
    const html = await call(UUID);
    expect(html.headers.get('content-type')).toBe('image/svg+xml');
    expect(html.headers.get('cache-control')).toBe('public, max-age=300');
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('timeout'); }));
    expect((await call(UUID)).headers.get('content-type')).toBe('image/svg+xml');
  });

  it('honours a configured template', () => {
    expect(avatarUpstreamUrl('abc', 64, 'https://heads.example/{uuid}.png?s={size}')).toBe('https://heads.example/abc.png?s=64');
  });
});
