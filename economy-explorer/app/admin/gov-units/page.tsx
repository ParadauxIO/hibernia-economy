import '../../gov/gov.css';
import Link from 'next/link';
import { Money, PageHeader, Section, Tag } from '@democracycraft/design-system';
import { DataTable, TableEmpty, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { PrivacyGate } from '@/components/PrivacyGate';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { accountLabel } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import { parseMoney } from '@/lib/money';
import { getGovUnitsAdmin } from '@/lib/services/governmentUnits';
import type { AssignableAccountRow, GovUnitRow } from '@/lib/sql/govUnits';
import { cleared, filterText, parseTableState, tableHref, withQuery, withSort, withoutFilter, type FilterState, type TableState } from '@/lib/tables/core';
import { govUnitAccountsTable } from '@/lib/tables/gov';
import { AccountRef } from '../../gov/shared';
import { AccountUnitSelect, DeleteUnitButton, UnitDialogButton, type UnitChoice } from './UnitForms';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government units', description: 'Group government accounts into units.', path: '/admin/gov-units' });
}

const RouterLink: LinkComponent = Link;
const TABLE_ID = 'gov-unit-accounts';
type UnitAdminRow = GovUnitRow & { accounts: number; parent_name: string | null };

function choice(u: GovUnitRow): UnitChoice {
  return { unitId: u.unit_id, name: u.name, slug: u.slug, parentUnitId: u.parent_unit_id, description: u.description };
}

function filterAccounts(rows: AssignableAccountRow[], state: TableState<typeof govUnitAccountsTable>, unitName: Map<number, string>): AssignableAccountRow[] {
  const q = state.q.toLowerCase().replace(/^#/, '');
  const test = (a: AssignableAccountRow, f: FilterState) => {
    if (f.field === 'assigned') return (a.unit_id !== null) === (f.values[0] === 'true');
    const v = parseMoney(a.balance);
    const lo = parseMoney(f.values[0] ?? '0');
    const hi = parseMoney(f.values[1] ?? f.values[0] ?? '0');
    switch (f.op) {
      case 'gt': return v > lo;
      case 'gte': return v >= lo;
      case 'lt': return v < lo;
      case 'lte': return v <= lo;
      case 'eq': return v === lo;
      case 'between': return v >= lo && v <= hi;
      default: return true;
    }
  };
  const dir = state.sort.dir === 'asc' ? 1 : -1;
  return rows
    .filter((a) => !q || (a.display_name ?? '').toLowerCase().includes(q) || String(a.account_id) === q)
    .filter((a) => state.filters.every((f) => test(a, f)))
    .sort((a, b) => {
      if (state.sort.field === 'balance') { const x = parseMoney(a.balance); const y = parseMoney(b.balance); return dir * (x < y ? -1 : x > y ? 1 : 0); }
      if (state.sort.field === 'unit') return dir * (unitName.get(a.unit_id ?? -1) ?? '~').localeCompare(unitName.get(b.unit_id ?? -1) ?? '~');
      return dir * (a.display_name ?? '').localeCompare(b.display_name ?? '') || a.account_id - b.account_id;
    });
}

export default async function GovUnitsAdminPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const data = await getGovUnitsAdmin();
  if (!data) return <PrivacyGate kind="private" title="Government unit management requires gov_units.manage" />;
  const sp = await searchParams;
  const state = parseTableState(sp, govUnitAccountsTable);
  const choices = data.units.map(choice);
  const unitName = new Map(data.units.map((u) => [u.unit_id, u.name]));
  const rows = filterAccounts(data.accounts, state, unitName);
  const href = (s: TableState) => tableHref('/admin/gov-units', s, govUnitAccountsTable);
  const active = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, govUnitAccountsTable, { currency: CURRENCY_SYMBOL }), removeHref: href(withoutFilter(state, f.field)) })),
  ];

  const unitColumns: Column<UnitAdminRow>[] = [
    { key: 'name', header: 'Unit', cell: (u) => <><span>{u.name}</span><span className="dt-sub"> · {u.slug}</span></> },
    { key: 'parent', header: 'Parent', secondary: true, cell: (u) => u.parent_name ?? <span className="muted">Top level</span> },
    { key: 'accounts', header: 'Accounts', numeric: true, cell: (u) => u.accounts },
    { key: 'actions', header: <span className="sr-only">Actions</span>, label: 'Actions', cell: (u) => <span className="gov-inline-actions"><UnitDialogButton unit={choice(u)} units={choices} /><DeleteUnitButton unit={choice(u)} /></span> },
  ];
  const accountColumns: Column<AssignableAccountRow>[] = [
    { key: 'name', header: 'Account', sort: 'name', cell: (a) => <AccountRef id={a.account_id} name={a.display_name} /> },
    { key: 'balance', header: 'Balance', sort: 'balance', numeric: true, secondary: true, cell: (a) => <Money value={a.balance} /> },
    {
      key: 'unit', header: 'Unit', sort: 'unit',
      cell: (a) => choices.length ? <AccountUnitSelect accountId={a.account_id} accountName={accountLabel(a)} unitId={a.unit_id} units={choices} /> : <Tag>No units yet</Tag>,
    },
  ];

  return <>
    <PageHeader title="Government units" subtitle="Group government accounts into departments, cities or states for the government workspace. Units are presentation only: officials see a unit’s figures for the accounts they can already read."
      actions={<UnitDialogButton units={choices} />} />
    <Section title="Units">
      <DataTable id="gov-units-table" label="Government units" columns={unitColumns} rows={data.units} rowKey={(u) => u.unit_id}
        empty={<p className="dt-empty-text">No units yet. Create one, then put accounts in it below.</p>} />
    </Section>
    <Section title="Accounts" description="Every active government account. An account belongs to at most one unit." divider>
      <FilterBar table={govUnitAccountsTable} total={rows.length} columns={columnOptions(accountColumns)} tableId={TABLE_ID} currency={CURRENCY_SYMBOL} />
      <DataTable id={TABLE_ID} label="Government accounts and their units" columns={accountColumns} rows={rows} rowKey={(a) => a.account_id}
        sort={state.sort} sortHref={(field, dir) => href(withSort(state, field, dir, govUnitAccountsTable))} link={RouterLink}
        empty={<TableEmpty noun="accounts" filters={active} clearHref={href(cleared(state))} link={RouterLink} />} />
    </Section>
  </>;
}
