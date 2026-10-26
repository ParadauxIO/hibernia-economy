import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { heatLevel } from '@/components/charts/Heatmap';
import {
  axisWidth, CURVE, detectOutliers, estimateTextWidth, formatTick, formatValue, logTicks, niceTicks, symlog, symlogInverse, timeSeriesLayout, totalHeight, valueAxis,
} from '@/components/charts/kit';

// Regression tests for the chart bugs in spec §1.4 / §5.3. The browser-level
// checks (rendered geometry) are in test/e2e/charts.spec.ts.

describe('ticks fall on nice numbers', () => {
  it.each([
    [0, 97, [0, 20, 40, 60, 80, 100]],
    [0, 46_812, [0, 10_000, 20_000, 30_000, 40_000, 50_000]],
    [-2_500, 18_400, [-10_000, 0, 10_000, 20_000]],
    [-157_004_112, 0, [-200_000_000, -150_000_000, -100_000_000, -50_000_000, 0]],
  ])('niceTicks(%d, %d)', (min, max, ticks) => {
    expect(niceTicks(min, max)).toEqual(ticks);
  });

  it('value axes always include zero, so areas never float', () => {
    expect(valueAxis([38_200, 46_000]).domain[0]).toBe(0);
    expect(valueAxis([-120e6, -157e6]).domain[1]).toBe(0);
  });

  it('formats money ticks compactly without trailing zeros', () => {
    expect([0, 40_000, 1_250_000, -157_000_000, 999e9].map((v) => formatTick(v, 'money', '$'))).toEqual(['$0', '$40K', '$1.25M', '−$157M', '$999B']);
    expect(formatTick(-50_000_000, 'money', '£')).toBe('−£50M');
    expect(formatTick(1500, 'count', '$')).toBe('1.5K');
  });

  it('formats tooltip and table money exactly from DECIMAL strings', () => {
    expect(formatValue('999000000000.00', 'money', '$')).toBe('$999,000,000,000.00');
    expect(formatValue('0.10', 'money', '$')).toBe('$0.10');
    expect(formatValue(null, 'money', '$')).toBe('—');
  });
});

describe('y-axis labels are never clipped (§1.4 item 3)', () => {
  it('sizes the axis to the widest formatted label, minus sign included', () => {
    const labels = niceTicks(-157_004_112, 0).map((t) => formatTick(t, 'money', '$'));
    const width = axisWidth(labels);
    for (const label of labels) expect(width).toBeGreaterThan(estimateTextWidth(label));
    // The old fixed 56–60 px cut "−$150.00M" to "$150.00M".
    expect(width).toBeGreaterThan(estimateTextWidth('−$200M'));
  });

  it('grows with the label instead of using a fixed width', () => {
    expect(axisWidth(['$40K'])).toBeLessThan(axisWidth(['$40,000,000.00']));
  });
});

describe('one outlier day no longer flattens the chart (§1.4 item 6)', () => {
  const normal = Array.from({ length: 30 }, (_, i) => 50_000 + (i % 7) * 4_000);

  it('detects a value more than 10× the 95th percentile and caps the axis near the rest', () => {
    const values = normal.map((v, i) => (i === 17 ? 999e9 : v));
    const clamp = detectOutliers(values);
    expect(clamp).not.toBeNull();
    expect(clamp!.clipped).toEqual([17]);
    expect(clamp!.cap).toBeGreaterThanOrEqual(Math.max(...normal));
    expect(clamp!.cap).toBeLessThan(200_000);
  });

  it('leaves ordinary data alone', () => {
    expect(detectOutliers(normal)).toBeNull();
    expect(detectOutliers([1, 2, 3])).toBeNull();
    expect(detectOutliers(normal.map((v, i) => (i === 3 ? v * 3 : v)))).toBeNull();
  });

  it('offers a log scale that is defined at zero', () => {
    expect(symlog(0)).toBe(0);
    expect(symlogInverse(symlog(999e9))).toBeCloseTo(999e9, -3);
    const { values, ticks } = logTicks(999e9);
    expect(values[0]).toBe(0);
    expect(values.at(-1)).toBeGreaterThanOrEqual(999e9);
    expect(ticks).toEqual([...ticks].sort((a, b) => a - b));
  });

  it('reserves the toolbar row on the server exactly when the plot shows it', () => {
    const data = normal.map((v, i) => ({ date: `d${i}`, v: i === 17 ? '999000000000.00' : String(v) }));
    const series = [{ key: 'v', label: 'Volume' }];
    expect(timeSeriesLayout(data, series, true).toolbar).toBe(true);
    expect(timeSeriesLayout(data, series, false).toolbar).toBe(false);
    expect(totalHeight(240, { toolbar: true, axisTitles: true })).toBe(300);
  });
});

describe('curves never overshoot below zero (§1.4 item 5)', () => {
  const dir = resolve(process.cwd(), 'components/charts');
  const files = (d: string): string[] => readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? files(join(d, e.name)) : e.name.endsWith('.tsx') ? [join(d, e.name)] : []));
  const sources = files(dir).map((f) => ({ f, text: readFileSync(f, 'utf8') }));

  it('uses the monotone curve, which preserves monotonicity between points', () => {
    expect(CURVE).toBe('monotone');
  });

  it('never uses basis, natural or cardinal curves in any chart', () => {
    for (const { f, text } of sources) expect(text, f).not.toMatch(/type=["'{](basis|natural|cardinal|catmullRom|bump)/);
  });

  it('gives every recharts Area and Line the shared curve', () => {
    const plots = sources.filter(({ text }) => /from 'recharts'/.test(text));
    expect(plots.length).toBeGreaterThan(0);
    for (const { f, text } of plots) {
      const marks = text.match(/<(Area|Line)\b/g) ?? [];
      if (marks.length) expect(text, f).toMatch(/type: CURVE|type=\{CURVE\}/);
    }
  });
});

describe('heatmap levels', () => {
  it('maps counts to the sequential scale, 0 only for no activity', () => {
    expect(heatLevel(0, 100)).toBe(0);
    expect(heatLevel(1, 100)).toBe(1);
    expect(heatLevel(50, 100)).toBe(3);
    expect(heatLevel(100, 100)).toBe(5);
  });
});
