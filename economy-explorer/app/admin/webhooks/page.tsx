import Link from 'next/link';
import type { Route } from 'next';
import { PageHeader, Section, Tag, When } from '@democracycraft/design-system';
import { DataTable, TablePager, columnOptions, type Column } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { Player } from '@/components/Player';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { fmtN } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import { getAdminWebhooks } from '@/lib/services/admin';
import type { AdminWebhookRow } from '@/lib/sql/webhook';
import { adminWebhooksTable } from '@/lib/tables/admin';
import { parseTableState, tableHref, withPage, withSort, type TableState } from '@/lib/tables/core';
import { AdminGate } from '../_components/AdminGate';
import { AdminTableEmpty, RouterLink } from '../_components/table';
import { AdminWebhookRowActions } from './admin-webhook-row-actions';
import { CreateAdminWebhookForm } from './create-admin-webhook-form';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Webhooks', description: 'Admin: every transaction-feed webhook on {server}.', path: '/admin/webhooks' });
}

const TABLE_ID = 'admin-webhooks-table';
const PATH = '/admin/webhooks';

const columns: Column<AdminWebhookRow>[] = [
  {
    key: 'url',
    header: 'Endpoint',
    sort: 'id',
    cell: (h) => <><span className="mono">#{h.id}</span>{' '}<span className="mono">{h.url}</span>{h.isDiscord && <span className="dt-sub"> · Discord embed</span>}</>,
  },
  { key: 'owner', header: 'Owner', secondary: true, cell: (h) => <Player name={h.ownerName} uuid={h.ownerUuid} /> },
  {
    key: 'scope',
    header: 'Scope',
    cell: (h) => (h.scope === 'firm'
      ? (h.firmName ? <Link href={`/firms/${encodeURIComponent(h.firmName)}` as Route} prefetch={false}>Firm: {h.firmName}</Link> : `Firm #${h.firmId}`)
      : h.accountId ? <Link href={`/accounts/${h.accountId}` as Route} prefetch={false}>Account: {h.accountName ?? `#${h.accountId}`}</Link> : '—'),
  },
  {
    key: 'status',
    header: 'Status',
    cell: (h) => (h.active ? <Tag tone="pos">Active</Tag> : <Tag tone={h.disabledAt ? 'neg' : 'neutral'}>{h.disabledAt ? 'Auto-disabled' : 'Paused'}</Tag>),
  },
  { key: 'failures', header: 'Failures', sort: 'failures', numeric: true, secondary: true, cell: (h) => fmtN(h.consecutiveFailures) },
  { key: 'source', header: 'Created through', secondary: true, optional: true, cell: (h) => (h.viaApiKey ? 'API key' : 'Explorer') },
  { key: 'created', header: 'Created', sort: 'created', sortFirst: 'desc', secondary: true, optional: true, cell: (h) => <When value={h.createdAt} /> },
  { key: 'actions', header: <span className="sr-only">Actions</span>, label: 'Actions', align: 'end', cell: (h) => <AdminWebhookRowActions id={h.id} active={h.active} url={h.url} /> },
];

export default async function AdminWebhooksPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'webhooks.read.all')) return <AdminGate viewer={viewer} title="Webhook access required" permission="webhooks.read.all" />;
  await auditView(viewer, { path: PATH, targetType: 'global' });

  const state = parseTableState(await searchParams, adminWebhooksTable);
  const { rows, total } = await getAdminWebhooks(viewer, state);
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof adminWebhooksTable>) => tableHref(PATH, s, adminWebhooksTable);

  return (
    <>
      <PageHeader title="Webhooks" subtitle="Every transaction-feed webhook, across all accounts and firms." />

      {hasPermission(viewer, 'webhooks.create.all') && (
        <Section
          id="create-webhook"
          title="Create a webhook for an account"
          description="Attributed to the account's owner, who also sees it under their own webhooks. The endpoint must be a public https:// URL; the signing secret is shown once. Discord webhook URLs are delivered as a rich embed."
        >
          <CreateAdminWebhookForm />
        </Section>
      )}

      <Section id="all-webhooks" title="All webhooks" divider={hasPermission(viewer, 'webhooks.create.all')}>
        <FilterBar table={adminWebhooksTable} total={total} columns={columnOptions(columns)} tableId={TABLE_ID} />
        <DataTable
          id={TABLE_ID}
          label="Webhooks"
          columns={columns}
          rows={rows}
          rowKey={(h) => h.id}
          sort={state.sort}
          sortHref={(field, dir) => href(withSort(state, field, dir, adminWebhooksTable))}
          link={RouterLink}
          density="compact"
          empty={<AdminTableEmpty pathname={PATH} state={state} table={adminWebhooksTable} />}
        />
        <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} />
      </Section>
    </>
  );
}
