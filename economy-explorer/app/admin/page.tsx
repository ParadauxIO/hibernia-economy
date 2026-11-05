import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { Suspense, type ReactNode } from 'react';
import { DescriptionList, EmptyState, ErrorState, MetricCard, MetricRow, Num, PageHeader, Section, SkeletonMetricRow, SkeletonTable, Tag, When } from '@democracycraft/design-system';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { canAccessAdmin } from '@/lib/auth/admin';
import { hasPermission } from '@/lib/auth/policy';
import { auditView } from '@/lib/audit';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { buildMetadata } from '@/lib/metadata';
import {
  KEY_EXPIRY_DAYS,
  getDashboardApiKeys,
  getDashboardChanges,
  getDashboardDenied,
  getDashboardFlags,
  getDashboardLedgerWriters,
  getDashboardWebhooks,
  type LedgerWriter,
} from '@/lib/services/adminDashboard';
import type { AuditEntry } from '@/lib/sql/audit';
import { NO_PLUGIN } from '@/lib/tables/transactions';
import { RouterLink } from './_components/table';
import { ACTION_LABEL, targetHref, targetOf, targetText } from './audit/targets';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin', description: 'Admin dashboard for the {server} economy.', path: '/admin' });
}

const RECENT_CHANGES = 10;

// The admin dashboard: what needs attention, what changed and what is running.
// Each panel needs the permission of the tool it summarises, streams on its
// own, and fails on its own. The tools themselves are in the sidebar.
export default async function AdminDashboardPage() {
  const viewer = await getViewer();
  if (!canAccessAdmin(viewer)) return null; // layout renders the access gate
  await auditView(viewer, { path: '/admin', targetType: 'global' });
  const can = (p: Parameters<typeof hasPermission>[1]) => hasPermission(viewer, p);
  const attention = can('webhooks.read.all') || can('api_keys.read') || can('audit.read');

  return (
    <>
      <PageHeader title="Admin" subtitle="What needs attention, what changed and what is running. Every tool is in the admin navigation." />
      {attention && (
        <Section id="admin-attention" title="Needs attention" description="Figures refresh about once a minute.">
          <Suspense fallback={<SkeletonMetricRow count={4} />}>
            <AttentionPanel viewer={viewer} />
          </Suspense>
        </Section>
      )}
      {can('audit.read') && (
        <Section id="admin-changes" title="Recent changes" description="The latest admin changes from the audit log." actions={<Link href="/admin/audit" prefetch={false}>Open audit log</Link>} divider>
          <Suspense fallback={<SkeletonTable rows={5} cols={4} />}>
            <ChangesPanel viewer={viewer} />
          </Suspense>
        </Section>
      )}
      {can('transactions.read') && (
        <Section id="admin-ledger" title="Ledger writers" description="Each plugin's latest settled transaction. A plugin that has gone quiet may have stopped recording." divider>
          <Suspense fallback={<SkeletonTable rows={5} cols={3} />}>
            <LedgerPanel viewer={viewer} />
          </Suspense>
        </Section>
      )}
      {can('feature_flags.read') && (
        <Section id="admin-flags" title="Feature flags" actions={<Link href="/admin/feature-flags" prefetch={false}>Manage flags</Link>} divider>
          <Suspense fallback={<SkeletonTable rows={3} cols={2} />}>
            <FlagsPanel viewer={viewer} />
          </Suspense>
        </Section>
      )}
    </>
  );
}

/** Renders a panel, or an error in its place so the rest of the dashboard still loads. */
async function panel(render: () => Promise<ReactNode>): Promise<ReactNode> {
  try {
    return await render();
  } catch (err) {
    console.error('admin dashboard panel failed', err);
    return <ErrorState>Reload the page to try again.</ErrorState>;
  }
}

function Review({ href, children }: { href: AppHref; children: ReactNode }) {
  return <Link href={href} prefetch={false}>{children}</Link>;
}

async function AttentionPanel({ viewer }: { viewer: Viewer }) {
  return panel(async () => {
    const [webhooks, keys, denied] = await Promise.all([
      hasPermission(viewer, 'webhooks.read.all') ? getDashboardWebhooks(viewer) : null,
      hasPermission(viewer, 'api_keys.read') ? getDashboardApiKeys(viewer) : null,
      hasPermission(viewer, 'audit.read') ? getDashboardDenied(viewer) : null,
    ]);
    return (
      <MetricRow label="Needs attention">
        {webhooks && (
          <MetricCard
            label="Failing webhooks"
            value={<Num value={webhooks.failing} />}
            info="Live webhooks whose latest delivery failed. Repeated failures disable a webhook automatically."
            meta={<Review href="/admin/webhooks?f=status:in:disabled">{webhooks.disabled} auto-disabled</Review>}
          />
        )}
        {webhooks && (
          <MetricCard
            label="Overdue deliveries"
            value={<Num value={webhooks.overdue} />}
            info="Pending deliveries to live webhooks that are past their next attempt. A growing number means the dispatcher is behind or stuck."
            meta={webhooks.oldestOverdueAt
              ? <>Oldest due <When value={webhooks.oldestOverdueAt} /></>
              : webhooks.dispatcherLag > 0 ? `${webhooks.dispatcherLag} transactions not yet scanned` : 'Dispatcher up to date'}
          />
        )}
        {keys && (
          <MetricCard
            label={`Keys expiring in ${KEY_EXPIRY_DAYS} days`}
            value={<Num value={keys.expiringSoon} />}
            meta={<Review href="/admin/api-keys?f=status:in:active&sort=expires">{keys.active} active keys</Review>}
          />
        )}
        {denied && (
          <MetricCard
            label="Denied requests, 24 hours"
            value={<Num value={denied.count} />}
            info="Pages and actions the explorer refused because the viewer lacked a permission."
            meta={denied.count > 0 ? `From ${denied.actors} ${denied.actors === 1 ? 'person' : 'people'}` : 'None refused'}
          />
        )}
      </MetricRow>
    );
  });
}

const changeColumns: Column<AuditEntry>[] = [
  { key: 'at', header: 'Time', cell: (e) => <When value={e.at} time /> },
  {
    key: 'actor',
    header: 'Actor',
    cell: (e) => (e.actor_uuid
      ? <Link href={`/admin/players/${e.actor_uuid}`} prefetch={false}>{e.actor_name ?? e.actor_uuid.slice(0, 8)}</Link>
      : <span>{e.actor_name ?? e.actor_sub}</span>),
  },
  {
    key: 'action',
    header: 'Action',
    cell: (e) => <span>{ACTION_LABEL.get(e.action) ?? e.action}{e.outcome >= 400 && <> <Tag tone="neg">Denied</Tag></>}</span>,
  },
  {
    key: 'target',
    header: 'Target',
    cell: (e) => {
      const { kind, value } = targetOf(e.target_type, e.target_id);
      const href = targetHref(kind, value);
      const text = targetText(kind, value);
      return href ? <Link href={href} prefetch={false}>{text}</Link> : <span>{text}</span>;
    },
  },
];

async function ChangesPanel({ viewer }: { viewer: Viewer }) {
  return panel(async () => {
    const rows = await getDashboardChanges(viewer, RECENT_CHANGES);
    if (rows.length === 0) return <EmptyState title="No admin changes yet" compact />;
    return <DataTable id="admin-recent-changes" label="Recent changes" columns={changeColumns} rows={rows} rowKey={(e) => e.audit_id} link={RouterLink} />;
  });
}

const ledgerColumns: Column<LedgerWriter>[] = [
  {
    key: 'plugin',
    header: 'Plugin',
    cell: (w) => <Link href={`/transactions?f=plugin:in:${encodeURIComponent(w.plugin ?? NO_PLUGIN)}`} prefetch={false}>{w.plugin ?? 'No plugin'}</Link>,
  },
  { key: 'last', header: 'Latest transaction', cell: (w) => <When value={w.lastAt} time /> },
  { key: 'day', header: 'Last 24 hours', align: 'end', cell: (w) => <Num value={w.last24h} /> },
];

async function LedgerPanel({ viewer }: { viewer: Viewer }) {
  return panel(async () => {
    const rows = await getDashboardLedgerWriters(viewer);
    if (rows.length === 0) return <EmptyState title="Nothing has written to the ledger yet" compact />;
    return <DataTable id="admin-ledger-writers" label="Ledger writers" columns={ledgerColumns} rows={rows} rowKey={(w) => w.plugin ?? ''} link={RouterLink} />;
  });
}

async function FlagsPanel({ viewer }: { viewer: Viewer }) {
  return panel(async () => {
    const flags = await getDashboardFlags(viewer);
    if (flags.length === 0) return <EmptyState title="No feature flags registered" compact />;
    return (
      <DescriptionList
        items={flags.map((f) => ({
          label: <span className="mono">{f.name}</span>,
          value: (
            <>
              <Tag tone={f.enabled === 1 ? 'accent' : 'neutral'}>{f.enabled === 1 ? 'Enabled' : 'Disabled'}</Tag>
              {' '}<span className="muted">Changed <When value={f.updated_at} /> by {f.updated_by ?? 'a database migration'}</span>
            </>
          ),
        }))}
      />
    );
  });
}
