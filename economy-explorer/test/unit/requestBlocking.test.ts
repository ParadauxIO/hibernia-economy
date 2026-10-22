import { describe, it, expect, vi, afterEach } from 'vitest';
import { NextRequest } from 'next/server';
import { createRequestBlocker } from '@/lib/requestBlocking';

const sessionWork = vi.hoisted(() => vi.fn());
vi.mock('@/lib/auth/authjs', () => ({ auth: () => sessionWork }));
const headers = (ip: string, ua = 'browser') => new Headers({ 'x-envoy-external-address': ip, 'user-agent': ua });
afterEach(() => { vi.unstubAllEnvs(); vi.restoreAllMocks(); vi.resetModules(); sessionWork.mockReset(); });

describe('request denylist', () => {
  it('allows traffic by default and ignores all untrusted IP headers', () => {
    expect(createRequestBlocker({})(headers('136.107.101.57'))).toEqual({ ip: '' });
  });
  it.each([
    ['192.0.2.7', true], ['192.0.2.255', true], ['192.0.3.0', false],
    ['::ffff:192.0.2.7', true], ['2001:db8::1', true], ['2001:db9::1', false],
    ['2001:0db8:0000:0000:0000:0000:0000:0001', true], ['::1', false],
  ])('matches IPv4, IPv6 and CIDRs: %s', (ip, blocked) => {
    const check = createRequestBlocker({ BLOCK_TRUST_ENVOY: '1', BLOCKED_IPS: '192.0.2.0/24,2001:db8::/32' });
    expect(check(headers(ip)).reason).toBe(blocked ? 'ip' : undefined);
  });
  it('uses the overwritten gateway identity, never a spoofed XFF entry', () => {
    const check = createRequestBlocker({ BLOCK_TRUST_ENVOY: '1', BLOCKED_IPS: '192.0.2.7' });
    const h = headers('192.0.2.7');
    h.set('x-forwarded-for', '198.51.100.1, 198.51.100.2');
    expect(check(h).reason).toBe('ip');
    h.set('x-envoy-external-address', '198.51.100.1');
    h.set('x-forwarded-for', '192.0.2.7');
    expect(check(h).reason).toBeUndefined();
  });
  it.each(['', 'unknown', '192.0.2.1, 192.0.2.7', '192.0.2.7:123', '[::1]', 'fe80::1%eth0'])('rejects missing or ambiguous identity: %s', (ip) => {
    expect(createRequestBlocker({ BLOCK_TRUST_ENVOY: '1', BLOCKED_IPS: '192.0.2.7' })(headers(ip)).reason).toBe('invalid-client-ip');
  });
  it('matches exact and literal substring UAs case sensitively', () => {
    const check = createRequestBlocker({ BLOCKED_UAS: 'bot/1', BLOCKED_UA_SUBSTRINGS: 'scraper' });
    expect(check(headers('', 'bot/1')).reason).toBe('user-agent');
    expect(check(headers('', 'prefix scraper/2')).reason).toBe('user-agent');
    expect(check(headers('', 'bot/10')).reason).toBeUndefined();
    expect(check(headers('', 'Scraper')).reason).toBeUndefined();
  });
  it.each(['host.test', '1.2.3.4/33', '::1/129', '::1/-1', '::1/', '::1/64/2'])('fails closed on invalid configuration %s', (rule) => {
    expect(() => createRequestBlocker({ BLOCK_TRUST_ENVOY: '1', BLOCKED_IPS: rule })).toThrow();
  });
  it('requires explicit proxy trust before enabling IP rules', () => {
    expect(() => createRequestBlocker({ BLOCKED_IPS: '192.0.2.1' })).toThrow('BLOCK_TRUST_ENVOY');
  });
  it.each(['/accounts', '/firms', '/admin', '/api/auth/session', '/economy/health?theme=red'])('rejects %s before auth, redirect, rendering or DB work', async (path) => {
    vi.stubEnv('BLOCKED_UAS', 'bot');
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const { default: proxy } = await import('@/proxy');
    const response = await proxy(new NextRequest('http://localhost' + path, { headers: { 'user-agent': 'bot' } }), {} as never);
    expect(response?.status).toBe(403);
    expect(response?.headers.get('location')).toBeNull();
    expect(response?.headers.get('x-middleware-next')).toBeNull();
    expect(sessionWork).not.toHaveBeenCalled();
    expect(JSON.parse(log.mock.calls[0]![0])).toMatchObject({ kind: 'blocked', reason: 'user-agent', status: 403 });
  });
  it('passes allowed public traffic onward', async () => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    const { default: proxy } = await import('@/proxy');
    const response = await proxy(new NextRequest('http://localhost/accounts'), {} as never);
    expect(response?.headers.get('x-middleware-next')).toBe('1');
  });
});
