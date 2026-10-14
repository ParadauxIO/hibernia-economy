import { ADMIN_TOOLS, canAccessAdmin } from '@/lib/auth/admin';
import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import type { Route } from 'next';
import { auditView } from '@/lib/audit';
import { getViewer } from '@/lib/auth/viewer';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin', description: 'Admin tools for the {server} economy.', path: '/admin' });
}

export default async function AdminHomePage() {
  const viewer = await getViewer();
  if (!canAccessAdmin(viewer)) return null; // layout renders the access gate
  await auditView(viewer, { path: '/admin', targetType: 'global' });

  return (
    <>
      <div className="page-heading">
        <h1>Admin</h1>
        <span className="sub">tools for managing the {''}economy explorer</span>
      </div>

      <div className="admin-tool-grid">
        {ADMIN_TOOLS.filter(t => hasPermission(viewer, t.permission)).map((t) => (
          <Link key={t.href} href={t.href as Route} className="card admin-tool-card" prefetch={false}>
            <div className="card-title">{t.title} →</div>
            <p className="muted small" style={{ margin: 0 }}>{t.desc}</p>
          </Link>
        ))}
      </div>
    </>
  );
}
