import { PageHeader, When } from '@democracycraft/design-system';
import { DataTable, TablePager, columnOptions, type Column } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { Player } from '@/components/Player';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { buildMetadata } from '@/lib/metadata';
import { getAdminPlayers } from '@/lib/services/admin';
import type { AdminPlayerRow } from '@/lib/sql/admin';
import { adminPlayersTable } from '@/lib/tables/admin';
import { parseTableState, tableHref, withPage, withSort, type TableState } from '@/lib/tables/core';
import { AdminGate } from '../_components/AdminGate';
import { AdminTableEmpty, RouterLink } from '../_components/table';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Players', description: 'Admin: look up a player and view their personal {server} economy dashboard.', path: '/admin/players' });
}

const TABLE_ID = 'admin-players-table';
const PATH = '/admin/players';

const columns: Column<AdminPlayerRow>[] = [
  { key: 'name', header: 'Player', sort: 'name', cell: (p) => <Player name={p.name} uuid={p.uuid} link={false} /> },
  { key: 'uuid', header: 'UUID', secondary: true, cell: (p) => <span className="mono">{p.uuid}</span> },
  { key: 'firstSeen', header: 'First seen', sort: 'firstSeen', sortFirst: 'desc', secondary: true, optional: true, cell: (p) => <When value={p.firstSeen} /> },
  { key: 'lastSeen', header: 'Last seen', sort: 'lastSeen', sortFirst: 'desc', cell: (p) => <When value={p.lastSeen} /> },
];

export default async function AdminPlayersPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'player.read.all')) return <AdminGate viewer={viewer} title="Player lookup access required" permission="player.read.all" />;
  await auditView(viewer, { path: PATH, targetType: 'global' });

  const state = parseTableState(await searchParams, adminPlayersTable);
  const { rows, total } = await getAdminPlayers(viewer, state);
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof adminPlayersTable>) => tableHref(PATH, s, adminPlayersTable);

  return (
    <>
      <PageHeader title="Players" subtitle="Open a player's personal dashboard. Every view is recorded in the audit log." />
      <FilterBar table={adminPlayersTable} total={total} columns={columnOptions(columns)} tableId={TABLE_ID} />
      <DataTable
        id={TABLE_ID}
        label="Players"
        columns={columns}
        rows={rows}
        rowKey={(p) => p.uuid}
        rowHref={(p) => `/admin/players/${p.uuid}`}
        rowLabel={(p) => `View ${p.name}'s data`}
        sort={state.sort}
        sortHref={(field, dir) => href(withSort(state, field, dir, adminPlayersTable))}
        link={RouterLink}
        empty={<AdminTableEmpty pathname={PATH} state={state} table={adminPlayersTable} />}
      />
      <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} />
    </>
  );
}
