import 'server-only';
import { memo } from '@/lib/cache';
import { CATEGORY_LABELS, categorize, type AccountType, type Category } from '@/lib/categories';
import { parseMoney, toDbString } from '@/lib/money';
import { addDays, monthStart, utcDay, type DayRange } from '@/lib/rollup/days';
import {
  countFirmDirectory, findFirmSummary, listFirmAccounts, listFirmDirectory, listFirmEmployees, listFirmItems, listFirmRolePermissions,
  listFirmRoles, selectMonthlyFlows, type FirmAccountRow, type FirmDirectoryRow, type FirmEmployeeRow, type FirmItemRow,
  type FirmRoleRow, type FirmSummary,
} from '@/lib/sql/firm';
import { counterparties, rollupCoverage, type RangeCoverage } from '@/lib/services/rollup';
import { getActivity, getRangeHistory, type AccountActivity, type AccountRangeHistory } from '@/lib/services/account';
import { firmsTable, type FirmsTableState } from '@/lib/tables/firms';
import { isQuickActive, quickFilters, serializeTableState } from '@/lib/tables/core';

// Firm pages (spec §7.3). The firm comes first: its accounts and their
// balances are public and are the firm's money, never its proprietor's.
// Members and financials are separate tabs behind ReBAC (canViewFirmInternals,
// canViewFirmFinancials), and their data is only read after those checks.

const FIVE_MINUTES = 5 * 60_000;
const DAY_MS = 86_400_000;

export interface FirmPublic {
  summary: FirmSummary;
  accounts: FirmAccountRow[];
  /** 30-day totals across the firm's accounts, transfers between them excluded. */
  activity: AccountActivity | null;
  /** Items most traded at the firm's shops in the last 30 days (counts, no revenue). */
  topItems: Pick<FirmItemRow, 'item_key' | 'item_name' | 'material' | 'item_custom' | 'trades' | 'quantity'>[];
}

/** Public firm data, cached for five minutes. */
export function getFirmPublic(firmId: number): Promise<FirmPublic | null> {
  return memo(`firm-public:v1:${firmId}`, FIVE_MINUTES, async () => {
    const now = new Date();
    const [summary, accounts, items] = await Promise.all([
      findFirmSummary(firmId),
      listFirmAccounts(firmId),
      listFirmItems(firmId, new Date(now.getTime() - 30 * DAY_MS), now, 8),
    ]);
    if (!summary) return null;
    const ids = accounts.map((a) => a.account_id);
    const activity = ids.length ? await getActivity(ids, { excludeInternal: true }) : null;
    return {
      summary,
      accounts,
      activity,
      topItems: items.map(({ item_key, item_name, material, item_custom, trades, quantity }) => ({ item_key, item_name, material, item_custom, trades, quantity })),
    };
  });
}

export interface FirmMembers {
  employees: FirmEmployeeRow[];
  roles: (FirmRoleRow & { permissions: string[]; members: number })[];
}

/** The roster and roles. Call only after canViewFirmInternals. */
export async function getFirmMembers(firmId: number): Promise<FirmMembers> {
  const [employees, roles, perms] = await Promise.all([listFirmEmployees(firmId), listFirmRoles(firmId), listFirmRolePermissions(firmId)]);
  return {
    employees,
    roles: roles.map((r) => ({
      ...r,
      permissions: perms.filter((p) => p.role_id === r.role_id).map((p) => p.permission).sort(),
      members: employees.filter((e) => e.role_name === r.name).length,
    })),
  };
}

export interface StatementLine { category: Category; label: string; amount: string }

export interface MonthStatement {
  month: string;
  revenue: StatementLine[];
  costs: StatementLine[];
  totalRevenue: string;
  totalCosts: string;
  net: string;
}

export interface FirmFinancials {
  range: AccountRangeHistory;
  /** Outflows to the firm's current staff, by employee, over the range. */
  payroll: { uuid: string; name: string | null; role: string; amount: string; txnCount: number }[];
  payrollCoverage: RangeCoverage;
  shopItems: FirmItemRow[];
  /** Calendar months, newest first, up to 12. Internal transfers between the firm's accounts are left out. */
  statements: MonthStatement[];
  statementCoverage: RangeCoverage | null;
}

function lines(map: Map<Category, bigint>): StatementLine[] {
  return [...map.entries()].filter(([, v]) => v !== 0n).sort((a, b) => (b[1] > a[1] ? 1 : b[1] < a[1] ? -1 : 0))
    .map(([category, v]) => ({ category, label: CATEGORY_LABELS[category], amount: toDbString(v) }));
}

/**
 * Monthly profit and loss across a set of accounts from the rollups: each
 * month's inflow by category is revenue and outflow is cost. Transfers between
 * the accounts themselves (Internal) are not revenue or cost.
 */
export async function monthlyStatements(accountIds: readonly number[], months = 12): Promise<{ statements: MonthStatement[]; coverage: RangeCoverage | null }> {
  const today = utcDay();
  const from = monthStart(addDays(today, -31 * (months - 1)));
  const cov = (await rollupCoverage()).account_day;
  const coverage: RangeCoverage | null = cov ? {
    job: 'account_day', coveredFrom: cov.from_day, coveredTo: cov.to_day, historyComplete: cov.history_complete === 1, updatedAt: cov.updated_at,
    covered: (cov.history_complete === 1 || cov.from_day <= from) && cov.to_day >= today,
  } : null;
  if (!cov) return { statements: [], coverage };
  const start = cov.history_complete === 1 || cov.from_day <= from ? from : cov.from_day;
  const rows = await selectMonthlyFlows(accountIds, start, today);
  const byMonth = new Map<string, { rev: Map<Category, bigint>; cost: Map<Category, bigint> }>();
  for (const r of rows) {
    const m = byMonth.get(r.month) ?? { rev: new Map(), cost: new Map() };
    byMonth.set(r.month, m);
    const input = { pluginKey: r.plugin_key, taxKind: r.tax_kind || null, counterpartyType: (r.counterparty_type || null) as AccountType | null, internal: r.is_internal === 1 };
    const inflow = parseMoney(r.inflow);
    const outflow = parseMoney(r.outflow);
    if (input.internal) continue;
    if (inflow !== 0n) { const c = categorize({ ...input, direction: 'in' }); m.rev.set(c, (m.rev.get(c) ?? 0n) + inflow); }
    if (outflow !== 0n) { const c = categorize({ ...input, direction: 'out' }); m.cost.set(c, (m.cost.get(c) ?? 0n) + outflow); }
  }
  const statements = [...byMonth.entries()].sort((a, b) => b[0].localeCompare(a[0])).map(([month, m]) => {
    const rev = [...m.rev.values()].reduce((a, b) => a + b, 0n);
    const cost = [...m.cost.values()].reduce((a, b) => a + b, 0n);
    return { month: month.slice(0, 7), revenue: lines(m.rev), costs: lines(m.cost), totalRevenue: toDbString(rev), totalCosts: toDbString(cost), net: toDbString(rev - cost) };
  });
  return { statements, coverage };
}

/** Financials for a range. Call only after canViewFirmFinancials. Cached per firm and range. */
export function getFirmFinancials(firmId: number, accountIds: readonly number[], range: DayRange): Promise<FirmFinancials> {
  return memo(`firm-financials:v1:${firmId}:${range.from}:${range.to}`, FIVE_MINUTES, async () => {
    const from = new Date(`${range.from}T00:00:00Z`);
    const to = new Date(Date.parse(`${range.to}T00:00:00Z`) + DAY_MS);
    const [history, cps, employees, shopItems, statements] = await Promise.all([
      getRangeHistory(accountIds, range, { excludeInternal: true }),
      counterparties(accountIds, range, 200),
      listFirmEmployees(firmId),
      listFirmItems(firmId, from, to, 50),
      monthlyStatements(accountIds),
    ]);
    const staff = new Map(employees.filter((e) => e.player_uuid).map((e) => [e.player_uuid as string, e]));
    const payroll = new Map<string, { uuid: string; name: string | null; role: string; amount: bigint; txnCount: number }>();
    for (const c of cps.data) {
      if (c.accountType !== 'PERSONAL' || !c.ownerUuid) continue;
      const e = staff.get(c.ownerUuid);
      const out = parseMoney(c.outflow);
      if (!e || out === 0n) continue;
      const row = payroll.get(c.ownerUuid) ?? { uuid: c.ownerUuid, name: c.ownerName ?? e.player_name, role: e.role_name, amount: 0n, txnCount: 0 };
      row.amount += out;
      row.txnCount += c.txnCount;
      payroll.set(c.ownerUuid, row);
    }
    return {
      range: history,
      payroll: [...payroll.values()].sort((a, b) => (b.amount > a.amount ? 1 : -1)).map((p) => ({ ...p, amount: toDbString(p.amount) })),
      payrollCoverage: cps.coverage,
      shopItems,
      statements: statements.statements,
      statementCoverage: statements.coverage,
    };
  });
}

// ── Directory ─────────────────────────────────────────────────────────────

function cacheable(state: FirmsTableState): boolean {
  if (state.q !== '') return false;
  if (state.filters.length === 0) return true;
  return quickFilters(firmsTable).some((q) => q.filters.length === state.filters.length && isQuickActive(state, q));
}

export async function getFirmsDirectory(state: FirmsTableState): Promise<{ rows: FirmDirectoryRow[]; total: number }> {
  const offset = (state.page - 1) * state.limit;
  const fetch = async () => {
    const [rows, total] = await Promise.all([listFirmDirectory(state, state.limit, offset), countFirmDirectory(state)]);
    return { rows, total };
  };
  return cacheable(state) ? memo(`firms-dir:v1:${serializeTableState(state, firmsTable)}`, 60_000, fetch) : fetch();
}

