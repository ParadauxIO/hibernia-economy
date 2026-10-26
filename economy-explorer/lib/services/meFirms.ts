import 'server-only';
import { hasPermission } from '@/lib/auth/policy';
import type { Viewer } from '@/lib/auth/viewer';
import { parseMoney, toDbString } from '@/lib/money';
import { utcDay } from '@/lib/rollup/days';
import type { RangeCoverage } from '@/lib/services/rollup';
import { selectFirmAccountBalances, selectFirmFlows } from '@/lib/sql/me';
import { monthFirstDay, monthOf } from './meFlows';
import { coverageMap, coverageOf, firmRolesFor } from './me';

// /me/firms (spec §8.6): firms the player owns or works for. With finance
// access (proprietor, or a role with FINANCIAL or ADMIN, plus the
// firm.financials.read permission) each firm's balance and this month's net
// are shown, labelled as the firm's money. They are never added to the
// player's own balance (spec §3 rule 5).

export interface MyFirm {
  firmId: number;
  name: string;
  archived: boolean;
  isProprietor: boolean;
  role: string | null;
  permissions: string[];
  hiredAt: Date | null;
  firstJoinedAt: Date | null;
  /** Null without finance access. */
  finance: {
    balance: string;
    accountCount: number;
    monthIn: string | null;
    monthOut: string | null;
    monthNet: string | null;
  } | null;
}

export interface MyFirmsResult { firms: MyFirm[]; month: string; coverage: RangeCoverage }

function hasFinance(isProprietor: boolean, permissions: string[]): boolean {
  return isProprietor || permissions.includes('FINANCIAL') || permissions.includes('ADMIN');
}

/** 1–3 queries: firm roles (cached), firm balances and month flows for finance firms. */
export async function getMyFirmsPage(viewer: Viewer, uuid: string, now: Date = new Date()): Promise<MyFirmsResult> {
  const today = utcDay(now);
  const month = monthOf(today);
  const range = { from: monthFirstDay(month), to: today };
  const [roles, map] = await Promise.all([firmRolesFor(uuid), coverageMap()]);
  const coverage = coverageOf(map, 'account_day', range);
  const rbac = hasPermission(viewer, 'firm.financials.read') || hasPermission(viewer, 'firm.financials.read.all');
  const financeIds = rbac ? roles.filter((r) => hasFinance(r.is_proprietor === 1, (r.permissions ?? '').split(','))).map((r) => r.firm_id) : [];
  const [balances, flows] = await Promise.all([
    selectFirmAccountBalances(financeIds),
    coverage.covered ? selectFirmFlows(financeIds, range.from, range.to) : Promise.resolve([]),
  ]);
  const firms = roles.map((r): MyFirm => {
    const permissions = (r.permissions ?? '').split(',').filter(Boolean);
    const finance = financeIds.includes(r.firm_id);
    const own = balances.filter((b) => b.firm_id === r.firm_id);
    const flow = flows.find((f) => f.firm_id === r.firm_id);
    return {
      firmId: r.firm_id, name: r.firm_name, archived: r.archived === 1, isProprietor: r.is_proprietor === 1,
      role: r.role_name, permissions, hiredAt: r.joined_at, firstJoinedAt: r.first_joined_at,
      finance: finance ? {
        balance: toDbString(own.reduce((s, b) => s + parseMoney(b.balance), 0n)),
        accountCount: own.length,
        monthIn: coverage.covered ? flow?.inflow ?? '0.00' : null,
        monthOut: coverage.covered ? flow?.outflow ?? '0.00' : null,
        monthNet: coverage.covered ? toDbString(parseMoney(flow?.inflow ?? '0.00') - parseMoney(flow?.outflow ?? '0.00')) : null,
      } : null,
    };
  });
  return { firms, month, coverage };
}

export async function getMyFirms(viewer: Viewer, uuid: string, now: Date = new Date()): Promise<MyFirm[]> {
  return (await getMyFirmsPage(viewer, uuid, now)).firms;
}
