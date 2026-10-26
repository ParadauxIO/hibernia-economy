import { adminToolsFor, canAccessAdmin, type AdminTool } from '@/lib/auth/admin';
import { getViewer } from '@/lib/auth/viewer';
import { AdminGate } from './_components/AdminGate';
import { AdminSidebar, type AdminNavGroup } from './_components/AdminSidebar';

const GROUP_ORDER: AdminTool['group'][] = ['Economy', 'Access', 'Integrations', 'Oversight'];

// Gates /admin/* to users with at least one tool permission and renders the
// workspace sidebar. Individual pages keep their own gate as defence-in-depth.
export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  const viewer = await getViewer();
  if (viewer.anon || !canAccessAdmin(viewer)) {
    return <AdminGate viewer={viewer} title={viewer.anon ? 'Admin' : 'Administrative permission required'} />;
  }
  const tools = adminToolsFor(viewer);
  const groups: AdminNavGroup[] = [
    { items: [{ label: 'Overview', href: '/admin' }] },
    ...GROUP_ORDER
      .map((group) => ({ label: group, items: tools.filter((t) => t.group === group).map((t) => ({ label: t.title, href: t.href })) }))
      .filter((g) => g.items.length > 0),
  ];
  return <AdminSidebar groups={groups}>{children}</AdminSidebar>;
}
