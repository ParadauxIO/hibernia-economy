import { describe, expect, it } from 'vitest';
import { BREAK_RATIO, waterfallLayout } from '@/components/charts/waterfallLayout';
import { parseMoney } from '@/lib/money';

const step = (key: string, value: string) => ({ key, label: key, cents: parseMoney(value) });
// The gallery's October statement: a $41K balance moved by a few thousand.
const OCTOBER = [step('wages', '8400.00'), step('sales', '2310.50'), step('purchases', '-1845.20'), step('tax', '-912.40'), step('fines', '-250.00'), step('transfers', '-3000.00')];

/** Share of the plotted value axis a bar covers. */
function share(range: [number, number], domain: [number, number]): number {
  return (range[1] - range[0]) / (domain[1] - domain[0]);
}

describe('waterfallLayout', () => {
  it('regression: category bars stay legible next to a large opening balance', () => {
    // Before the fix the axis started at $0 and ran to ~$50K, so the $250 fine
    // was 0.5% of the plot (about one pixel at 260 px) and wages ~17%.
    const layout = waterfallLayout(parseMoney('41210.30'), OCTOBER, parseMoney('45913.20'));
    expect(layout.broken).toBe(true);
    expect(layout.domain[0]).toBeGreaterThan(0);
    const steps = layout.bars.filter((b) => b.kind === 'step');
    // The old zero-based axis ran 0..$60K. Every step is now at least 2.5x taller.
    const zeroBased: [number, number] = [0, 60_000];
    for (const s of steps) expect(share(s.range, layout.domain)).toBeGreaterThan(2.5 * share(s.range, zeroBased));
    expect(share(steps[0]!.range, layout.domain)).toBeGreaterThan(0.4);
    // At the default 260 px plot even the $250 fine is several pixels tall.
    expect(share(steps[4]!.range, layout.domain) * 260).toBeGreaterThanOrEqual(3);
  });

  it('marks the opening and closing bars cut and starts them at the axis floor, never at a fake value', () => {
    const layout = waterfallLayout(parseMoney('41210.30'), OCTOBER, parseMoney('45913.20'));
    const [first, last] = [layout.bars[0]!, layout.bars[layout.bars.length - 1]!];
    expect(first.cut && last.cut).toBe(true);
    expect(first.range).toEqual([layout.floor, 41210.3]);
    expect(last.range).toEqual([layout.floor, 45913.2]);
    expect(layout.floor).toBe(layout.domain[0]);
    // The running total stays inside the axis with room to spare.
    expect(layout.domain[0]).toBeLessThan(41210.3 - 1000);
    expect(layout.domain[1]).toBeGreaterThan(51000);
  });

  it('keeps exact amounts and contiguous steps (each starts where the last ended)', () => {
    const layout = waterfallLayout(parseMoney('41210.30'), OCTOBER, parseMoney('45913.20'));
    expect(layout.bars.map((b) => b.cents)).toEqual([4121030n, 840000n, 231050n, -184520n, -91240n, -25000n, -300000n, 4591320n]);
    const steps = layout.bars.filter((b) => b.kind === 'step');
    expect(steps[0]!.range).toEqual([41210.3, 49610.3]);
    expect(steps[2]!.range).toEqual([50075.6, 51920.8]);
  });

  it('keeps a zero baseline when the changes are large next to the balance', () => {
    const layout = waterfallLayout(parseMoney('1200.00'), OCTOBER, parseMoney('5903.90'));
    expect(layout.broken).toBe(false);
    expect(layout.domain[0]).toBeLessThanOrEqual(0);
    expect(layout.bars[0]!.range).toEqual([0, 1200]);
    expect(layout.bars.every((b) => !b.cut)).toBe(true);
  });

  it('keeps zero when the running total crosses it', () => {
    const layout = waterfallLayout(parseMoney('100.00'), [step('spend', '-300.00'), step('pay', '250.00')], parseMoney('50.00'));
    expect(layout.broken).toBe(false);
    expect(layout.domain[0]).toBeLessThan(0);
    expect(layout.domain[1]).toBeGreaterThan(0);
  });

  it('fits an overdrawn account from the top, cutting at the upper edge', () => {
    const layout = waterfallLayout(parseMoney('-50000.00'), [step('a', '-500.00'), step('b', '200.00')], parseMoney('-50300.00'));
    expect(layout.broken).toBe(true);
    expect(layout.domain[1]).toBeLessThan(0);
    expect(layout.floor).toBe(layout.domain[1]);
    expect(layout.bars[0]!.range).toEqual([-50000, layout.floor]);
  });

  it('does not break the axis for a flat month (no movement)', () => {
    const layout = waterfallLayout(parseMoney('41210.30'), [], parseMoney('41210.30'));
    expect(layout.broken).toBe(false);
  });

  it('breaks at the documented threshold only', () => {
    // span / high just under and just over BREAK_RATIO.
    const under = waterfallLayout(10_000_00n, [step('x', String(10_000 * BREAK_RATIO - 100))], 10_000_00n + BigInt(Math.round((10_000 * BREAK_RATIO - 100) * 100)));
    const over = waterfallLayout(10_000_00n, [step('x', '20000.00')], 30_000_00n);
    expect(under.broken).toBe(true);
    expect(over.broken).toBe(false);
  });
});
