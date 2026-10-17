import 'server-only';
import { lookup } from 'node:dns/promises';
import net from 'node:net';

/**
 * SSRF guard for player-supplied webhook URLs — a TS port of the Treasury REST
 * API's SsrfValidator. The dispatcher (treasury-rest-api) re-checks every URL at
 * delivery time, so this is the early reject / first line; require https, an
 * explicit host, no credentials, and that every resolved address is a public
 * unicast address. Throws on violation (the caller surfaces the message).
 */
export async function assertPublicHttpsUrl(raw: string): Promise<URL> {
  let u: URL;
  try {
    u = new URL(raw.trim());
  } catch {
    throw new Error('Webhook URL is not a valid URL.');
  }
  if (u.protocol !== 'https:') throw new Error('Webhook URL must use https.');
  if (!u.hostname) throw new Error('Webhook URL must include a host.');
  if (u.username || u.password) throw new Error('Webhook URL must not contain credentials.');

  const host = u.hostname.replace(/^\[/, '').replace(/\]$/, ''); // strip IPv6 brackets
  let addrs: string[];
  if (net.isIP(host)) {
    addrs = [host];
  } else {
    try {
      addrs = (await lookup(host, { all: true })).map((r) => r.address);
    } catch {
      throw new Error('Webhook URL host could not be resolved.');
    }
  }
  if (addrs.length === 0) throw new Error('Webhook URL host could not be resolved.');
  for (const a of addrs) {
    if (isBlockedAddress(a)) throw new Error('Webhook URL must resolve to a public address.');
  }
  return u;
}

/** True for anything other than a routable public unicast address. */
export function isBlockedAddress(ip: string): boolean {
  const v = net.isIP(ip);
  if (v === 4) return isBlockedV4(ip);
  if (v === 6) return isBlockedV6(ip);
  return true;
}

function isBlockedV4(ip: string): boolean {
  const o = ip.split('.').map(Number);
  const [a, b] = o;
  if (o.length !== 4 || a === undefined || b === undefined || o.some((n) => Number.isNaN(n) || n < 0 || n > 255)) return true;
  if (a === 0) return true; // 0.0.0.0/8
  if (a === 10) return true; // private
  if (a === 127) return true; // loopback
  if (a === 169 && b === 254) return true; // link-local / cloud metadata
  if (a === 172 && b >= 16 && b <= 31) return true; // private
  if (a === 192 && b === 168) return true; // private
  if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT 100.64/10
  if (a >= 224) return true; // multicast / reserved
  return false;
}

function isBlockedV6(ip: string): boolean {
  const lower = ip.toLowerCase();
  if (lower === '::1' || lower === '::') return true; // loopback / unspecified
  if (lower.startsWith('fe80')) return true; // link-local
  if (lower.startsWith('fc') || lower.startsWith('fd')) return true; // unique-local fc00::/7
  if (lower.startsWith('ff')) return true; // multicast
  const mapped = lower.match(/^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/);
  if (mapped?.[1]) return isBlockedV4(mapped[1]);
  return false;
}
