import Link from 'next/link';
import styles from './page.module.css';
import { TableWrap } from '@democracycraft/design-system';
import { getViewer, hasPermission } from '@/lib/auth';
import { PrivacyGate } from '@/components/PrivacyGate';
import { listRoleGrantsForAdmin } from '@/lib/services/role-grants';

export const dynamic = 'force-dynamic';
export default async function RoleGrantsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'roles.read') || !hasPermission(viewer, 'groups.read')) return <PrivacyGate kind={viewer.anon ? 'login' : 'private'} title="Role and group read access required" />;
  const params = await searchParams;
  const value = (key: string) => typeof params[key] === 'string' ? params[key] as string : '';
  const filters = { q: value('q').slice(0, 100), role: value('role').slice(0, 32), source: value('source'), status: value('status'), page: Number(value('page')) || 1 };
  const result = await listRoleGrantsForAdmin(filters);
  const pageUrl = (page: number) => `/admin/role-grants?${new URLSearchParams({ q: filters.q, role: filters.role, source: filters.source, status: filters.status, page: String(page) })}` as '/admin/role-grants';
  return <>
    <div className="page-heading"><h1>Role grants</h1><span className="sub">One row per role source. Active sources combine into effective roles; permissions depend on each role’s current definition.</span></div>
    <p>The player baseline applies to every signed-in user; known players are listed here. LuckPerms sources reflect resolved inheritance or effective permission checks. The upstream inheritance path and LuckPerms granting actor are not recorded. Explorer grant timestamps and actors are shown when available. Inactive memberships do not grant access.</p>
    <form className={`card ${styles.filters}`} action="/admin/role-grants">
      <label className={styles.field}>Search user, UUID, group or source node <input className="input" name="q" defaultValue={filters.q} maxLength={100} /></label>
      <label className={styles.field}>Role key <input className="input" name="role" defaultValue={filters.role} maxLength={32} /></label>
      <div className={styles.field}><label htmlFor="grant-source">Source</label><select id="grant-source" name="source" defaultValue={filters.source}><option value="">All sources</option>{['baseline', 'direct', 'manual', 'luckperms', 'business', 'realty'].map(s => <option key={s}>{s}</option>)}</select></div>
      <div className={styles.field}><label htmlFor="grant-status">Status</label><select id="grant-status" name="status" defaultValue={filters.status}><option value="">All statuses</option><option value="active">Active</option><option value="inactive">Inactive</option></select></div>
      <div className={styles.actions}><button className="btn" type="submit">Filter</button><Link href="/admin/role-grants">Reset</Link></div>
    </form>
    <div className="card"><TableWrap label="Role grant provenance"><table className="data-table">
      <thead><tr><th>User</th><th>Role</th><th>Originating group</th><th>Source</th><th>Source node / selector</th><th>Status</th><th>Last synced (UTC)</th><th>Recorded at (UTC)</th><th>Recorded by</th></tr></thead>
      <tbody>{result.rows.map((row, i) => <tr key={`${row.uuid}:${row.role}:${row.source}:${row.groupId}:${i}`}>
        <td>{row.name ?? 'Unknown player'}<div className="sub mono">{row.uuid}</div></td><td>{row.roleName}<div className="sub">{row.role}</div></td>
        <td>{row.groupId ? <Link href={`/admin/groups/${row.groupId}`}>{row.group}</Link> : '—'}</td><td>{row.source}</td><td>{row.selector ?? '—'}</td>
        <td>{row.active ? 'Active' : 'Inactive (expired or source disabled/changed)'}</td><td>{row.syncedAt ?? '—'}</td><td>{row.addedAt ?? '—'}</td><td className="mono">{row.addedBy ?? 'Not recorded'}</td>
      </tr>)}</tbody>
    </table></TableWrap>{result.rows.length === 0 && <p>No role grants match these filters.</p>}</div>
    <nav aria-label="Role grant pages">{result.page > 1 && <Link href={pageUrl(result.page - 1)}>Previous</Link>} <span>Page {result.page}</span> {result.hasMore && <Link href={pageUrl(result.page + 1)}>Next</Link>}</nav>
  </>;
}
