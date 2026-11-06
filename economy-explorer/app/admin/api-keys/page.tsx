import Link from 'next/link';
import { PageHeader, Tag, When } from '@democracycraft/design-system';
import { DataTable, TablePager, columnOptions, type Column } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { Player } from '@/components/Player';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { searchAdminPlayersAction } from '@/lib/actions/admin-audit';
import { fmtN } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import type { KeyUsage } from '@/lib/prometheus';
import { getAdminApiKeys } from '@/lib/services/admin';
import type { AdminApiKeyRow } from '@/lib/sql/admin';
import { adminApiKeysTable } from '@/lib/tables/admin';
import { parseTableState, tableHref, withPage, withSort, type TableState } from '@/lib/tables/core';
import { AdminGate } from '../_components/AdminGate';
import { AdminTableEmpty, RouterLink } from '../_components/table';
import { LimitDialog } from './limit-dialog';
import { RevokeButton } from './revoke-button';
import { RotateButton } from './rotate-button';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'API keys', description: 'Manage programmatic API keys for the {server} economy API.', path: '/admin/api-keys' });
}

const TABLE_ID = 'admin-api-keys-table';
const PATH = '/admin/api-keys';
const TYPE_LABEL: Record<string, string> = { PERSONAL: 'Personal', BUSINESS: 'Business', GOVERNMENT: 'Government', SERVICE: 'Service' };

export default async function AdminApiKeysPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'api_keys.read')) return <AdminGate viewer={viewer} title="API key access required" permission="api_keys.read" />;
  await auditView(viewer, { path: PATH, targetType: 'global' });

  const state = parseTableState(await searchParams, adminApiKeysTable);
  const { rows, total, active, usage, entityLabels } = await getAdminApiKeys(viewer, state);
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof adminApiKeysTable>) => tableHref(PATH, s, adminApiKeysTable);
  const hasUsage = usage.size > 0;
  const canLimit = hasPermission(viewer, 'rate_limits.update');
  const canRotate = hasPermission(viewer, 'api_keys.rotate');
  const canRevoke = hasPermission(viewer, 'api_keys.revoke');
  const usageOf = (k: AdminApiKeyRow): KeyUsage | undefined => usage.get(k.key_id);

  const columns: Column<AdminApiKeyRow>[] = [
    { key: 'id', header: 'Key', sort: 'id', cell: (k) => <><span className="mono">#{k.key_id}</span><span className="dt-sub">{` · ${TYPE_LABEL[k.key_type] ?? k.key_type}`}</span></> },
    { key: 'owner', header: 'Owner', cell: (k) => <Player name={k.owner_name} uuid={k.owner_uuid} /> },
    {
      key: 'scope',
      header: 'Scope',
      secondary: true,
      cell: (k) => (k.firm_id && k.firm_name
        ? <Link href={`/firms/${encodeURIComponent(k.firm_name)}`} prefetch={false}>Firm: {k.firm_name}</Link>
        : k.account_id
          ? <Link href={`/accounts/${k.account_id}`} prefetch={false}>Account: {k.account_name ?? `#${k.account_id}`}</Link>
          : '—'),
    },
    ...(hasUsage ? [
      { key: 'req', header: 'Requests 24 h', numeric: true, optional: true, cell: (k: AdminApiKeyRow) => fmtN(usageOf(k)?.requests24h ?? 0) },
      { key: 'throttled', header: 'Throttled', numeric: true, optional: true, cell: (k: AdminApiKeyRow) => fmtN(usageOf(k)?.throttled24h ?? 0) },
      { key: 'rpm', header: 'Per minute', numeric: true, optional: true, secondary: true, cell: (k: AdminApiKeyRow) => ((usageOf(k)?.reqPerMin ?? 0) >= 0.05 ? (usageOf(k)?.reqPerMin ?? 0).toFixed(1) : '—') },
    ] : []),
    { key: 'limit', header: 'Limit', numeric: true, cell: (k) => `×${k.multiplier ?? '1.00'}` },
    { key: 'issued', header: 'Issued', sort: 'issued', sortFirst: 'desc', secondary: true, optional: true, cell: (k) => <When value={k.issued_at} /> },
    { key: 'expires', header: 'Expires', sort: 'expires', secondary: true, optional: true, cell: (k) => <When value={k.expires_at} relative={false} /> },
    { key: 'status', header: 'Status', cell: (k) => (k.revoked ? <Tag tone="neg">Revoked</Tag> : <Tag tone="pos">Active</Tag>) },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      label: 'Actions',
      align: 'end',
      cell: (k) => (
        <>
          {k.owner_uuid && canLimit && <LimitDialog ownerUuid={k.owner_uuid} ownerName={k.owner_name ?? `${k.owner_uuid.slice(0, 8)}…`} currentMultiplier={k.multiplier ?? '1.00'} />}
          {!k.revoked && canRotate && <RotateButton keyId={k.key_id} />}
          {!k.revoked && canRevoke && <RevokeButton keyId={k.key_id} />}
        </>
      ),
    },
  ];

  return (
    <>
      <PageHeader title="API keys" subtitle={`${fmtN(active)} active of ${fmtN(total)} matching. The limit multiplier is per issuer and applies to all of their keys.`} />
      <FilterBar
        table={adminApiKeysTable}
        total={total}
        entityLabels={entityLabels}
        searchEntity={searchAdminPlayersAction}
        columns={columnOptions(columns)}
        tableId={TABLE_ID}
      />
      <DataTable
        id={TABLE_ID}
        label="All keys"
        columns={columns}
        rows={rows}
        rowKey={(k) => k.key_id}
        sort={state.sort}
        sortHref={(field, dir) => href(withSort(state, field, dir, adminApiKeysTable))}
        link={RouterLink}
        density="compact"
        empty={<AdminTableEmpty pathname={PATH} state={state} table={adminApiKeysTable} describe={{ entityLabels }} />}
      />
      <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} />
    </>
  );
}
