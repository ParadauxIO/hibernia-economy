import Link from 'next/link';
import type { Route } from 'next';
import { PageHeader, Section, Tag } from '@democracycraft/design-system';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { fmtAmtFull } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import { searchFirmsByName, type AdminFirmRow } from '@/lib/sql/firm';
import { treasuryAdminConfigured } from '@/lib/treasury';
import { AdminGate } from '../_components/AdminGate';
import { ManageDialog } from '../_components/ManageDialog';
import { RouterLink } from '../_components/table';
import styles from '../admin.module.css';
import { FirmAdminPanel } from './firm-admin-panel';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Firms', description: 'Administer firms (disband / rename).', path: '/admin/firms' });
}

/**
 * Admin firm-management tool (PAR-209): a deliberately separate, admin-gated
 * surface (not the public firm page) for rename, details and the destructive
 * disband. Money movement runs through the ledger-authoritative treasury admin
 * API; every action is audited with its values before and after.
 */
export default async function AdminFirmsPage({ searchParams }: { searchParams: Promise<{ q?: string | string[] }> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'firm.admin.read')) return <AdminGate viewer={viewer} title="Firm administration access required" permission="firm.admin.read" />;
  const { q } = await searchParams;
  const query = (typeof q === 'string' ? q : '').trim().slice(0, 64);
  await auditView(viewer, { path: '/admin/firms', targetType: 'global' });

  const configured = treasuryAdminConfigured();
  const firms = query ? await searchFirmsByName(query) : [];

  const columns: Column<AdminFirmRow>[] = [
    {
      key: 'name',
      header: 'Firm',
      cell: (f) => (
        <>
          <Link href={`/admin/firms/${f.firm_id}` as Route} prefetch={false}>{f.display_name}</Link>
          <span className="dt-sub">{` #${f.firm_id}`}</span>
          {f.archived ? <> <Tag tone="neg">Disbanded</Tag></> : null}
        </>
      ),
    },
    { key: 'hq', header: 'HQ region', secondary: true, cell: (f) => f.hq_region ?? '—' },
    { key: 'balance', header: 'Balance', numeric: true, cell: (f) => (f.archived ? '—' : fmtAmtFull(f.total_balance)) },
    {
      key: 'manage',
      header: <span className="sr-only">Manage</span>,
      label: 'Manage',
      align: 'end',
      cell: (f) => (f.archived ? null : (
        <ManageDialog label={`Manage ${f.display_name}`} title={f.display_name}
          description="Each change goes through the Treasury admin API and is recorded in the audit log with its old and new values.">
          <FirmAdminPanel firmId={f.firm_id} name={f.display_name} totalBalance={f.total_balance} discordUrl={f.discord_url} hqRegion={f.hq_region} disabled={!configured} />
        </ManageDialog>
      )),
    },
  ];

  return (
    <>
      <PageHeader title="Firms" subtitle="Rename a firm, update its details, or disband it. Disbanding sweeps its balances to the proprietor." />

      {!configured && (
        <p className="state-error" role="alert">
          The Treasury admin API is not configured in this environment (TREASURY_API_BASE_URL / TREASURY_ADMIN_TOKEN), so changes are unavailable.
        </p>
      )}

      <Section id="find-firm" title="Find a firm">
        <form method="get" className={styles.row} role="search" aria-label="Find a firm">
          <label className={`field ${styles.grow}`}>
            <span className="field-label">Firm name</span>
            <input className="input" type="search" name="q" defaultValue={query} placeholder="Search firms by name…" />
          </label>
          <button className="btn" type="submit">Search</button>
        </form>
      </Section>

      {query && (
        <DataTable
          id="admin-firms-table"
          label={`Firms matching “${query}”`}
          columns={columns}
          rows={firms}
          rowKey={(f) => f.firm_id}
          link={RouterLink}
          empty={<p className="dt-empty-text">No firms match “{query}”.</p>}
        />
      )}
    </>
  );
}
