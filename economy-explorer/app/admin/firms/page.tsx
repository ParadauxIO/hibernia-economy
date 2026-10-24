import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import type { Route } from 'next';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { searchFirmsByName } from '@/lib/sql/firm';
import { treasuryAdminConfigured } from '@/lib/treasury';
import { fmtAmt } from '@/lib/format';
import { FirmAdminPanel } from './firm-admin-panel';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Firms', description: 'Administer firms (disband / rename).', path: '/admin/firms' });
}

/**
 * Admin firm-management tool (PAR-209): a deliberately-separate, admin-gated
 * surface (not the public firm page) for the destructive disband + rename
 * operations. Money movement runs through the ledger-authoritative treasury
 * admin API; every action is audited.
 */
export default async function AdminFirmsPage({ searchParams }: { searchParams: Promise<{ q?: string }> }) {
  const viewer = await getViewer();
  if (viewer.anon || !hasPermission(viewer, 'firm.admin.read')) return null; // layout already gates; defence in depth
  const { q } = await searchParams;
  const query = (q ?? '').trim();
  await auditView(viewer, { path: '/admin/firms', targetType: 'global' });

  const configured = treasuryAdminConfigured();
  const firms = query ? await searchFirmsByName(query) : [];

  return (
    <>
      <div className="page-heading">
        <h1>Firms</h1>
        <span className="sub">disband or rename a firm — destructive actions, audited</span>
      </div>

      {!configured && (
        <p className="state-error" style={{ marginBottom: 12 }}>
          Treasury admin API is not configured in this environment (TREASURY_API_BASE_URL / TREASURY_ADMIN_TOKEN).
          Disband and rename are unavailable.
        </p>
      )}

      <form method="get" style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <input className="input" name="q" defaultValue={query} placeholder="Search firms by name…" style={{ minWidth: 280 }} />
        <button className="btn" type="submit">Search</button>
      </form>

      {query && firms.length === 0 && <p className="muted">No firms match “{query}”.</p>}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {firms.map((f) => (
          <div key={f.firm_id} className="card" style={{ padding: 14 }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
              <strong style={{ fontSize: 15 }}>{f.display_name}</strong>
              <span className="muted small">#{f.firm_id}</span>
              {f.archived ? (
                <span className="badge" style={{ background: 'var(--bad)', color: '#fff' }}>disbanded</span>
              ) : (
                <span className="muted small">balance {fmtAmt(f.total_balance)}</span>
              )}
              <Link href={`/admin/firms/${f.firm_id}` as Route} className="rowlink small">report →</Link>
            </div>
            {!f.archived && (
              <FirmAdminPanel
                firmId={f.firm_id}
                name={f.display_name}
                totalBalance={f.total_balance}
                discordUrl={f.discord_url}
                hqRegion={f.hq_region}
                disabled={!configured}
              />
            )}
          </div>
        ))}
      </div>
    </>
  );
}
