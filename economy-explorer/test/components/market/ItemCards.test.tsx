import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ItemCards, type ItemCard } from '@/components/market/ItemCards';

function item(overrides: Partial<ItemCard> = {}): ItemCard {
  return {
    item_key: 'minecraft:diamond',
    item_name: 'Diamond',
    material: 'DIAMOND',
    icon: '/icons/diamond.png',
    trade_count: 1234,
    total_quantity: 5000,
    total_volume: '98765.43',
    current_price: 12.5,
    price_change_pct: 0.1234,
    series: [
      { day: '2026-03-01', price: 11 },
      { day: '2026-03-02', price: 12.5 },
    ],
    ...overrides,
  };
}

function card(name: string): HTMLElement {
  return screen.getByRole('link', { name: new RegExp(name) });
}

function text(el: HTMLElement, selector: string): string {
  return el.querySelector(selector)?.textContent ?? '';
}

describe('ItemCards', () => {
  it('shows the window length and an empty state with no items', () => {
    render(<ItemCards items={[]} days={30} />);
    expect(screen.getByText('price trend · last 30 days')).toBeInTheDocument();
    expect(screen.getByText(/No trades in this window/)).toBeInTheDocument();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders the current price and volume with full precision and grouping', () => {
    render(<ItemCards items={[item()]} days={7} />);
    const c = card('Diamond');
    expect(c).toHaveAttribute('href', '/chestshop/items/minecraft%3Adiamond');
    expect(text(c, '.item-card-price-val')).toBe('$12.50');
    // DECIMAL volume arrives as a string; fmtN groups the trade count.
    expect(text(c, '.item-card-meta')).toBe('1,234 trades · $98,765.43');
  });

  it.each<[string, number | null, string, string]>([
    ['typical', 12.5, '98765.43', '$12.50'],
    ['zero', 0, '0', '$0.00'],
    ['fractional (rounds to cents)', 0.125, '0.005', '$0.13'],
    ['large', 1234567.891, '12345678901.2', '$1,234,567.89'],
    ['missing', null, '0.00', '—'],
  ])('formats a %s price', (_label, current, volume, expected) => {
    render(<ItemCards items={[item({ current_price: current, total_volume: volume })]} days={7} />);
    expect(text(card('Diamond'), '.item-card-price-val')).toBe(expected);
  });

  it.each([
    ['0', '$0.00'],
    ['0.005', '$0.01'],
    ['12345678901.2', '$12,345,678,901.20'],
    ['-42.5', '−$42.50'],
  ])('formats a %s total volume as %s', (volume, expected) => {
    render(<ItemCards items={[item({ trade_count: 0, total_volume: volume })]} days={7} />);
    expect(text(card('Diamond'), '.item-card-meta')).toBe(`0 trades · ${expected}`);
  });

  it.each<[number | null, string | null, string]>([
    [0.1234, '▲ 12.3%', 'up'],
    [-0.05, '▼ 5.0%', 'down'],
    // Movement inside ±0.1% is "flat", not noise-driven arrows.
    [0.0004, '→ 0.0%', 'flat'],
    [null, null, ''],
  ])('shows a %s price change as %s', (pct, expected, cls) => {
    render(<ItemCards items={[item({ price_change_pct: pct })]} days={7} />);
    const delta = card('Diamond').querySelector('.kpi-delta');
    if (expected === null) {
      expect(delta).toBeNull();
    } else {
      expect(delta).toHaveTextContent(expected);
      expect(delta).toHaveClass(cls);
    }
  });

  it('falls back to the item key and a lettered tile when name/icon are missing', () => {
    render(<ItemCards items={[item({ item_name: null, icon: null, item_key: 'custom:widget' })]} days={7} />);
    const c = card('custom:widget');
    expect(text(c, '.item-card-name')).toBe('custom:widget');
    expect(c.querySelector('img')).toBeNull();
    expect(text(c, '.item-icon-fallback')).toBe('C');
  });

  it('refuses a non-http icon URL', () => {
    render(<ItemCards items={[item({ icon: 'javascript:alert(1)' })]} days={7} />);
    expect(card('Diamond').querySelector('img')).toBeNull();
  });

  it('draws a sparkline only with at least two points', () => {
    render(<ItemCards items={[item(), item({ item_key: 'minecraft:dirt', item_name: 'Dirt', series: [{ day: '2026-03-01', price: 1 }] })]} days={7} />);
    expect(card('Diamond').querySelector('.item-card-spark svg')).not.toBeNull();
    expect(card('Dirt').querySelector('.item-card-spark svg')).toBeNull();
    expect(text(card('Dirt'), '.item-card-spark')).toBe('—');
  });
});
