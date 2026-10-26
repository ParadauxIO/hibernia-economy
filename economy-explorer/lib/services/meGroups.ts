// Pure shaping for the /me workspace: who an account belongs to, why the
// player can see it, and which group it is listed in. No I/O, so the rules
// (spec §3 rule 5, §8.1) are unit- and component-tested directly.
//
// Rule 5: a business's money is not its owner's. "My balance" is the sum of
// the player's own PERSONAL accounts only. Business and government accounts
// are listed in their own groups with their own subtotals, labelled as the
// firm's or the government's money, and never added to the player's.

import { accountLabel } from '@/lib/format';
import { sumBy, toDbString } from '@/lib/money';
import type { AccountRelationRow, FirmRoleRow, RelationKind } from '@/lib/sql/me';

export interface Relation {
  kind: RelationKind;
  /** Firm role name (firm finance) or LuckPerms group (group). */
  detail: string | null;
}

export interface MyAccount {
  id: number;
  type: string;
  name: string;
  ownerUuid: string | null;
  ownerName: string | null;
  archived: boolean;
  createdAt: Date;
  /** DECIMAL string. */
  balance: string;
  firmId: number | null;
  firmName: string | null;
  /** Every reason the player can read it, strongest first. */
  relations: Relation[];
}

export type GroupKind = 'mine' | 'shared' | 'business' | 'government' | 'other';

export interface AccountGroup {
  key: string;
  kind: GroupKind;
  title: string;
  /** One line under the title: whose money this is. */
  subtitle: string;
  /** Sum of the group's balances, DECIMAL string; null where a sum would mean nothing. */
  subtotal: string | null;
  /** What the subtotal is, in words ("My balance", "Acme Corp's money"). */
  subtotalLabel: string | null;
  firmId: number | null;
  /** The player's role at the firm, for business groups ("Proprietor", "Treasurer", or null if none). */
  role: string | null;
  /** Why accounts are shown when the player has no finance role there. */
  note: string | null;
  accounts: MyAccount[];
}

const RANK: Record<RelationKind, number> = { owner: 0, authorizer: 1, member: 2, firm_finance: 3, viewer: 4, group: 5 };

/** Collapse relation rows (one per reason) into accounts with all their reasons. */
export function toAccounts(rows: readonly AccountRelationRow[]): MyAccount[] {
  const byId = new Map<number, MyAccount>();
  for (const r of rows) {
    let a = byId.get(r.account_id);
    if (!a) {
      a = {
        id: r.account_id, type: r.account_type, name: accountLabel(r), ownerUuid: r.owner_uuid, ownerName: r.owner_name,
        archived: r.archived === 1, createdAt: r.created_at, balance: r.balance, firmId: r.firm_id, firmName: r.firm_name, relations: [],
      };
      byId.set(r.account_id, a);
    }
    if (!a.relations.some((x) => x.kind === r.kind && x.detail === r.detail)) a.relations.push({ kind: r.kind, detail: r.detail });
  }
  for (const a of byId.values()) a.relations.sort((x, y) => RANK[x.kind] - RANK[y.kind] || (x.detail ?? '').localeCompare(y.detail ?? ''));
  return [...byId.values()];
}

/** "Owner", "Member", "Firm finance · Treasurer", "Group · mayor". */
export function relationLabel(r: Relation, accountType: string): string {
  switch (r.kind) {
    // On a firm or government account the registered owner is the holder of
    // record, not the owner of the money (rule 5).
    case 'owner': return accountType === 'PERSONAL' ? 'Owner' : 'Registered holder';
    case 'authorizer': return 'Authorizer';
    case 'member': return 'Member';
    case 'viewer': return 'Viewer';
    case 'firm_finance': return r.detail ? `Firm finance · ${r.detail}` : 'Firm finance';
    case 'group': return r.detail ? `Group · ${r.detail}` : 'Group';
  }
}

/** Plain-language reason for showing an account, used in the "why is this here" note. */
function relationReason(r: Relation): string {
  switch (r.kind) {
    case 'owner': return 'you are its registered holder';
    case 'authorizer': return 'you are an authorizer on it';
    case 'member': return 'you are a member of it';
    case 'viewer': return 'you were given view access';
    case 'firm_finance': return 'you have a finance role';
    case 'group': return `your group ${r.detail ?? ''} has access`.replace(/\s+has/, ' has');
  }
}

/** The player's personal accounts: PERSONAL and owned by them. The only accounts in "My balance". */
export function isMine(a: MyAccount, playerUuid: string): boolean {
  return a.type === 'PERSONAL' && a.ownerUuid === playerUuid && a.relations.some((r) => r.kind === 'owner');
}

/** Open accounts by name, closed accounts last. */
function sortAccounts(list: MyAccount[]): MyAccount[] {
  return [...list].sort((a, b) => Number(a.archived) - Number(b.archived) || a.name.localeCompare(b.name) || a.id - b.id);
}

function roleAt(firmId: number, roles: readonly FirmRoleRow[]): { role: string | null; finance: boolean } {
  const r = roles.find((x) => x.firm_id === firmId);
  if (!r) return { role: null, finance: false };
  const perms = (r.permissions ?? '').split(',');
  const finance = r.is_proprietor === 1 || perms.includes('FINANCIAL') || perms.includes('ADMIN');
  return { role: r.is_proprietor === 1 ? (r.role_name ? `Proprietor · ${r.role_name}` : 'Proprietor') : r.role_name, finance };
}

/**
 * Group accounts by whose money they are. Groups are never added together:
 * each has its own subtotal, and only "Mine" is the player's money.
 */
export function groupAccounts(accounts: readonly MyAccount[], playerUuid: string, roles: readonly FirmRoleRow[]): AccountGroup[] {
  const mine = accounts.filter((a) => isMine(a, playerUuid));
  const shared = accounts.filter((a) => a.type === 'PERSONAL' && !isMine(a, playerUuid));
  const business = accounts.filter((a) => a.type === 'BUSINESS');
  const government = accounts.filter((a) => a.type === 'GOVERNMENT');
  const other = accounts.filter((a) => !['PERSONAL', 'BUSINESS', 'GOVERNMENT'].includes(a.type));
  const total = (list: MyAccount[]) => toDbString(sumBy(list, (a) => a.balance));

  const groups: AccountGroup[] = [{
    key: 'mine', kind: 'mine', title: 'Mine', subtitle: 'Your personal accounts. This is your money.',
    subtotal: total(mine), subtotalLabel: 'My balance', firmId: null, role: null, note: null, accounts: sortAccounts(mine),
  }];
  if (shared.length) {
    groups.push({
      key: 'shared', kind: 'shared', title: 'Shared with you', subtitle: 'Other players’ personal accounts you can see. Their money, not yours.',
      subtotal: null, subtotalLabel: null, firmId: null, role: null, note: null, accounts: sortAccounts(shared),
    });
  }

  const firms = new Map<string, MyAccount[]>();
  for (const a of business) {
    const key = a.firmId === null ? 'none' : String(a.firmId);
    firms.set(key, [...(firms.get(key) ?? []), a]);
  }
  const firmGroups = [...firms.entries()].map(([key, list]): AccountGroup => {
    const firmId = key === 'none' ? null : Number(key);
    const firmName = list[0]?.firmName ?? null;
    const { role, finance } = firmId === null ? { role: null, finance: false } : roleAt(firmId, roles);
    let note: string | null = null;
    if (!finance) {
      const reasons = [...new Set(list.flatMap((a) => a.relations.filter((r) => r.kind !== 'firm_finance').map(relationReason)))];
      const where = firmName ?? 'this firm';
      note = `You have no finance role at ${where}. ${list.length === 1 ? 'This account is' : 'These accounts are'} shown because ${reasons.join(' and ') || 'of a direct grant'}.`;
    }
    return {
      key: `firm-${key}`, kind: 'business', title: firmName ?? 'Business accounts without a firm',
      subtitle: firmName ? `${firmName}’s money, not yours.` : 'Business money, not yours.',
      subtotal: total(list), subtotalLabel: firmName ? `${firmName}’s money` : 'Business money',
      firmId, role, note, accounts: sortAccounts(list),
    };
  }).sort((a, b) => (a.firmId === null ? 1 : 0) - (b.firmId === null ? 1 : 0) || a.title.localeCompare(b.title));
  groups.push(...firmGroups);

  // TODO(GOV, PAR-539): group by explorer_gov_unit once GOV's units table lands.
  if (government.length) {
    groups.push({
      key: 'government', kind: 'government', title: 'Government', subtitle: 'Public money you can see through your role. Not yours.',
      subtotal: total(government), subtotalLabel: 'Held by these government accounts', firmId: null, role: null, note: null, accounts: sortAccounts(government),
    });
  }
  if (other.length) {
    groups.push({
      key: 'other', kind: 'other', title: 'Other', subtitle: 'System accounts you can see.',
      subtotal: null, subtotalLabel: null, firmId: null, role: null, note: null, accounts: sortAccounts(other),
    });
  }
  return groups;
}
