// PlayerDashboard is an async server component: resolve it with mocked data
// access, then render the returned tree in the DOM. Money rollups arrive as exact
// DECIMAL strings and must render to the cent without a float round-trip.
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ExplorerAccountRow, CounterpartyRow } from '@/lib/sql/ledger';
import type { PlayerTotals, PlayerTxnRow } from '@/lib/sql/me';
import { PlayerDashboard } from '@/components/PlayerDashboard';
import * as me from '@/lib/sql/me';
import { setViewer } from './viewer';

const UUID = '11111111-2222-3333-4444-555555555555';

const data = vi.hoisted(() => ({
  accounts: [] as ExplorerAccountRow[],
  allowed: new Set<number>(),
  totals: { totalBalance: '0.00', income: '0.00', spend: '0.00', net: '0.00' } as PlayerTotals,
  txnCount: 0,
  partners: [] as CounterpartyRow[],
  txns: [] as PlayerTxnRow[],
}));

vi.mock('@/lib/auth', () => ({
  getViewer: vi.fn(() => Promise.resolve({ anon: false })),
  requirePlayerRead: vi.fn(),
  canViewAccount: vi.fn((id: number) => Promise.resolve(data.allowed.has(id))),
}));

vi.mock('@/lib/sql/me', () => ({
  findAccountsForPlayer: vi.fn(() => Promise.resolve(data.accounts)),
  getPlayerTotals: vi.fn(() => Promise.resolve(data.totals)),
  countPlayerTransactions: vi.fn(() => Promise.resolve(data.txnCount)),
  getPlayerCounterparties: vi.fn(() => Promise.resolve(data.partners)),
  getPlayerTransactions: vi.fn(() => Promise.resolve(data.txns)),
}));

function account(overrides: Partial<ExplorerAccountRow> = {}): ExplorerAccountRow {
  return {
    account_id: 10,
    account_type: 'PERSONAL',
    display_name: UUID,
    owner_uuid: UUID,
    owner_name: 'Steve',
    archived: 0,
    requires_authorization: 0,
    allow_overdraft: 0,
    credit_limit: '0.00',
    created_at: new Date('2025-01-02T03:04:05Z'),
    balance: '1234.56',
    ...overrides,
  };
}

function txn(overrides: Partial<PlayerTxnRow> = {}): PlayerTxnRow {
  return {
    posting_id: 1,
    txn_id: 900,
    account_id: 10,
    account_name: 'Steve',
    account_type: 'PERSONAL',
    amount: '12.50',
    memo: null,
    message: 'Paid by Alex',
    settlement_time: new Date('2026-03-08T15:04:00Z'),
    plugin_system: null,
    ...overrides,
  };
}

async function renderDashboard(self = true) {
  render(await PlayerDashboard({ uuid: UUID, name: 'Steve', self }));
}

function kpi(label: string): HTMLElement {
  const el = screen.getByText(label).closest('.kpi');
  if (!(el instanceof HTMLElement)) throw new Error(`no KPI ${label}`);
  return el;
}

function kpiValue(label: string): HTMLElement {
  const el = kpi(label).querySelector('.kpi-value');
  if (!(el instanceof HTMLElement)) throw new Error(`no KPI value for ${label}`);
  return el;
}

function table(caption: string): HTMLElement {
  const card = screen.getByText(caption).closest('.card');
  const t = card?.querySelector('table');
  if (!(t instanceof HTMLElement)) throw new Error(`no table under ${caption}`);
  return t;
}

/** Cell text for each body row, in column order. */
function rows(t: HTMLElement): string[][] {
  return within(t)
    .getAllByRole('row')
    .slice(1)
    .map((r) => within(r).getAllByRole('cell').map((c) => c.textContent));
}

beforeEach(() => {
  data.accounts = [account()];
  data.allowed = new Set([10]);
  data.totals = { totalBalance: '1234.56', income: '500.00', spend: '250.25', net: '249.75' };
  data.txnCount = 3;
  data.partners = [];
  data.txns = [txn()];
  vi.mocked(me.getPlayerTotals).mockClear();
  setViewer('player');
});

describe('PlayerDashboard KPIs', () => {
  it('renders typical DECIMAL totals to the cent', async () => {
    await renderDashboard();
    expect(kpiValue('Total balance')).toHaveTextContent('$1,234.56');
    expect(kpiValue('Income / 90d')).toHaveTextContent('$500.00');
    expect(kpiValue('Spend / 90d')).toHaveTextContent('$250.25');
    expect(kpiValue('Net / 90d')).toHaveTextContent('+$249.75');
    expect(kpiValue('Net / 90d')).toHaveClass('pos');
    expect(kpi('Total balance')).toHaveTextContent('across 1 accounts');
    expect(kpi('Net / 90d')).toHaveTextContent('3 txns');
  });

  it('renders zero totals as +$0.00 / $0.00', async () => {
    data.totals = { totalBalance: '0.00', income: '0.00', spend: '0.00', net: '0.00' };
    await renderDashboard();
    expect(kpiValue('Total balance')).toHaveTextContent(/^\$0\.00$/);
    expect(kpiValue('Net / 90d')).toHaveTextContent(/^\+\$0\.00$/);
    expect(kpiValue('Net / 90d')).toHaveClass('pos');
  });

  it('renders a negative net with a true minus sign and no plus', async () => {
    data.totals = { totalBalance: '-75.10', income: '10.00', spend: '85.10', net: '-75.10' };
    await renderDashboard();
    expect(kpiValue('Total balance')).toHaveTextContent(/^−\$75\.10$/);
    expect(kpiValue('Net / 90d')).toHaveTextContent(/^−\$75\.10$/);
    expect(kpiValue('Net / 90d')).toHaveClass('neg');
  });

  it('decides the net sign from the string, so a fractional loss is still negative', async () => {
    data.totals = { totalBalance: '0.01', income: '0.00', spend: '0.01', net: ' -0.01' };
    await renderDashboard();
    expect(kpiValue('Net / 90d')).toHaveTextContent(/^−\$0\.01$/);
    expect(kpiValue('Net / 90d')).toHaveClass('neg');
  });

  it('groups large balances without switching to an abbreviated form', async () => {
    data.totals = { totalBalance: '12345678901.23', income: '1000000.00', spend: '999999.99', net: '0.01' };
    await renderDashboard();
    expect(kpiValue('Total balance')).toHaveTextContent('$12,345,678,901.23');
    expect(kpiValue('Income / 90d')).toHaveTextContent('$1,000,000.00');
    expect(kpiValue('Spend / 90d')).toHaveTextContent('$999,999.99');
    expect(kpiValue('Net / 90d')).toHaveTextContent('+$0.01');
  });
});

describe('PlayerDashboard tables', () => {
  it('only shows accounts the viewer may read, and only sums those', async () => {
    data.accounts = [account(), account({ account_id: 11, display_name: 'Secret Vault', owner_uuid: 'someone-else', balance: '999.00' })];
    data.allowed = new Set([10]);
    await renderDashboard();
    expect(screen.queryByText('Secret Vault')).toBeNull();
    expect(vi.mocked(me.getPlayerTotals)).toHaveBeenCalledWith([10], 90);
    expect(rows(table('Your accounts'))).toEqual([
      // UUID display_name is replaced by the resolved owner name.
      ['Steve #10', 'PERSONAL', 'owner', '$1,234.56', expect.any(String)],
    ]);
    expect(table('Your accounts').querySelector('time')).toHaveAttribute('datetime', '2025-01-02T03:04:05.000Z');
  });

  it('labels shared accounts as member and formats each balance', async () => {
    data.accounts = [
      account({ account_id: 20, display_name: 'Acme Payroll', owner_uuid: 'firm-owner', account_type: 'BUSINESS', balance: '0.00' }),
      account({ account_id: 21, display_name: 'Overdrawn', balance: '-3.5' }),
    ];
    data.allowed = new Set([20, 21]);
    await renderDashboard(false);
    const body = rows(table('Accounts'));
    expect(body.map((r) => r.slice(0, 4))).toEqual([
      ['Acme Payroll #20', 'BUSINESS', 'member', '$0.00'],
      ['Overdrawn #21', 'PERSONAL', 'owner', '−$3.50'],
    ]);
  });

  it('signs transaction amounts: + for credits (incl. zero), − for debits', async () => {
    data.txns = [
      txn({ posting_id: 1, amount: '12.50' }),
      txn({ posting_id: 2, amount: '-3.25', message: null, memo: 'rent' }),
      txn({ posting_id: 3, amount: '0.00', message: 'Zero' }),
      txn({ posting_id: 4, amount: '1500000.00', account_name: null, account_id: 77 }),
    ];
    await renderDashboard();
    const body = rows(table('Recent transactions'));
    expect(body.map((r) => r[2])).toEqual(['+$12.50', '−$3.25', '+$0.00', '+$1,500,000.00']);
    expect(body.map((r) => r[1])).toEqual(['Steve', 'Steve', 'Steve', '#77']);
    expect(body[1]?.[3]).toBe('— · rent');
    const amountCells = within(table('Recent transactions')).getAllByText(/\$/);
    expect(amountCells.map((c) => c.classList.contains('pos'))).toEqual([true, false, true, true]);
  });

  it('shows empty states and hides counterparties when there is no activity', async () => {
    data.accounts = [];
    data.txns = [];
    await renderDashboard();
    expect(screen.getByText('No accounts found.')).toBeInTheDocument();
    expect(screen.getByText('No transactions yet.')).toBeInTheDocument();
    expect(screen.queryByText('Top counterparties')).toBeNull();
  });

  it('lists counterparties with their total volume', async () => {
    data.partners = [
      { counterparty_id: 5, display_name: 'Bank of Hibernia', account_type: 'GOVERNMENT', owner_uuid: null, owner_name: null, txn_count: 1200, total_volume: '45000.5' },
    ];
    await renderDashboard();
    expect(rows(table('Top counterparties'))).toEqual([['Bank of Hibernia #5', 'GOVERNMENT', '1,200', '$45,000.50']]);
  });
});

describe('PlayerDashboard exports', () => {
  it('offers CSV/JSON exports to a logged-in viewer', async () => {
    await renderDashboard();
    expect(screen.getAllByRole('button', { name: 'CSV' })).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: 'JSON' })).toHaveLength(2);
  });

  it('hides exports from an anonymous viewer', async () => {
    setViewer('anon');
    await renderDashboard();
    expect(screen.queryByRole('button', { name: 'CSV' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'JSON' })).toBeNull();
  });
});
