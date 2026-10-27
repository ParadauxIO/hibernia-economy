import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { PageHeader, Tag, When } from '@democracycraft/design-system';
import { CursorPager } from '@democracycraft/design-system/next';
import { DataTable, columnOptions, type Column } from '@democracycraft/design-system/table';
import { ExportMenuForViewer } from '@/components/ExportMenuForViewer';
import { FilterBar } from '@/components/FilterBar';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { searchAdminPlayersAction } from '@/lib/actions/admin-audit';
import { exportHrefs } from '@/lib/export/href';
import { buildMetadata } from '@/lib/metadata';
import { getAuditLog } from '@/lib/services/audit';
import type { AuditEntry } from '@/lib/sql/audit';
import { AUDIT_ACTIONS, AUDIT_TARGET_TYPES, auditTable } from '@/lib/tables/audit';
import { parseTableState, tableHref, withCursor, withSort, type TableState } from '@/lib/tables/core';
import { AdminGate } from '../_components/AdminGate';
import { AdminTableEmpty, RouterLink } from '../_components/table';
import { AuditDetailButton } from './audit-detail-button';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Audit log', description: 'Who viewed or changed what in the {server} economy explorer.', path: '/admin/audit' });
}

const TABLE_ID = 'audit-table';
const PATH = '/admin/audit';
const ACTION_LABEL = new Map<string, string>(AUDIT_ACTIONS.map((a) => [a.action, a.label]));
const TARGET_LABEL = new Map<string, string>(AUDIT_TARGET_TYPES.map((t) => [t.value, t.label]));

/**
 * The target as a kind and value. Older rows stored groups, roles, keys and
 * webhooks as `global` + `group:12`; read those as the kind they name.
 */
function targetOf(type: string | null, id: string | null): { kind: string | null; value: string | null } {
  if (type === 'global' && id && /^[a-z_]+:./.test(id)) {
    const [prefix = '', ...rest] = id.split(':');
    return { kind: prefix === 'key' ? 'api_key' : prefix, value: rest.join(':') };
  }
  return { kind: type, value: id };
}

/** Where a target can be opened, if anywhere. */
function targetHref(kind: string | null, value: string | null): AppHref | null {
  if (!kind || !value) return null;
  switch (kind) {
    case 'account': return /^\d+$/.test(value) ? `/accounts/${value}` : null;
    case 'firm': return /^\d+$/.test(value) ? `/admin/firms/${value}` : null;
    case 'player': return /^[0-9a-f-]{36}$/i.test(value) ? `/admin/players/${value.toLowerCase()}` : null;
    case 'transaction': return /^\d+$/.test(value) ? `/transactions/${value}` : null;
    case 'group': return /^\d+$/.test(value) ? `/admin/groups/${value}` : null;
    case 'role': return `/admin/roles/${encodeURIComponent(value)}`;
    default: return null;
  }
}

const columns: Column<AuditEntry>[] = [
  { key: 'at', header: 'Time', sort: 'at', sortFirst: 'desc', cell: (e) => <When value={e.at} time /> },
  {
    key: 'actor',
    header: 'Actor',
    cell: (e) => (
      <>
        {e.actor_uuid
          ? <Link href={`/admin/players/${e.actor_uuid}`} prefetch={false}>{e.actor_name ?? e.actor_uuid.slice(0, 8)}</Link>
          : <span>{e.actor_name ?? e.actor_sub}</span>}
        {e.actor_role && <span className="dt-sub">{` · ${e.actor_role}`}</span>}
      </>
    ),
  },
  {
    key: 'action',
    header: 'Action',
    cell: (e) => <span>{ACTION_LABEL.get(e.action) ?? e.action}{e.has_detail && <span className="dt-sub"> · changes recorded</span>}</span>,
  },
  {
    key: 'target',
    header: 'Target',
    cell: (e) => {
      const { kind, value } = targetOf(e.target_type, e.target_id);
      const href = targetHref(kind, value);
      const text = `${TARGET_LABEL.get(kind ?? '') ?? kind ?? '—'}${value ? ` ${value}` : ''}`;
      return href ? <Link href={href} prefetch={false}>{text}</Link> : <span>{text}</span>;
    },
  },
  { key: 'path', header: 'Path', secondary: true, optional: true, cell: (e) => <span className="mono">{e.path}</span> },
  {
    key: 'outcome',
    header: 'Outcome',
    secondary: true,
    optional: true,
    cell: (e) => <Tag tone={e.outcome >= 400 ? 'neg' : 'neutral'}>{e.outcome >= 400 ? `Denied (${e.outcome})` : 'Allowed'}</Tag>,
  },
  {
    key: 'details',
    header: <span className="sr-only">Details</span>,
    label: 'Details',
    align: 'end',
    cell: (e) => <AuditDetailButton auditId={e.audit_id} actionLabel={ACTION_LABEL.get(e.action) ?? e.action} />,
  },
];

export default async function AdminAuditPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'audit.read')) return <AdminGate viewer={viewer} title="Audit log access required" permission="audit.read" />;

  const state = parseTableState(await searchParams, auditTable);
  await auditView(viewer, { path: PATH, targetType: 'global' });
  const log = await getAuditLog(viewer, state);
  const href = (s: TableState<typeof auditTable>) => tableHref(PATH, s, auditTable);
  const describe = { entityLabels: log.entityLabels };

  return (
    <>
      <PageHeader
        title="Audit log"
        subtitle="Privileged views, relationship reads of government and business accounts, and every admin change with its values before and after."
      />

      <FilterBar
        table={auditTable}
        total={log.rows.length}
        totalIsFloor={log.older !== null}
        counts={{ action: log.actionCounts }}
        entityLabels={log.entityLabels}
        searchEntity={searchAdminPlayersAction}
        columns={columnOptions(columns)}
        tableId={TABLE_ID}
        exportSlot={<ExportMenuForViewer hrefs={exportHrefs('audit', state, auditTable)} allDescription="Every matching entry, up to 100,000" />}
      />

      <DataTable
        id={TABLE_ID}
        label="Audit log"
        columns={columns}
        rows={log.rows}
        rowKey={(e) => e.audit_id}
        sort={state.sort}
        sortHref={(field, dir) => href(withSort(state, field, dir, auditTable))}
        link={RouterLink}
        density="compact"
        empty={<AdminTableEmpty pathname={PATH} state={state} table={auditTable} describe={describe} />}
      />

      <CursorPager
        newerHref={state.cursor ? href(withCursor(state, null)) : null}
        olderHref={log.older ? href(withCursor(state, log.older)) : null}
        summary={state.cursor ? 'Older entries' : `Newest ${log.rows.length.toLocaleString('en-US')}${log.older ? '+' : ''} entries`}
      />
    </>
  );
}
