import Link from 'next/link';
import type { Route } from 'next';
import { DescriptionList, PageHeader, Section } from '@democracycraft/design-system';
import { adminToolsFor, canAccessAdmin, type AdminTool } from '@/lib/auth/admin';
import { auditView } from '@/lib/audit';
import { getViewer } from '@/lib/auth/viewer';
import { buildMetadata } from '@/lib/metadata';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin', description: 'Admin tools for the {server} economy.', path: '/admin' });
}

const GROUPS: { group: AdminTool['group']; description: string }[] = [
  { group: 'Economy', description: 'Players, accounts and firms. Money moves only through the Treasury admin API.' },
  { group: 'Access', description: 'Who can see what: groups, roles and where each grant comes from.' },
  { group: 'Integrations', description: 'API keys and webhooks.' },
  { group: 'Oversight', description: 'The audit trail of views and changes.' },
];

export default async function AdminHomePage() {
  const viewer = await getViewer();
  if (!canAccessAdmin(viewer)) return null; // layout renders the access gate
  await auditView(viewer, { path: '/admin', targetType: 'global' });
  const tools = adminToolsFor(viewer);

  return (
    <>
      <PageHeader title="Admin" subtitle="The tools your roles allow. Each change needs its own permission and is recorded in the audit log." />
      {GROUPS.map(({ group, description }) => {
        const items = tools.filter((t) => t.group === group);
        if (items.length === 0) return null;
        return (
          <Section key={group} id={`admin-${group.toLowerCase()}`} title={group} description={description} divider>
            <DescriptionList
              items={items.map((t) => ({
                label: <Link href={t.href as Route} prefetch={false}>{t.title}</Link>,
                value: t.desc,
              }))}
            />
          </Section>
        );
      })}
    </>
  );
}
