// Waterfall geometry, pure so the server wrapper, the client plot and the unit
// tests agree. Money stays in integer cents until it becomes a chart coordinate.
//
// The problem this solves: a monthly statement opens at, say, $41,210 and the
// month's categories move it by a few hundred or thousand dollars. With the
// value axis forced to start at $0 the opening and closing bars fill the plot
// and every category bar is a sliver a few pixels tall. So when the running
// total stays far from zero, the axis is fitted to the range the running total
// actually covers (with padding) instead. The opening and closing bars then
// start at the bottom of the plot rather than at $0; they are marked as cut
// and the chart says where the axis starts, so nobody reads their height as
// their value. When the changes are large relative to the balances, or the
// running total crosses zero, the axis includes zero as usual.

import { toNumber, type Cents } from '@/lib/money';
import { niceTicks } from './kit';

export interface WaterfallInputStep {
  key: string;
  label: string;
  cents: Cents;
}

export interface WaterfallBar {
  key: string;
  label: string;
  kind: 'opening' | 'step' | 'closing';
  /** Exact amount: the balance for opening/closing, the signed change for a step. */
  cents: Cents;
  /** [low, high] in chart coordinates (dollars). */
  range: [number, number];
  /** The bar is drawn from the axis floor, not from zero: its height is not its value. */
  cut: boolean;
}

export interface WaterfallLayout {
  bars: WaterfallBar[];
  domain: [number, number];
  ticks: number[];
  /** The value axis does not include zero. */
  broken: boolean;
  /** Where cut bars start: the axis edge nearest zero. 0 when not broken. */
  floor: number;
}

/**
 * Break the axis when the running total never crosses zero and its whole span
 * is less than this share of its distance from zero. At 0.5, a statement whose
 * flows move the balance by less than half of it gets a fitted axis.
 */
export const BREAK_RATIO = 0.5;

export function waterfallLayout(
  opening: Cents, steps: readonly WaterfallInputStep[], closing: Cents,
  labels: { opening: string; closing: string } = { opening: 'Opening', closing: 'Closing' },
): WaterfallLayout {
  // Running levels in cents: exact, so the step ranges meet without drift.
  const levels: Cents[] = [opening];
  for (const s of steps) levels.push(levels[levels.length - 1]! + s.cents);
  const all = [...levels, closing];
  let lo = all[0]!;
  let hi = all[0]!;
  for (const v of all) { if (v < lo) lo = v; if (v > hi) hi = v; }
  const loN = toNumber(lo);
  const hiN = toNumber(hi);
  const span = hiN - loN;

  let broken = false;
  let ticks: number[] = [];
  if (span > 0 && loN > 0 && span < hiN * BREAK_RATIO) {
    // All positive, far from zero: fit to [lo, hi] with room above and below.
    const pad = span * 0.15;
    ticks = niceTicks(loN - pad, hiN + pad, 5);
    broken = ticks[0]! > 0;
  } else if (span > 0 && hiN < 0 && span < -loN * BREAK_RATIO) {
    // All negative (an overdrawn account), far from zero: mirror of the above.
    const pad = span * 0.15;
    ticks = niceTicks(loN - pad, hiN + pad, 5);
    broken = ticks[ticks.length - 1]! < 0;
  }
  // Otherwise (or if padding reached zero anyway) the axis includes zero.
  if (!broken) ticks = niceTicks(Math.min(0, loN), Math.max(0, hiN), 5);
  const domain: [number, number] = [ticks[0]!, ticks[ticks.length - 1]!];
  // A cut bar starts at the edge of the plot nearest zero.
  const floor = !broken ? 0 : domain[0] > 0 ? domain[0] : domain[1];

  const total = (key: string, label: string, kind: 'opening' | 'closing', cents: Cents): WaterfallBar => {
    const v = toNumber(cents);
    return { key, label, kind, cents, range: [Math.min(floor, v), Math.max(floor, v)], cut: broken };
  };
  const bars: WaterfallBar[] = [total('opening', labels.opening, 'opening', opening)];
  steps.forEach((s, i) => {
    const from = toNumber(levels[i]!);
    const to = toNumber(levels[i + 1]!);
    bars.push({ key: s.key, label: s.label, kind: 'step', cents: s.cents, range: [Math.min(from, to), Math.max(from, to)], cut: false });
  });
  bars.push(total('closing', labels.closing, 'closing', closing));
  return { bars, domain, ticks, broken, floor };
}
