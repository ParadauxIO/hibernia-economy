import Link from 'next/link';
import { EntityBadge, PageHeader, Section, Tag, When } from '@democracycraft/design-system';
import { DataTable, TablePager, columnOptions, type Column } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { accountLabel, fmtAmtFull } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import { getAdminAccounts } from '@/lib/services/admin';
import type { ExplorerAccountRow } from '@/lib/sql/ledger';
import { searchFirmsAction } from '@/lib/tables/actions';
import { accountsTable } from '@/lib/tables/accounts';
import { parseTableState, tableHref, withPage, withSort, type TableState } from '@/lib/tables/core';
import { treasuryAdminConfigured } from '@/lib/treasury';
import { AdminGate } from '../_components/AdminGate';
import { ManageDialog } from '../_components/ManageDialog';
import { AdminTableEmpty, RouterLink } from '../_components/table';
import { AccountAdminPanel } from './account-admin-panel';
import { MoveMoneyPanel } from './move-money-panel';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Accounts', description: 'Administer accounts: move money, rename, change owner, archive.', path: '/admin/accounts' });
}

const TABLE_ID = 'admin-accounts-table';
const PATH = '/admin/accounts';

/**
 * Admin account tool (PAR-219/221): move money between accounts, and per-account
 * rename / change-owner / archive-unarchive, all through the ledger-authoritative
 * treasury admin API and audited with the values before and after.
 */
export default async function AdminAccountsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'account.admin.read')) return <AdminGate viewer={viewer} title="Account administration access required" permission="account.admin.read" />;
  const state = parseTableState(await searchParams, accountsTable);
  await auditView(viewer, { path: PATH, targetType: 'global', targetId: state.q || null });

  const configured = treasuryAdminConfigured();
  const canManage = (['account.rename', 'account.owner.change', 'account.archive'] as const).some((p) => hasPermission(viewer, p));
  const { rows, total, entityLabels } = await getAdminAccounts(viewer, state);
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof accountsTable>) => tableHref(PATH, s, accountsTable);

  const columns: Column<ExplorerAccountRow>[] = [
    {
      key: 'name',
      header: 'Account',
      sort: 'name',
      cell: (a) => (
        <>
          <Link href={`/accounts/${a.account_id}`} prefetch={false}>{accountLabel(a)}</Link>
          <span className="dt-sub">{` #${a.account_id}`}</span>
          {a.archived ? <> <Tag tone="neg">Archived</Tag></> : null}
        </>
      ),
    },
    { key: 'type', header: 'Type', sort: 'type', secondary: true, cell: (a) => <EntityBadge type={a.account_type} /> },
    { key: 'owner', header: 'Owner', secondary: true, optional: true, cell: (a) => a.owner_name ?? (a.owner_uuid ? <span className="mono">{a.owner_uuid}</span> : '—') },
    { key: 'balance', header: 'Balance', sort: 'balance', numeric: true, cell: (a) => fmtAmtFull(a.balance) },
    { key: 'created', header: 'Created', sort: 'created', sortFirst: 'desc', secondary: true, optional: true, cell: (a) => <When value={a.created_at} /> },
    ...(canManage ? [{
      key: 'manage',
      header: <span className="sr-only">Manage</span>,
      label: 'Manage',
      align: 'end' as const,
      cell: (a: ExplorerAccountRow) => (
        <ManageDialog label={`Manage account ${a.account_id}`} title={`${accountLabel(a)} · #${a.account_id}`}
          description="Each change goes through the Treasury admin API and is recorded in the audit log with its old and new values.">
          <AccountAdminPanel accountId={a.account_id} displayName={a.display_name} archived={a.archived === 1} />
        </ManageDialog>
      ),
    }] : []),
  ];

  return (
    <>
      <PageHeader title="Accounts" subtitle="Move money, rename, change owner or archive. Each change needs its own permission and is audited." />

      {!configured && (
        <p className="state-error" role="alert">
          The Treasury admin API is not configured in this environment (TREASURY_API_BASE_URL / TREASURY_ADMIN_TOKEN), so changes are unavailable.
        </p>
      )}

      {hasPermission(viewer, 'account.transfer') && (
        <Section id="move-money" title="Move money between accounts" description="A ledger transfer between any two accounts, recorded with you as its initiator.">
          <MoveMoneyPanel disabled={!configured} />
        </Section>
      )}

      <Section id="accounts" title="All accounts" divider={hasPermission(viewer, 'account.transfer')}>
        <FilterBar
          table={accountsTable}
          total={total}
          entityLabels={entityLabels}
          searchEntity={searchFirmsAction}
          columns={columnOptions(columns)}
          tableId={TABLE_ID}
          currency={CURRENCY_SYMBOL}
        />
        <DataTable
          id={TABLE_ID}
          label="Accounts"
          columns={columns}
          rows={rows}
          rowKey={(a) => a.account_id}
          sort={state.sort}
          sortHref={(field, dir) => href(withSort(state, field, dir, accountsTable))}
          link={RouterLink}
          empty={<AdminTableEmpty pathname={PATH} state={state} table={accountsTable} describe={{ currency: CURRENCY_SYMBOL, entityLabels }} />}
        />
        <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} />
      </Section>
    </>
  );
}
