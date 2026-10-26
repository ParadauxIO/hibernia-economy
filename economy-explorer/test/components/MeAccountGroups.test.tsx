// /me "Accounts I can access": grouping by whose money it is, never summed
// across groups (spec §3 rule 5), and the real relationship on every row.
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AccountGroups } from '@/app/me/_components/AccountGroups';
import { groupAccounts, relationLabel, toAccounts } from '@/lib/services/meGroups';
import type { AccountRelationRow, FirmRoleRow } from '@/lib/sql/me';

const ME = '11111111-2222-3333-4444-555555555555';
const OTHER = '99999999-2222-3333-4444-555555555555';

function rel(account_id: number, kind: AccountRelationRow['kind'], o: Partial<AccountRelationRow> = {}): AccountRelationRow {
  return {
    account_id, kind, detail: null, account_type: 'PERSONAL', display_name: null, owner_uuid: ME, owner_name: 'Steve',
    archived: 0, created_at: new Date('2025-01-01T00:00:00Z'), balance: '0.00', firm_id: null, firm_name: null, ...o,
  };
}

const ROWS: AccountRelationRow[] = [
  rel(1, 'owner', { balance: '1200.50' }),
  rel(2, 'member', { owner_uuid: OTHER, owner_name: 'Alex', balance: '999.00' }),
  // A bank: the player is proprietor, but the deposits are not theirs.
  rel(3, 'owner', { account_type: 'BUSINESS', display_name: 'Steve Bank Vault', balance: '1000000.00', firm_id: 7, firm_name: 'Steve Bank' }),
  rel(3, 'firm_finance', { account_type: 'BUSINESS', display_name: 'Steve Bank Vault', balance: '1000000.00', firm_id: 7, firm_name: 'Steve Bank', detail: 'Proprietor' }),
  rel(4, 'firm_finance', { account_type: 'BUSINESS', display_name: 'Steve Bank Payroll', owner_uuid: OTHER, balance: '250.25', firm_id: 7, firm_name: 'Steve Bank', detail: 'Proprietor' }),
  rel(5, 'viewer', { account_type: 'BUSINESS', display_name: 'Bakery Till', owner_uuid: OTHER, balance: '80.00', firm_id: 8, firm_name: 'Bakery' }),
  rel(6, 'group', { account_type: 'GOVERNMENT', display_name: 'Parks', owner_uuid: OTHER, balance: '-50.00', detail: 'parks' }),
  rel(6, 'viewer', { account_type: 'GOVERNMENT', display_name: 'Parks', owner_uuid: OTHER, balance: '-50.00' }),
];

const ROLES: FirmRoleRow[] = [
  { firm_id: 7, firm_name: 'Steve Bank', archived: 0, is_proprietor: 1, role_name: null, rank_order: null, permissions: null, joined_at: null, first_joined_at: null },
  { firm_id: 8, firm_name: 'Bakery', archived: 0, is_proprietor: 0, role_name: 'Baker', rank_order: 2, permissions: 'DEFAULT', joined_at: new Date('2026-01-01T00:00:00Z'), first_joined_at: null },
];

const groups = () => groupAccounts(toAccounts(ROWS), ME, ROLES);

describe('groupAccounts', () => {
  it('puts only the player’s own personal accounts in Mine, and totals only those', () => {
    const g = groups();
    const mine = g.find((x) => x.kind === 'mine')!;
    expect(mine.accounts.map((a) => a.id)).toEqual([1]);
    expect(mine.subtotal).toBe('1200.50');
    expect(mine.subtotalLabel).toBe('My balance');
    // The million in the bank vault is the firm's, in its own group.
    const bank = g.find((x) => x.firmId === 7)!;
    expect(bank.subtotal).toBe('1000250.25');
    expect(bank.subtotalLabel).toBe('Steve Bank’s money');
    expect(bank.role).toBe('Proprietor');
    expect(bank.note).toBeNull();
  });

  it('lists another player’s shared personal account apart, without a total', () => {
    const shared = groups().find((x) => x.kind === 'shared')!;
    expect(shared.accounts.map((a) => a.id)).toEqual([2]);
    expect(shared.subtotal).toBeNull();
  });

  it('says why a business account is shown when the player has no finance role there', () => {
    const bakery = groups().find((x) => x.firmId === 8)!;
    expect(bakery.role).toBe('Baker');
    expect(bakery.note).toBe('You have no finance role at Bakery. This account is shown because you were given view access.');
  });

  it('labels each relationship for what it is, not "member"', () => {
    const accounts = toAccounts(ROWS);
    const labels = (id: number) => accounts.find((a) => a.id === id)!.relations.map((r) => relationLabel(r, accounts.find((a) => a.id === id)!.type));
    expect(labels(1)).toEqual(['Owner']);
    expect(labels(2)).toEqual(['Member']);
    expect(labels(3)).toEqual(['Registered holder', 'Firm finance · Proprietor']);
    expect(labels(5)).toEqual(['Viewer']);
    expect(labels(6)).toEqual(['Viewer', 'Group · parks']);
  });
});

describe('<AccountGroups>', () => {
  it('renders each group with its own labelled subtotal and per-row relationships', () => {
    render(<AccountGroups groups={groups()} />);
    const mine = screen.getByRole('region', { name: 'Mine' });
    expect(within(mine).getByText('My balance')).toBeInTheDocument();
    expect(within(mine).getByText('$1,200.50', { selector: '.me-group-total .num' })).toBeInTheDocument();
    expect(within(mine).queryByText(/Steve Bank/)).toBeNull();

    const bank = screen.getByRole('region', { name: /^Steve Bank ?· your role/ });
    expect(within(bank).getByText('Steve Bank’s money')).toBeInTheDocument();
    expect(within(bank).getByText('$1,000,250.25', { selector: '.me-group-total .num' })).toBeInTheDocument();
    expect(within(bank).getByText('Registered holder')).toBeInTheDocument();
    expect(within(bank).getAllByText('Firm finance · Proprietor')).toHaveLength(2);

    const bakery = screen.getByRole('region', { name: /^Bakery ?· your role/ });
    expect(within(bakery).getByRole('note')).toHaveTextContent('no finance role at Bakery');

    const gov = screen.getByRole('region', { name: 'Government' });
    expect(within(gov).getByText('Group · parks')).toBeInTheDocument();
    expect(within(gov).getByText('−$50.00', { selector: '.me-group-total .num' })).toBeInTheDocument();

    // Nowhere does a total of everything appear.
    expect(screen.queryByText('$1,001,480.75')).toBeNull();
  });
});
