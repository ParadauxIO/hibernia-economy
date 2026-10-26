import { describe, expect, it } from 'vitest';
import {
  analysePayroll, balanceAnomalies, classifyFlows, governmentRedirect, isRoundAmount, selectUnit, unassignedIds,
  validateUnitInput, visibleUnits, type FlowGroup, type PayoutBucket,
} from '@/lib/services/governmentModel';

const unit = (unit_id: number, slug: string, parent_unit_id: number | null = null) => ({ unit_id, slug, name: slug[0]!.toUpperCase() + slug.slice(1), parent_unit_id, description: null });

describe('units', () => {
  const units = [unit(1, 'nation'), unit(2, 'commerce', 1), unit(3, 'interior', 1), unit(4, 'city')];
  const members = [
    { unit_id: 1, account_id: 10 }, { unit_id: 2, account_id: 20 }, { unit_id: 2, account_id: 21 },
    { unit_id: 3, account_id: 30 }, { unit_id: 4, account_id: 40 },
  ];

  it('rolls descendants into a parent and orders the tree depth first', () => {
    const views = visibleUnits(units, members, new Set([10, 20, 21, 30, 40]));
    expect(views.map((u) => [u.slug, u.depth, u.readableIds.length, u.partial])).toEqual([
      ['city', 0, 1, false], ['nation', 0, 4, false], ['commerce', 1, 2, false], ['interior', 1, 1, false],
    ]);
  });

  it('hides units with nothing readable and marks partial ones with counts only', () => {
    const views = visibleUnits(units, members, new Set([20, 99]));
    expect(views.map((u) => u.slug)).toEqual(['nation', 'commerce']);
    expect(views[0]).toMatchObject({ readableIds: [20], totalAccounts: 4, partial: true });
    expect(views[1]).toMatchObject({ readableIds: [20], totalAccounts: 2, partial: true });
  });

  it('survives a parent cycle and a dangling parent', () => {
    const cyclic = [unit(1, 'a', 2), unit(2, 'b', 1), unit(3, 'c', 77)];
    const views = visibleUnits(cyclic, [{ unit_id: 3, account_id: 5 }], new Set([5]));
    expect(views.map((u) => u.slug)).toEqual(['c']);
  });

  it('lists in-scope accounts outside every unit as unassigned', () => {
    expect(unassignedIds([10, 50, 20], members)).toEqual([50]);
  });

  it('selects within the scope only, falling back to all units', () => {
    const views = visibleUnits(units, members, new Set([20, 50]));
    expect(selectUnit('commerce', [20, 50], views, [50])).toMatchObject({ key: 'commerce', ids: [20] });
    expect(selectUnit('interior', [20, 50], views, [50])).toMatchObject({ key: 'all', ids: [20, 50] });
    expect(selectUnit('unassigned', [20, 50], views, [50])).toMatchObject({ key: 'unassigned', ids: [50] });
    expect(selectUnit(undefined, [], [], [])).toMatchObject({ key: 'all', ids: [] });
  });

  it('validates unit forms: slug shape, reserved words, parents and cycles', () => {
    const ok = validateUnitInput({ slug: ' Trade ', name: ' Trade  office ', parentUnitId: 2, description: '  ' }, null, units);
    expect(ok).toEqual({ ok: true, value: { slug: 'trade', name: 'Trade office', parentUnitId: 2, description: null } });
    expect(validateUnitInput({ slug: 'all', name: 'X', parentUnitId: null, description: null }, null, units).ok).toBe(false);
    expect(validateUnitInput({ slug: 'bad slug', name: 'X', parentUnitId: null, description: null }, null, units).ok).toBe(false);
    expect(validateUnitInput({ slug: 'x', name: '', parentUnitId: null, description: null }, null, units).ok).toBe(false);
    expect(validateUnitInput({ slug: 'x', name: 'X', parentUnitId: 99, description: null }, null, units).ok).toBe(false);
    // nation (1) under commerce (2, its own child) would be a cycle.
    expect(validateUnitInput({ slug: 'nation', name: 'Nation', parentUnitId: 2, description: null }, 1, units)).toMatchObject({ ok: false });
    expect(validateUnitInput({ slug: 'nation', name: 'Nation', parentUnitId: 1, description: null }, 1, units)).toMatchObject({ ok: false });
  });
});

describe('classifyFlows', () => {
  const g = (over: Partial<FlowGroup>): FlowGroup => ({ account_id: 1, plugin_key: '', tax_kind: '', counterparty_type: 'PERSONAL', is_internal: 0, inflow: '0.00', outflow: '0.00', ...over });

  it('splits taxes by kind, fines and categories, and keeps government transfers apart', () => {
    const b = classifyFlows([
      g({ plugin_key: 'ChestShop', tax_kind: 'sales', inflow: '10.10' }),
      g({ plugin_key: 'treasury', tax_kind: 'personal', inflow: '0.20' }),
      g({ plugin_key: 'treasury', tax_kind: 'fine', inflow: '50.00', account_id: 2 }),
      g({ plugin_key: 'treasury-salary', outflow: '60.00' }),
      g({ plugin_key: 'Treasury-Core', counterparty_type: 'GOVERNMENT', outflow: '30.00', inflow: '5.00' }),
      g({ plugin_key: 'treasury', counterparty_type: 'GOVERNMENT', is_internal: 1, outflow: '1.00' }),
      g({ plugin_key: 'ChestShop', counterparty_type: 'BUSINESS', outflow: '2.50' }),
    ], (id) => (id === 2 ? 'justice' : 'commerce'));
    // Taxes first (largest kind first), then fines, then other categories.
    expect(b.revenue.map((l) => [l.key, l.amount])).toEqual([['tax:sales', '10.10'], ['tax:personal', '0.20'], ['fines', '50.00']]);
    expect(b.revenue[2]!.byUnit).toEqual({ justice: '50.00' });
    expect(b.spending.map((l) => [l.label, l.amount])).toEqual([['Wages & salary', '60.00'], ['Shop purchases', '2.50']]);
    expect(b.totalRevenue).toBe('60.30');
    expect(b.taxes).toBe('10.30');
    expect(b.fines).toBe('50.00');
    expect(b.totalSpending).toBe('62.50');
    expect(b.governmentIn).toBe('5.00');
    expect(b.governmentOut).toBe('31.00');
    expect(b.recipients).toEqual([
      { type: 'PERSONAL', label: 'Players', amount: '60.00' },
      { type: 'BUSINESS', label: 'Firms', amount: '2.50' },
      { type: 'GOVERNMENT', label: 'Other government accounts', amount: '31.00' },
    ]);
  });

  it('adds exactly, never through a double', () => {
    const b = classifyFlows([0.1, 0.2, 0.3].map((v) => g({ plugin_key: 'rest-api', inflow: v.toFixed(2) })), () => 'x');
    expect(b.totalRevenue).toBe('0.60');
  });
});

describe('analysePayroll', () => {
  const at = (minute: number) => new Date(Date.UTC(2026, 9, 25, 0, minute)).toISOString();
  const run = (minute: number, payments = 40, total = '400.00'): PayoutBucket => ({ at: at(minute), payments, recipients: payments, total });

  it('groups minute buckets into runs and finds the cadence', () => {
    const a = analysePayroll([run(0), run(1, 5, '50.00'), run(15), run(30), run(45), run(60)]);
    expect(a.runs).toHaveLength(5);
    expect(a.runs[0]).toMatchObject({ payments: 45, total: '450.00', start: at(0), end: at(1), gapMinutes: null, status: 'ok' });
    expect(a.cadenceMinutes).toBe(15);
    expect(a.missed).toBe(0);
    expect(a.duplicates).toBe(0);
  });

  it('counts the runs a long gap skipped', () => {
    const a = analysePayroll([run(0), run(15), run(30), run(75), run(90), run(105)]);
    const missed = a.runs.find((r) => r.status === 'missed');
    expect(missed).toMatchObject({ start: at(75), gapMinutes: 45, missedBefore: 2 });
    expect(a.missed).toBe(2);
  });

  it('flags a run that starts too soon as a possible duplicate', () => {
    const a = analysePayroll([run(0), run(15), run(30), run(35), run(45), run(60)]);
    expect(a.runs.find((r) => r.start === at(35))?.status).toBe('duplicate');
    expect(a.duplicates).toBe(1);
  });

  it('calls a slightly long gap late, not missed', () => {
    const a = analysePayroll([run(0), run(15), run(30), run(50), run(65), run(80)]);
    expect(a.runs.find((r) => r.start === at(50))?.status).toBe('late');
    expect(a.missed).toBe(0);
  });

  it('gives no verdicts without enough runs to know the cadence', () => {
    const a = analysePayroll([run(0), run(100)]);
    expect(a.cadenceMinutes).toBeNull();
    expect(a.runs.every((r) => r.status === 'ok')).toBe(true);
    expect(analysePayroll([]).runs).toEqual([]);
  });

  it('sorts unordered input and sums totals exactly', () => {
    const a = analysePayroll([run(30, 1, '0.30'), run(0, 1, '0.10'), run(15, 1, '0.20'), run(16, 1, '0.20')]);
    expect(a.runs.map((r) => r.total)).toEqual(['0.10', '0.40', '0.30']);
  });
});

describe('compliance signals', () => {
  it('detects round amounts at or above one unit', () => {
    expect(isRoundAmount('5000.00')).toBe(true);
    expect(isRoundAmount('-2000.00')).toBe(true);
    expect(isRoundAmount('999.00')).toBe(false);
    expect(isRoundAmount('1000.01')).toBe(false);
    expect(isRoundAmount('500.00', 50_000n)).toBe(true);
  });

  it('flags days far from the baseline, above a floor', () => {
    const baseline = Array.from({ length: 90 }, (_, i) => ({ day: `b${i}`, net: i % 2 ? '100.00' : '-100.00' }));
    const recent = [{ day: 'r1', net: '150.00' }, { day: 'r2', net: '25000.00' }, { day: 'r3', net: '-900.00' }];
    const flagged = balanceAnomalies(baseline, recent);
    expect(flagged.map((a) => a.day)).toEqual(['r2']);
    expect(flagged[0]!.score).toBeGreaterThan(3);
    expect(balanceAnomalies(baseline.slice(0, 5), recent)).toEqual([]);
  });

  it('treats any large move on a flat account as an anomaly', () => {
    const flat = Array.from({ length: 30 }, (_, i) => ({ day: `b${i}`, net: '0.00' }));
    expect(balanceAnomalies(flat, [{ day: 'r', net: '5000.00' }])).toMatchObject([{ day: 'r', score: 99 }]);
  });
});

describe('governmentRedirect', () => {
  it('maps the old dashboard and fines views onto /gov', () => {
    expect(governmentRedirect(new URLSearchParams())).toBe('/gov');
    expect(governmentRedirect(new URLSearchParams('days=7'))).toBe('/gov?range=7d');
    expect(governmentRedirect(new URLSearchParams('view=fines&days=30&q=speeding&status=revoked&page=3')))
      .toBe('/gov/fines?range=30d&q=speeding&f=status%3Ain%3Arevoked');
    expect(governmentRedirect(new URLSearchParams('view=accounts&status=active&unit=commerce'))).toBe('/gov?unit=commerce');
    expect(governmentRedirect(new URLSearchParams('days=13&status=bogus&view=fines'))).toBe('/gov/fines');
  });
});
