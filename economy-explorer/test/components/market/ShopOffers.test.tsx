import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ShopOffers } from '@/components/market/ShopOffers';
import type { MarketShopRow } from '@/lib/sql/market';

const MAP = 'https://map.example.net';

function shop(overrides: Partial<MarketShopRow> = {}): MarketShopRow {
  return {
    shop_id: 1,
    world: 'World',
    sign_x: 100,
    sign_y: 64,
    sign_z: -200,
    admin_shop: 0,
    shop_account_type: 'PERSONAL',
    shop_firm_id: null,
    shop_owner_uuid: '00000000-0000-0000-0000-000000000001',
    owner_name: 'Alex',
    material: 'DIAMOND',
    item_key: 'minecraft:diamond',
    item_name: 'Diamond',
    item_custom: 0,
    buy_price: '10.00',
    sell_price: '6.40',
    batch_qty: 64,
    current_stock: 128,
    current_space: 32,
    stock_at: new Date('2026-03-08T15:04:00Z'),
    last_seen: new Date('2026-03-09T09:30:00Z'),
    active: 1,
    ...overrides,
  };
}

function offerCard(name: string): HTMLElement {
  const card = screen.getByRole('heading', { name }).closest('article');
  if (!card) throw new Error(`no offer card for ${name}`);
  return card;
}

/** The definition (<dd>) that follows a term (<dt>) inside a card. */
function detail(card: HTMLElement, term: string): string {
  const dt = within(card).getByText(term, { selector: 'dt' });
  return dt.nextElementSibling?.textContent ?? '';
}

function price(card: HTMLElement): string {
  return card.querySelector('.offer-price')?.textContent ?? '';
}

describe('ShopOffers', () => {
  it('shows the empty state when nothing matches', () => {
    render(<ShopOffers shops={[]} direction="buy" mapBase={MAP} />);
    expect(screen.getByText(/No observed offers match/)).toBeInTheDocument();
    expect(screen.queryByRole('article')).toBeNull();
  });

  it('prices a buy offer per item from the sign batch (DECIMAL string ÷ batch, up to 4 dp)', () => {
    render(<ShopOffers shops={[shop()]} direction="buy" mapBase={MAP} />);
    const card = offerCard('Diamond');
    // 10.00 / 64 = 0.15625 → rounded to 4 dp.
    expect(price(card)).toBe('$0.1563 / item');
    expect(detail(card, 'Sign batch')).toBe('64 items · $10.00');
    expect(within(card).getByText(/^Buy from/)).toHaveTextContent('Buy from Alex');
  });

  it('uses the sell price and "Sell to" wording for the sell direction', () => {
    render(<ShopOffers shops={[shop()]} direction="sell" mapBase={MAP} />);
    const card = offerCard('Diamond');
    expect(price(card)).toBe('$0.10 / item');
    expect(detail(card, 'Sign batch')).toBe('64 items · $6.40');
    expect(within(card).getByText(/^Sell to/)).toHaveTextContent('Sell to Alex');
  });

  it('scales to a stack of 64 when units=64', () => {
    render(<ShopOffers shops={[shop({ buy_price: '12.50', batch_qty: 16 })]} direction="buy" units={64} mapBase={MAP} />);
    expect(price(offerCard('Diamond'))).toBe('$50.00 / 64 items');
  });

  it.each([
    ['0.00', 1, '$0.00'],
    ['1234567.50', 1, '$1,234,567.50'],
    ['0.0025', 1, '$0.0025'],
    ['1.00', 3, '$0.3333'],
  ])('formats a %s batch of %i as %s per item', (buyPrice, batch, expected) => {
    render(<ShopOffers shops={[shop({ buy_price: buyPrice, batch_qty: batch })]} direction="buy" mapBase={MAP} />);
    expect(price(offerCard('Diamond'))).toBe(`${expected} / item`);
  });

  it('never shows a missing or zero-batch price as free', () => {
    render(<ShopOffers shops={[shop({ buy_price: '10.00', batch_qty: 0 })]} direction="buy" mapBase={MAP} />);
    expect(price(offerCard('Diamond'))).toBe('Unavailable / item');
  });

  it('describes admin shops as unlimited regardless of observed stock', () => {
    render(<ShopOffers shops={[shop({ admin_shop: 1, current_stock: 0, current_space: 0 })]} direction="buy" mapBase={MAP} />);
    const card = offerCard('Diamond');
    expect(within(card).getByText(/^Buy from/)).toHaveTextContent('Buy from Admin shop');
    expect(detail(card, 'Observed stock')).toBe('Unlimited');
    expect(detail(card, 'Space available')).toBe('Unlimited');
  });

  it('reports unknown stock/space/owner instead of zero', () => {
    render(<ShopOffers shops={[shop({ current_stock: null, current_space: null, owner_name: null })]} direction="buy" mapBase={MAP} />);
    const card = offerCard('Diamond');
    expect(detail(card, 'Observed stock')).toBe('Unknown');
    expect(detail(card, 'Space available')).toBe('Unknown');
    expect(within(card).getByText(/^Buy from/)).toHaveTextContent('Buy from Unknown owner');
  });

  it('shows observed stock and space counts', () => {
    render(<ShopOffers shops={[shop()]} direction="buy" mapBase={MAP} />);
    const card = offerCard('Diamond');
    expect(detail(card, 'Observed stock')).toBe('128');
    expect(detail(card, 'Space available')).toBe('32 items');
  });

  it('links the item page and a BlueMap deep link for the sign location', () => {
    render(<ShopOffers shops={[shop({ item_name: null, item_key: 'custom:gold bar' })]} direction="buy" mapBase={MAP} />);
    const card = offerCard('custom:gold bar');
    expect(within(card).getByRole('link', { name: 'custom:gold bar' })).toHaveAttribute('href', '/chestshop/items/custom%3Agold%20bar');
    const map = within(card).getByRole('link', { name: /World 100, 64, -200/ });
    expect(map).toHaveAttribute('href', `${MAP}/#world:100:64:-200:60:0:0:0:1:flat`);
    expect(map).toHaveAttribute('target', '_blank');
    expect(map).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('shows the location as plain text when the world is unknown', () => {
    render(<ShopOffers shops={[shop({ world: null })]} direction="buy" mapBase={MAP} />);
    const card = offerCard('Diamond');
    expect(within(card).getByText('Unknown world 100, 64, -200')).toBeInTheDocument();
    expect(within(card).queryByRole('link', { name: /Unknown world/ })).toBeNull();
  });

  it('renders the stock and last-seen timestamps as machine-readable <time>s', () => {
    render(<ShopOffers shops={[shop({ last_seen: null })]} direction="buy" mapBase={MAP} />);
    const card = offerCard('Diamond');
    const checked = within(card).getByText('Stock and space checked', { selector: 'dt' }).nextElementSibling;
    expect(checked?.querySelector('time')).toHaveAttribute('datetime', '2026-03-08T15:04:00.000Z');
    expect(detail(card, 'Shop seen')).toBe('—');
  });
});
