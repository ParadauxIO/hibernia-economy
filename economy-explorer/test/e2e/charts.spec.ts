import { test, expect, type Locator, type Page } from '@playwright/test';
import { visit } from './helpers';

// Browser regression tests for the chart bugs in spec §1.4 / §5.3, against the
// fixed fixtures on /design-system (no database data needed). Pure helpers are
// covered in test/unit/chart-kit.test.ts.

async function box(l: Locator) {
  const b = await l.boundingBox();
  if (!b) throw new Error('not visible');
  return b;
}

// Recharts 3 draws tick labels in their own layer, outside the axis group.
const Y_TICKS = '.recharts-yAxis-tick-labels .recharts-cartesian-axis-tick-value';

/** The y-axis label group on one side, picked by position (left = smallest x). */
async function yLabels(chart: Locator, side: 'left' | 'right'): Promise<Locator> {
  const groups = await chart.locator('.recharts-yAxis-tick-labels').all();
  const xs = await Promise.all(groups.map(async (g) => (await box(g)).x));
  const order = xs.map((x, i) => [x, i] as const).sort((a, b) => a[0] - b[0]);
  const pick = side === 'left' ? order[0] : order[order.length - 1];
  return groups[pick![1]]!;
}

async function tickTexts(chart: Locator, side: 'left' | 'right'): Promise<string[]> {
  return (await yLabels(chart, side)).locator('.recharts-cartesian-axis-tick-value').allTextContents();
}

async function cssVar(page: Page, name: string): Promise<string> {
  // Resolve a token to the browser's computed rgb() form for comparison.
  return page.evaluate((n) => {
    const probe = document.createElement('span');
    probe.style.color = `var(${n})`;
    document.body.append(probe);
    const c = getComputedStyle(probe).color;
    probe.remove();
    return c;
  }, name);
}

test.describe('chart regressions', () => {
  test.beforeEach(async ({ page }) => { await visit(page, '/design-system'); });

  test('y-axis labels are never clipped, minus signs included (§1.4 item 3)', async ({ page }) => {
    for (const id of ['#chart-treasury', '#chart-player', '#chart-inout']) {
      const chart = page.locator(id);
      await chart.scrollIntoViewIfNeeded();
      const plot = await box(chart.locator('.chart-svg'));
      const ticks = chart.locator(Y_TICKS);
      expect(await ticks.count()).toBeGreaterThan(2);
      for (const tick of await ticks.all()) {
        const b = await box(tick);
        expect(b.x, `${id} tick "${await tick.textContent()}"`).toBeGreaterThanOrEqual(plot.x - 0.5);
      }
    }
    expect(await page.locator(`#chart-treasury ${Y_TICKS}`).allTextContents()).toContain('−$150M');
  });

  test('curves stay above zero over idle days (§1.4 item 5)', async ({ page }) => {
    const chart = page.locator('#chart-price');
    await chart.scrollIntoViewIfNeeded();
    const zeroBox = await box(chart.locator(Y_TICKS).filter({ hasText: /^\$0$/ }));
    const curve = await box(chart.locator('.recharts-line-curve'));
    expect(curve.y + curve.height).toBeLessThanOrEqual(zeroBox.y + zeroBox.height / 2 + 1);
  });

  test('one outlier day is clamped and flagged, with a log scale (§1.4 item 6)', async ({ page, isMobile }) => {
    const chart = page.locator('#chart-volume-outlier');
    await chart.scrollIntoViewIfNeeded();
    await expect(chart.locator('[data-outlier="true"]')).toHaveCount(1);
    // The note gives way to the legend on phones; the flag and toggle stay.
    if (!isMobile) await expect(chart.getByText('1 day is above the scale')).toBeVisible();
    const clamped = await tickTexts(chart, 'left');
    expect(clamped.some((t) => t.endsWith('B'))).toBe(false); // the axis follows the other 29 days
    const log = chart.getByRole('button', { name: 'Log scale' });
    await log.click();
    await expect(log).toHaveAttribute('aria-pressed', 'true');
    await expect(chart.locator('[data-outlier="true"]')).toHaveCount(0);
    expect((await tickTexts(chart, 'left')).some((t) => t.endsWith('B'))).toBe(true);
  });

  test('dual-axis charts label both axes in their series colour (§5.3)', async ({ page, isMobile }) => {
    const chart = page.locator('#chart-dual');
    await chart.scrollIntoViewIfNeeded();
    const volume = await cssVar(page, '--cat-1');
    const count = await cssVar(page, '--cat-3');
    await expect(chart.locator('.chart-axis-title.is-left')).toHaveText('Volume');
    await expect(chart.locator('.chart-axis-title.is-left')).toHaveCSS('color', volume);
    const transactions = chart.getByRole('button', { name: /Transactions/ });
    // Phones start with volume only; the legend brings the second axis back.
    if (isMobile) {
      await expect(transactions).toHaveAttribute('aria-pressed', 'false');
      await transactions.click();
    }
    await expect(transactions).toHaveAttribute('aria-pressed', 'true');
    await expect(chart.locator('.chart-axis-title.is-right')).toHaveText('Transactions');
    await expect(chart.locator('.chart-axis-title.is-right')).toHaveCSS('color', count);
    const rightTick = (await yLabels(chart, 'right')).locator('.recharts-cartesian-axis-tick-value').first();
    await expect(rightTick).toHaveCSS('fill', count);
    const leftTick = (await yLabels(chart, 'left')).locator('.recharts-cartesian-axis-tick-value').first();
    await expect(leftTick).toHaveCSS('fill', volume);
    // Toggling a series off from the legend hides it.
    await transactions.click();
    await expect(transactions).toHaveAttribute('aria-pressed', 'false');
  });

  test('the heatmap fills its card and shows all 24 hours (§1.4 item 4)', async ({ page }) => {
    const frame = page.locator('#chart-heatmap .chart-frame');
    await frame.scrollIntoViewIfNeeded();
    const heat = frame.locator('.heat');
    const frameBox = await box(frame);
    const heatBox = await box(heat);
    expect(heatBox.width).toBeGreaterThanOrEqual(frameBox.width * 0.98);
    await expect(heat.locator('.heat-cell')).toHaveCount(7 * 24);
    const last = await box(heat.locator('.heat-cell').nth(23));
    expect(last.x + last.width).toBeLessThanOrEqual(heatBox.x + heatBox.width + 0.5);
    expect(last.width).toBeGreaterThan(5);
    expect(await heat.evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(0);
  });

  test('every chart has an accessible summary and a data table', async ({ page }) => {
    const frames = page.locator('#charts .chart-frame');
    const n = await frames.count();
    expect(n).toBeGreaterThanOrEqual(12);
    for (const frame of await frames.all()) {
      const labelled = frame.locator('[role="img"][aria-label], [role="group"][aria-label]').first();
      const empty = await frame.locator('.chart-empty').count();
      if (empty) continue;
      expect((await labelled.getAttribute('aria-label'))?.length ?? 0).toBeGreaterThan(10);
      await expect(frame.locator('details.chart-data summary')).toHaveText('View chart data');
    }
  });
});

test('charts hold their final size while recharts loads (no layout shift)', async ({ page }) => {
  // Hold back the chunk that contains recharts, so the skeletons stay on screen.
  let release!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/_next/static/chunks/*.js', async (route) => {
    const response = await route.fetch();
    const body = await response.text();
    if (body.includes('recharts-wrapper')) await held;
    await route.fulfill({ response, body });
  });
  const ids = ['#chart-dual', '#chart-treasury', '#chart-donut', '#chart-inout'];
  await page.goto('/design-system', { waitUntil: 'domcontentloaded' });
  const before: { top: number; height: number }[] = [];
  for (const id of ids) {
    await expect(page.locator(`${id} .chart-plot .skeleton-chart[aria-busy="true"]`)).toHaveCount(1);
    const b = await box(page.locator(`${id} .chart-plot`));
    before.push({ top: b.y, height: b.height });
  }
  const below = await box(page.locator('#chart-candles'));
  release();
  for (const [i, id] of ids.entries()) {
    await expect(page.locator(`${id} .recharts-surface`).first()).toBeVisible();
    const plot = await box(page.locator(`${id} .chart-plot`));
    expect(plot.height).toBeCloseTo(before[i]!.height, 0);
    expect(plot.y).toBeCloseTo(before[i]!.top, 0);
    // The loaded chart (legend, axis titles, plot) fits the reserved box exactly.
    expect((await box(page.locator(`${id} .chart-body`))).height).toBeLessThanOrEqual(before[i]!.height + 1);
  }
  expect((await box(page.locator('#chart-candles'))).y).toBeCloseTo(below.y, 0);
});

test('routes without charts never download recharts', async ({ page }) => {
  const scripts: string[] = [];
  page.on('response', async (res) => {
    if (res.request().resourceType() === 'script') scripts.push(await res.text().catch(() => ''));
  });
  await visit(page, '/accounts');
  await page.waitForLoadState('networkidle');
  expect(scripts.length).toBeGreaterThan(0);
  expect(scripts.filter((s) => s.includes('recharts-wrapper'))).toEqual([]);
});
