import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import { unstable_cache } from 'next/cache';
import Link from 'next/link';
import type { Route } from 'next';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { PrivacyGate } from '@/components/PrivacyGate';
import { listAdminApiKeys } from '@/lib/sql/apiKey';
import { listRateLimitOverrides } from '@/lib/sql/rateLimitOverride';
import { queryKeyUsage, type KeyUsage } from '@/lib/prometheus';
import { fmtN } from '@/lib/format';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { Player } from '@/components/Player';
import { RevokeButton } from './revoke-button';
import { RotateButton } from './rotate-button';
import { LimitDialog } from './limit-dialog';
import { TableWrap } from '@democracycraft/design-system';

export const dynamic = 'force-dynamic';

// Per-key usage comes from Prometheus, which is spiky under load. Cache it 30s
// (slightly-stale usage stats are fine) so a slow Prometheus stops blocking the
// page on every hit. unstable_cache can't round-trip a Map through the data
// cache, so we cache entries and rebuild the Map below. One tenant per pod.
const getCachedKeyUsage = unstable_cache(
  async (): Promise<[number, KeyUsage][]> => Array.from((await queryKeyUsage()).entries()),
  ['api-key-usage'],
  { revalidate: 30 },
);

export async function generateMetadata() {
  return buildMetadata({ title: "API keys", description: "Manage programmatic API keys for the {server} economy API.", path: "/admin/api-keys" });
}

export default async function AdminApiKeysPage() {
  const viewer = await getViewer();
  if (viewer.anon) return <PrivacyGate kind="login" title="Admin API keys" hint="Sign in to manage API keys." />;
  if (!hasPermission(viewer, 'api_keys.read')) return <PrivacyGate kind="private" title="Permission required" />;
  await auditView(viewer, { path: '/admin/api-keys', targetType: 'global' });

  const [keys, overrides, usageEntries] = await Promise.all([
    listAdminApiKeys(),
    listRateLimitOverrides(),
    getCachedKeyUsage(),
  ]);
  const usage = new Map<number, KeyUsage>(usageEntries);
  const active = keys.filter((k) => !k.revoked).length;
  const multByOwner = new Map(overrides.map((o) => [o.owner_uuid, o.multiplier]));
  const hasUsage = usage.size > 0;

  return (
    <>
      <div className="page-heading">
        <h1>API keys</h1>
        <span className="sub">{fmtN(active)} active · {fmtN(keys.length)} total</span>
      </div>

      <div className="card">
        <div className="card-title">All keys
          <span className="sub">limit multiplier is per-issuer (applies to every one of their keys)</span>
        </div>
        <TableWrap label="All keys">
          <table className="data-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Type</th>
                <th>Owner</th>
                <th>Scope</th>
                {hasUsage && <th className="amount">Req 24h</th>}
                {hasUsage && <th className="amount">Throttled</th>}
                {hasUsage && <th className="amount">Req/min</th>}
                <th className="amount">Limit</th>
                <th className="ts">Issued</th>
                <th className="ts">Expires</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {keys.length === 0 && (
                <tr><td colSpan={hasUsage ? 12 : 9} className="empty">No API keys.</td></tr>
              )}
              {keys.map((k) => {
                const mult = (k.owner_uuid && multByOwner.get(k.owner_uuid)) ?? '1.00';
                const isDefault = parseFloat(mult) === 1;
                const u = usage.get(k.key_id);
                return (
                  <tr key={k.key_id}>
                    <td><span className="mono">#{k.key_id}</span></td>
                    <td><span className="mono" style={{ color: 'var(--fg-muted)' }}>{k.key_type}</span></td>
                    <td><Player name={k.owner_name} uuid={k.owner_uuid} /></td>
                    <td>
                      {k.firm_id && k.firm_name ? (
                        <Link href={`/firms/${encodeURIComponent(k.firm_name)}` as Route} className="rowlink" prefetch={false}>
                          Firm: {k.firm_name}
                        </Link>
                      ) : k.account_id ? (
                        <Link href={`/accounts/${k.account_id}` as Route} className="rowlink" prefetch={false}>
                          Acct: {k.account_name ?? `#${k.account_id}`}
                        </Link>
                      ) : <span className="muted">—</span>}
                    </td>
                    {hasUsage && <td className="amount mono">{fmtN(u?.requests24h ?? 0)}</td>}
                    {hasUsage && (
                      <td className={`amount mono ${(u?.throttled24h ?? 0) > 0 ? 'neg' : ''}`}>
                        {fmtN(u?.throttled24h ?? 0)}
                      </td>
                    )}
                    {hasUsage && (
                      <td className="amount mono">
                        {(u?.reqPerMin ?? 0) >= 0.05 ? (u?.reqPerMin ?? 0).toFixed(1) : '—'}
                      </td>
                    )}
                    <td className={`amount mono ${!isDefault ? 'pos' : ''}`}>×{mult}</td>
                    <td className="ts"><LocalTimestamp value={k.issued_at} /></td>
                    <td className="ts"><LocalTimestamp value={k.expires_at} /></td>
                    <td>
                      {k.revoked
                        ? <span className="badge badge-archived">Revoked</span>
                        : <span className="badge badge-active">Active</span>}
                    </td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      {k.owner_uuid && hasPermission(viewer, 'rate_limits.update') && (
                        <LimitDialog
                          ownerUuid={k.owner_uuid}
                          ownerName={k.owner_name ?? k.owner_uuid.slice(0, 8) + '…'}
                          currentMultiplier={mult}
                        />
                      )}{' '}
                      {!k.revoked && (
                        <>
                          {hasPermission(viewer, 'api_keys.rotate') && <RotateButton keyId={k.key_id} />}{' '}
                          {hasPermission(viewer, 'api_keys.revoke') && <RevokeButton keyId={k.key_id} />}
                        </>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </TableWrap>
      </div>
    </>
  );
}
