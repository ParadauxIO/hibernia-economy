// Pure money summaries over rollup flow groups for /me (spec §8.1, §8.3, §8.4).
//
// Income and spending exclude the Internal category: transfers between the
// subject's own accounts (two of the player's personal accounts, say). Money
// between a personal account and a firm is not internal, so wages, dividends
// and owner draws count. Net balance change keeps every flow, internal ones
// too, because a single account's balance does move when money goes to a
// sibling account.

import { CATEGORIES, CATEGORY_LABELS, categorize, type AccountType, type Category } from '@/lib/categories';
import { accountLabel } from '@/lib/format';
import { parseMoney, type Cents } from '@/lib/money';
import type { MonthFlowGroupRow, PostingRow } from '@/lib/sql/me';

export interface CategoryFlow {
  key: string;
  category: Category;
  label: string;
  /** For taxes: personal, corporate, income or sales. */
  taxKind: string | null;
  inflow: Cents;
  outflow: Cents;
}

export interface MonthFlows {
  month: string;
  /** Money in, internal transfers excluded. */
  income: Cents;
  /** Money out, internal transfers excluded. */
  spending: Cents;
  /** Every flow, internal included: the balance change over the month. */
  net: Cents;
  internalIn: Cents;
  internalOut: Cents;
  /** Taxes paid (tax category outflow) by kind. */
  taxPaid: Cents;
  taxByKind: Record<string, Cents>;
  categories: CategoryFlow[];
}

const TAX_LABELS: Record<string, string> = { personal: 'Balance tax', corporate: 'Corporate tax', income: 'Income tax', sales: 'Sales tax' };

export function taxKindLabel(kind: string | null): string {
  return kind ? TAX_LABELS[kind] ?? `${kind[0]!.toUpperCase()}${kind.slice(1)} tax` : 'Tax';
}

function emptyMonth(month: string): MonthFlows {
  return { month, income: 0n, spending: 0n, net: 0n, internalIn: 0n, internalOut: 0n, taxPaid: 0n, taxByKind: {}, categories: [] };
}

/** Summaries per month (YYYY-MM), categorised with lib/categories. */
export function summariseMonths(rows: readonly MonthFlowGroupRow[]): Map<string, MonthFlows> {
  const months = new Map<string, MonthFlows>();
  const cats = new Map<string, Map<string, CategoryFlow>>();
  for (const row of rows) {
    const m = months.get(row.month) ?? emptyMonth(row.month);
    months.set(row.month, m);
    const byKey = cats.get(row.month) ?? new Map<string, CategoryFlow>();
    cats.set(row.month, byKey);
    const input = {
      pluginKey: row.plugin_key, taxKind: row.tax_kind || null,
      counterpartyType: (row.counterparty_type || null) as AccountType | null, internal: row.is_internal === 1,
    };
    for (const direction of ['in', 'out'] as const) {
      const amount = parseMoney(direction === 'in' ? row.inflow : row.outflow);
      if (amount === 0n) continue;
      const category = categorize({ ...input, direction });
      const taxKind = category === 'taxes' ? input.taxKind : null;
      const key = `${category}:${taxKind ?? ''}`;
      const flow = byKey.get(key) ?? { key, category, label: category === 'taxes' ? taxKindLabel(taxKind) : CATEGORY_LABELS[category], taxKind, inflow: 0n, outflow: 0n };
      byKey.set(key, flow);
      if (direction === 'in') { flow.inflow += amount; m.net += amount; } else { flow.outflow += amount; m.net -= amount; }
      if (category === 'internal') {
        if (direction === 'in') m.internalIn += amount; else m.internalOut += amount;
      } else if (direction === 'in') {
        m.income += amount;
      } else {
        m.spending += amount;
      }
      if (category === 'taxes' && direction === 'out') {
        m.taxPaid += amount;
        const k = taxKind ?? 'other';
        m.taxByKind[k] = (m.taxByKind[k] ?? 0n) + amount;
      }
    }
  }
  for (const [month, m] of months) {
    m.categories = [...(cats.get(month)?.values() ?? [])].sort((a, b) =>
      CATEGORIES.indexOf(a.category) - CATEGORIES.indexOf(b.category) || (a.taxKind ?? '').localeCompare(b.taxKind ?? ''));
  }
  return months;
}

export function monthOrEmpty(months: Map<string, MonthFlows>, month: string): MonthFlows {
  return months.get(month) ?? emptyMonth(month);
}

/** Change from `previous` to `current` as a fraction for MetricCard; null when there's no base. */
export function changeFraction(current: Cents, previous: Cents): number | null {
  if (previous === 0n) return null;
  const abs = previous < 0n ? -previous : previous;
  return Number(((current - previous) * 1_000_000n) / abs) / 1_000_000;
}

/** 'YYYY-MM' of a 'YYYY-MM-DD' day, and month arithmetic on 'YYYY-MM'. */
export function monthOf(day: string): string {
  return day.slice(0, 7);
}
export function addMonths(month: string, n: number): string {
  const [y, m] = month.split('-').map(Number) as [number, number];
  const d = new Date(Date.UTC(y, m - 1 + n, 1));
  return d.toISOString().slice(0, 7);
}
export function monthFirstDay(month: string): string {
  return `${month}-01`;
}
export function monthLastDay(month: string): string {
  const next = new Date(Date.parse(`${addMonths(month, 1)}-01T00:00:00Z`) - 86_400_000);
  return next.toISOString().slice(0, 10);
}
export function monthLabel(month: string, withYear = true): string {
  return new Date(`${month}-01T00:00:00Z`).toLocaleDateString('en-US', { month: 'long', ...(withYear ? { year: 'numeric' } : {}), timeZone: 'UTC' });
}

// ── Feed rows ─────────────────────────────────────────────────────────────

export interface Party {
  accountId: number;
  accountType: string;
  /** The account's label: its display name, or its owner's name for a personal account. */
  name: string;
  firmId: number | null;
  firmName: string | null;
  ownerUuid: string | null;
  ownerName: string | null;
}

export interface FeedRow {
  postingId: number;
  txnId: number;
  at: Date;
  account: Party;
  /** Signed, DECIMAL string, from the account's side. */
  amount: string;
  description: string;
  category: Category;
  categoryLabel: string;
  internal: boolean;
  counterparty: Party | null;
}

function party(id: number, type: string, displayName: string | null, ownerUuid: string | null, ownerName: string | null, firmId: number | null, firmName: string | null): Party {
  const name = accountLabel({ account_id: id, display_name: displayName, owner_uuid: ownerUuid, owner_name: ownerName });
  return { accountId: id, accountType: type, name, firmId, firmName, ownerUuid, ownerName };
}

/** A posting as the feed shows it, categorised with lib/categories (the same inputs as the rollups). */
export function toFeedRow(r: PostingRow): FeedRow {
  const direction = parseMoney(r.amount) > 0n ? 'in' : 'out';
  const internal = r.is_internal === 1;
  const category = categorize({
    pluginKey: r.plugin_key, taxKind: r.tax_kind || null,
    counterpartyType: (r.counter_type || null) as AccountType | null, direction, internal,
  });
  return {
    postingId: r.posting_id,
    txnId: r.txn_id,
    at: r.settlement_time,
    account: party(r.account_id, r.account_type, r.account_name, r.account_owner_uuid, r.account_owner_name, null, null),
    amount: r.amount,
    description: r.memo?.trim() || r.message,
    category,
    categoryLabel: category === 'taxes' ? taxKindLabel(r.tax_kind || null) : CATEGORY_LABELS[category],
    internal,
    counterparty: r.counter_id === null ? null
      : party(r.counter_id, r.counter_type ?? 'SYSTEM', r.counter_name, r.counter_owner_uuid, r.counter_owner_name, r.counter_firm_id, r.counter_firm_name),
  };
}
