import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ItemTable, type PopularItem } from '@/components/market/ItemTable';

function item(overrides: Partial<PopularItem> = {}): PopularItem {
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

function row(name: string): HTMLElement {
  return screen.getByRole('row', { name: new RegExp(name) });
}

function text(el: HTMLElement, selector: string): string {
  return el.querySelector(selector)?.textContent ?? '';
}

describe('ItemTable', () => {
  it('shows the window length and an empty state with no items', () => {
    render(<ItemTable items={[]} days={30} />);
    expect(screen.getByText('Prices are historical daily averages per item · last 30 days.')).toBeInTheDocument();
    expect(screen.getByText(/No trades in this window/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse the full catalogue' })).toHaveAttribute('href', '/chestshop');
  });

  it('renders the current price and volume with full precision and grouping', () => {
    render(<ItemTable items={[item()]} days={7} />);
    const c = row('Diamond');
    expect(c.querySelector('a')).toHaveAttribute('href', '/chestshop/items/minecraft%3Adiamond');
    expect(text(c, '.item-price')).toBe('$12.50');
    // DECIMAL volume arrives as a string; fmtN groups the trade count.
    expect(text(c, '.item-trades')).toBe('1,234');
    expect(text(c, '.item-volume')).toBe('$98,765.43');
  });

  it.each<[string, number | null, string, string]>([
    ['typical', 12.5, '98765.43', '$12.50'],
    ['zero', 0, '0', '$0.00'],
    ['fractional (rounds to cents)', 0.125, '0.005', '$0.13'],
    ['large', 1234567.891, '12345678901.2', '$1,234,567.89'],
    ['missing', null, '0.00', '—'],
  ])('formats a %s price', (_label, current, volume, expected) => {
    render(<ItemTable items={[item({ current_price: current, total_volume: volume })]} days={7} />);
    expect(text(row('Diamond'), '.item-price')).toBe(expected);
  });

  it.each([
    ['0', '$0.00'],
    ['0.005', '$0.01'],
    ['12345678901.2', '$12,345,678,901.20'],
    ['-42.5', '−$42.50'],
  ])('formats a %s total volume as %s', (volume, expected) => {
    render(<ItemTable items={[item({ trade_count: 0, total_volume: volume })]} days={7} />);
    expect(text(row('Diamond'), '.item-volume')).toBe(expected);
  });

  it.each<[number | null, string | null, string]>([
    [0.1234, '▲ 12.3%', 'up'],
    [-0.05, '▼ 5.0%', 'down'],
    // Movement inside ±0.1% is "flat", not noise-driven arrows.
    [0.0004, '→ 0.0%', 'flat'],
    [null, null, ''],
  ])('shows a %s price change as %s', (pct, expected, cls) => {
    render(<ItemTable items={[item({ price_change_pct: pct })]} days={7} />);
    const delta = row('Diamond').querySelector('.kpi-delta');
    if (expected === null) {
      expect(delta).toBeNull();
    } else {
      expect(delta).toHaveTextContent(expected);
      expect(delta).toHaveClass(cls);
    }
  });

  it('falls back to the item key and a lettered tile when name/icon are missing', () => {
    render(<ItemTable items={[item({ item_name: null, icon: null, item_key: 'custom:widget' })]} days={7} />);
    const c = row('custom:widget');
    expect(text(c, '.item-name')).toBe('custom:widget');
    expect(c.querySelector('img')).toBeNull();
    expect(text(c, '.item-icon-fallback')).toBe('C');
  });

  it('refuses a non-http icon URL', () => {
    render(<ItemTable items={[item({ icon: 'javascript:alert(1)' })]} days={7} />);
    expect(row('Diamond').querySelector('img')).toBeNull();
  });

  it('draws a sparkline only with at least two points', () => {
    render(<ItemTable items={[item(), item({ item_key: 'minecraft:dirt', item_name: 'Dirt', series: [{ day: '2026-03-01', price: 1 }] })]} days={7} />);
    expect(row('Diamond').querySelector('.item-trend svg')).not.toBeNull();
    expect(row('Dirt').querySelector('.item-trend svg')).toBeNull();
    expect(text(row('Dirt'), '.item-trend')).toBe('—');
  });
});
