import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ShopTable } from '@/components/market/ShopTable';
import { AccountTransactions } from '@/components/entity/AccountTransactions';
import { Player } from '@/components/Player';
import type { TxnPage, TxnView } from '@/lib/services/account';
import { accountTxnsTable } from '@/lib/tables/accountTxns';
import { parseTableState } from '@/lib/tables/core';
import { resetNavigation } from './next-navigation';

vi.mock('next/navigation', () => import('./next-navigation'));

const MAP = 'https://map.example.net';

describe('ShopTable', () => {
  const shop = {
    shop_id: 1, item_key: 'DIAMOND', item_name: 'Diamond', material: 'DIAMOND', item_custom: 0, world: 'World', sign_x: 100, sign_y: 64, sign_z: -200,
    buy_price: '64.00', sell_price: '32.00', batch_qty: 64, current_stock: 10, current_space: 640, stock_at: new Date(), admin_shop: 0,
  };

  it('quotes per item, flags low stock and links to the map', () => {
    render(<ShopTable id="t" label="Shops" shops={[shop]} mapBase={MAP} />);
    const row = screen.getAllByRole('row')[1]!;
    expect(within(row).getByText('$1.00')).toBeInTheDocument();
    expect(within(row).getByText('$0.50')).toBeInTheDocument();
    expect(within(row).getAllByText('Below one batch')[0]).toBeInTheDocument();
    expect(within(row).getAllByRole('link', { name: 'Open World 100, 64, -200 on the map' })[0]).toHaveAttribute('href', `${MAP}/#world:100:64:-200:60:0:0:0:1:flat`);
    expect(within(row).getByRole('link', { name: 'Diamond' })).toHaveAttribute('href', '/chestshop/items/DIAMOND');
  });

  it('reads free space when the visitor is selling', () => {
    render(<ShopTable id="t" label="Shops" shops={[shop]} mapBase={MAP} direction="sell" />);
    expect(screen.getByRole('columnheader', { name: 'Space' })).toBeInTheDocument();
    expect(screen.getAllByText('Has space')[0]).toBeInTheDocument();
  });
});

describe('Player', () => {
  it('links to the profile with a head, never showing a raw UUID', () => {
    render(<Player name="Ada" uuid="00000000-0000-0000-0000-00000000a1ce" />);
    expect(screen.getByRole('link', { name: 'Ada' })).toHaveAttribute('href', '/players/Ada');
    render(<Player name={null} uuid="00000000-0000-0000-0000-00000000da7e" />);
    const unnamed = screen.getByRole('link', { name: '00000000…' });
    expect(unnamed).toHaveAttribute('href', '/players/00000000-0000-0000-0000-00000000da7e');
  });
});

describe('AccountTransactions', () => {
  const row: TxnView = {
    txnId: 42, at: new Date('2026-10-20T10:00:00Z'), amount: '-5.00', balanceAfter: '95.00', category: 'transfers', categoryLabel: 'Transfers',
    message: 'Payment to Bob', memo: 'rent', initiator: null,
    counterparty: { accountId: 2, name: 'Bob', accountType: 'PERSONAL', ownerUuid: null, ownerName: 'Bob' }, otherLegs: 0,
  };
  const page = (over: Partial<TxnPage>): TxnPage => ({ rows: [row], next: null, newer: null, searchedBackTo: null, implicitDays: null, runningBalance: true, entityLabels: {}, ...over });
  const search = vi.fn(async () => []);

  beforeEach(() => resetNavigation('/accounts/1'));

  it('shows the counterparty, signed amount and running balance, and pages by cursor', () => {
    render(<AccountTransactions pathname="/accounts/1" state={parseTableState({}, accountTxnsTable)} extra={[['range', '7d']]} searchEntity={search}
      page={page({ next: { at: new Date('2026-10-20T10:00:00Z'), id: 42 } })} />);
    expect(screen.getByRole('link', { name: /Bob/ })).toHaveAttribute('href', '/accounts/2');
    expect(screen.getByRole('columnheader', { name: 'Balance after' })).toBeInTheDocument();
    expect(screen.getAllByText('$95.00')[0]).toBeInTheDocument();
    const older = screen.getByRole('link', { name: 'Older' });
    expect(older.getAttribute('href')).toMatch(/^\/accounts\/1\?range=7d&cursor=[\w-]+#transactions$/);
  });

  it('leaves the balance out when it is too far back to place cheaply', () => {
    render(<AccountTransactions pathname="/accounts/1" state={parseTableState({}, accountTxnsTable)} extra={[]} searchEntity={search}
      page={page({ runningBalance: false })} />);
    expect(screen.queryByRole('columnheader', { name: 'Balance after' })).not.toBeInTheDocument();
  });

  it('says how far a filtered search looked when it found nothing', () => {
    render(<AccountTransactions pathname="/accounts/1" state={parseTableState({ f: 'category:in:fines' }, accountTxnsTable)} extra={[]} searchEntity={search}
      page={page({ rows: [], searchedBackTo: new Date('2026-09-01T00:00:00Z'), next: { at: new Date('2026-09-01T00:00:00Z'), id: 7 } })} />);
    expect(screen.getByText(/No matches in the 5,000 transactions before/)).toBeInTheDocument();
    expect(screen.getByText('Searched back to Sep 1, 2026')).toBeInTheDocument();
  });

  it('notes that text search without a date covers recent days', () => {
    render(<AccountTransactions pathname="/accounts/1" state={parseTableState({ q: 'rent' }, accountTxnsTable)} extra={[]} searchEntity={search}
      page={page({ implicitDays: 30 })} />);
    expect(screen.getByText(/Text search covers the past 30 days/)).toBeInTheDocument();
  });
});
