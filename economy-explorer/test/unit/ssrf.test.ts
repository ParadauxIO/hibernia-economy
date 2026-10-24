import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock the DNS lookup so hostname-resolution branches are deterministic and no
// real network I/O happens. The map keys are hostnames; values are the resolved
// address lists returned by lookup(host, { all: true }).
const resolveMap = new Map<string, Array<{ address: string; family: number }>>();
vi.mock('node:dns/promises', () => ({
  lookup: vi.fn(async (host: string) => {
    if (resolveMap.has(host)) return resolveMap.get(host)!;
    const err = new Error(`getaddrinfo ENOTFOUND ${host}`);
    throw err;
  }),
}));

import { assertPublicHttpsUrl, isBlockedAddress } from '@/lib/util/ssrf';

beforeEach(() => resolveMap.clear());

describe('isBlockedAddress — IPv4 ranges', () => {
  it('blocks loopback, private, link-local/metadata, unspecified, CGNAT, multicast', () => {
    expect(isBlockedAddress('127.0.0.1')).toBe(true); // loopback
    expect(isBlockedAddress('0.0.0.0')).toBe(true); // 0.0.0.0/8
    expect(isBlockedAddress('10.0.0.5')).toBe(true); // 10/8 private
    expect(isBlockedAddress('192.168.1.1')).toBe(true); // 192.168/16 private
    expect(isBlockedAddress('172.16.0.1')).toBe(true); // 172.16/12 low edge
    expect(isBlockedAddress('172.31.255.255')).toBe(true); // 172.16/12 high edge
    expect(isBlockedAddress('169.254.169.254')).toBe(true); // link-local / cloud metadata
    expect(isBlockedAddress('100.64.0.1')).toBe(true); // CGNAT 100.64/10
    expect(isBlockedAddress('100.127.255.255')).toBe(true); // CGNAT high edge
    expect(isBlockedAddress('224.0.0.1')).toBe(true); // multicast
    expect(isBlockedAddress('255.255.255.255')).toBe(true); // reserved/broadcast
  });

  it('allows routable public unicast IPv4', () => {
    expect(isBlockedAddress('8.8.8.8')).toBe(false);
    expect(isBlockedAddress('1.1.1.1')).toBe(false);
    expect(isBlockedAddress('93.184.216.34')).toBe(false); // example.com
    expect(isBlockedAddress('172.15.0.1')).toBe(false); // just below 172.16 private
    expect(isBlockedAddress('172.32.0.1')).toBe(false); // just above 172.31 private
    expect(isBlockedAddress('100.63.255.255')).toBe(false); // just below CGNAT
    expect(isBlockedAddress('100.128.0.0')).toBe(false); // just above CGNAT
    expect(isBlockedAddress('223.255.255.255')).toBe(false); // just below multicast
  });
});

describe('isBlockedAddress — IPv6 ranges', () => {
  it('blocks loopback, unspecified, link-local, unique-local, multicast', () => {
    expect(isBlockedAddress('::1')).toBe(true); // loopback
    expect(isBlockedAddress('::')).toBe(true); // unspecified
    expect(isBlockedAddress('fe80::1')).toBe(true); // link-local
    expect(isBlockedAddress('fc00::1')).toBe(true); // unique-local fc00::/8
    expect(isBlockedAddress('fd12:3456::1')).toBe(true); // unique-local fd00::/8
    expect(isBlockedAddress('ff02::1')).toBe(true); // multicast
    expect(isBlockedAddress('FE80::1')).toBe(true); // case-insensitive
  });

  it('blocks IPv4-mapped IPv6 that maps to a private v4', () => {
    expect(isBlockedAddress('::ffff:127.0.0.1')).toBe(true);
    expect(isBlockedAddress('::ffff:169.254.169.254')).toBe(true);
    expect(isBlockedAddress('::ffff:10.0.0.1')).toBe(true);
  });

  it('allows public IPv6 and IPv4-mapped public v4', () => {
    expect(isBlockedAddress('2606:4700:4700::1111')).toBe(false); // cloudflare
    expect(isBlockedAddress('::ffff:8.8.8.8')).toBe(false);
  });
});

describe('isBlockedAddress — non-IP input', () => {
  it('blocks anything that is not a valid IP', () => {
    expect(isBlockedAddress('not-an-ip')).toBe(true);
    expect(isBlockedAddress('example.com')).toBe(true);
    expect(isBlockedAddress('')).toBe(true);
  });
});

describe('assertPublicHttpsUrl — scheme / structure guards', () => {
  it('rejects non-https schemes', async () => {
    await expect(assertPublicHttpsUrl('http://example.com/hook')).rejects.toThrow('must use https');
    resolveMap.set('example.com', [{ address: '93.184.216.34', family: 4 }]);
    await expect(assertPublicHttpsUrl('ftp://example.com/hook')).rejects.toThrow('must use https');
  });

  it('rejects malformed URLs', async () => {
    await expect(assertPublicHttpsUrl('not a url')).rejects.toThrow('not a valid URL');
    await expect(assertPublicHttpsUrl('')).rejects.toThrow('not a valid URL');
  });

  it('rejects URLs carrying credentials', async () => {
    await expect(assertPublicHttpsUrl('https://user:pass@example.com/hook')).rejects.toThrow(
      'must not contain credentials',
    );
    await expect(assertPublicHttpsUrl('https://user@example.com/hook')).rejects.toThrow(
      'must not contain credentials',
    );
  });
});

describe('assertPublicHttpsUrl — literal IP hosts (no DNS)', () => {
  it('rejects a literal private/loopback IP host', async () => {
    await expect(assertPublicHttpsUrl('https://127.0.0.1/hook')).rejects.toThrow('public address');
    await expect(assertPublicHttpsUrl('https://169.254.169.254/latest/meta-data')).rejects.toThrow(
      'public address',
    );
    await expect(assertPublicHttpsUrl('https://10.0.0.1/hook')).rejects.toThrow('public address');
    await expect(assertPublicHttpsUrl('https://[::1]/hook')).rejects.toThrow('public address');
  });

  it('accepts a literal public IP host', async () => {
    const u = await assertPublicHttpsUrl('https://8.8.8.8/hook');
    expect(u.hostname).toBe('8.8.8.8');
  });
});

describe('assertPublicHttpsUrl — hostname resolution', () => {
  it('rejects a hostname that resolves to a private address', async () => {
    resolveMap.set('evil.internal', [{ address: '10.1.2.3', family: 4 }]);
    await expect(assertPublicHttpsUrl('https://evil.internal/hook')).rejects.toThrow('public address');
  });

  it('rejects a hostname where ANY resolved address is private (rebinding defence)', async () => {
    resolveMap.set('mixed.example', [
      { address: '93.184.216.34', family: 4 }, // public
      { address: '127.0.0.1', family: 4 }, // private → whole URL blocked
    ]);
    await expect(assertPublicHttpsUrl('https://mixed.example/hook')).rejects.toThrow('public address');
  });

  it('rejects a hostname that cannot be resolved', async () => {
    await expect(assertPublicHttpsUrl('https://nx.does-not-exist/hook')).rejects.toThrow(
      'could not be resolved',
    );
  });

  it('accepts a hostname that resolves only to public addresses', async () => {
    resolveMap.set('good.example', [{ address: '93.184.216.34', family: 4 }]);
    const u = await assertPublicHttpsUrl('https://good.example/api/hook');
    expect(u.hostname).toBe('good.example');
  });
});
