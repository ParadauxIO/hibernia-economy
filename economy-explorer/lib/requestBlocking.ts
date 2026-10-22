import { BlockList, isIP } from 'node:net';

/** No forwarded header is trustworthy on a directly reachable app server. Enable
 * only behind Cilium Envoy with useRemoteAddress=true, zero trusted XFF hops,
 * and ingress restricted to that gateway. Never fall back to client-supplied XFF. */
export function createRequestBlocker(env: Record<string, string | undefined>) {
  const ips = new BlockList();
  const rules = (env.BLOCKED_IPS ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  for (const rule of rules) {
    const [address, prefix, extra] = rule.split('/');
    const version = isIP(address ?? '');
    if (!version || address?.includes('%') || extra !== undefined || (prefix !== undefined &&
      (!/^\d+$/.test(prefix) || Number(prefix) > (version === 4 ? 32 : 128)))) {
      throw new Error('Invalid BLOCKED_IPS address or CIDR');
    }
    const family = version === 4 ? 'ipv4' : 'ipv6';
    if (prefix === undefined) ips.addAddress(address!, family);
    else ips.addSubnet(address!, Number(prefix), family);
  }
  const trusted = env.BLOCK_TRUST_ENVOY === '1';
  if (rules.length && !trusted) throw new Error('BLOCKED_IPS requires BLOCK_TRUST_ENVOY=1');
  const exact = (env.BLOCKED_UAS ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  const contains = (env.BLOCKED_UA_SUBSTRINGS ?? '').split(',').map((s) => s.trim()).filter(Boolean);

  return (headers: Headers): { ip: string; reason?: 'ip' | 'user-agent' | 'invalid-client-ip' } => {
    const raw = trusted ? headers.get('x-envoy-external-address')?.trim() ?? '' : '';
    const version = raw.includes('%') ? 0 : isIP(raw);
    const ip = version ? raw : '';
    // Missing/ambiguous identity must not silently bypass an active IP denylist.
    if (rules.length && !version) return { ip, reason: 'invalid-client-ip' };
    if (version && ips.check(ip, version === 4 ? 'ipv4' : 'ipv6')) return { ip, reason: 'ip' };
    const ua = headers.get('user-agent') ?? '';
    if (exact.includes(ua) || contains.some((part) => ua.includes(part))) return { ip, reason: 'user-agent' };
    return { ip };
  };
}
