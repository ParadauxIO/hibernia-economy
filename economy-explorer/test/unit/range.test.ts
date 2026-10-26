import { describe, expect, it } from 'vitest';
import { isDay, parseRange, presetRange, previousRange, rangeDays, rangeLabel, rangeToParams } from '@/lib/range';

const now = new Date('2026-10-26T15:30:00Z');

describe('parseRange', () => {
  it('defaults to the last 30 days, inclusive of today', () => {
    const r = parseRange({}, { now });
    expect(r.preset).toBe('30d');
    expect(r.range).toEqual({ from: '2026-09-27', to: '2026-10-26' });
    expect(rangeDays(r.range)).toBe(30);
    expect(r.compare).toBe(false);
    expect(r.previous).toBeNull();
    expect(r.label).toBe('Last 30 days');
  });

  it.each([
    ['7d', '2026-10-20', 7],
    ['90d', '2026-07-29', 90],
    ['1y', '2025-10-27', 365],
    ['ytd', '2026-01-01', 299],
    ['all', '2020-01-01', 2491],
  ] as const)('preset %s starts %s and spans %i days', (preset, from, days) => {
    const r = parseRange(new URLSearchParams({ range: preset }), { now });
    expect(r.range).toEqual({ from, to: '2026-10-26' });
    expect(rangeDays(r.range)).toBe(days);
  });

  it('gives 24h as yesterday and today plus the exact instant', () => {
    const r = parseRange({ range: '24h' }, { now });
    expect(r.range).toEqual({ from: '2026-10-25', to: '2026-10-26' });
    expect(r.since?.toISOString()).toBe('2026-10-25T15:30:00.000Z');
  });

  it('accepts a custom range, swapping reversed ends and clamping to today', () => {
    expect(parseRange({ range: 'custom', from: '2026-09-30', to: '2026-09-01' }, { now }).range).toEqual({ from: '2026-09-01', to: '2026-09-30' });
    expect(parseRange({ range: 'custom', from: '2026-10-01', to: '2027-01-01' }, { now }).range).toEqual({ from: '2026-10-01', to: '2026-10-26' });
    expect(parseRange({ range: 'custom', from: '2001-01-01', to: '2026-01-05' }, { now, earliest: '2024-03-01' }).range.from).toBe('2024-03-01');
  });

  it.each([
    [{ range: 'custom', from: '2026-02-31', to: '2026-03-01' }],
    [{ range: 'custom', from: 'yesterday', to: '2026-03-01' }],
    [{ range: 'custom' }],
    [{ range: 'forever' }],
    [{ range: ['7d', '30d'] }],
    [{ range: 'custom', from: '2030-01-01', to: '2030-02-01' }],
  ])('never throws on malformed input %j and falls back sensibly', (params) => {
    const r = parseRange(params as Record<string, string | string[]>, { now });
    expect(isDay(r.range.from) && isDay(r.range.to)).toBe(true);
    expect(r.range.from <= r.range.to).toBe(true);
  });

  it('maps the legacy days param', () => {
    expect(parseRange({ days: '7' }, { now }).preset).toBe('7d');
    expect(parseRange({ days: '365' }, { now }).preset).toBe('1y');
    expect(parseRange({ days: '12' }, { now }).preset).toBe('30d');
    expect(parseRange({ days: '7', range: '90d' }, { now }).preset).toBe('90d');
  });

  it('computes the previous period when cmp=1', () => {
    const r = parseRange({ range: '7d', cmp: '1' }, { now });
    expect(r.previous).toEqual({ from: '2026-10-13', to: '2026-10-19' });
    expect(r.value).toEqual({ preset: '7d', from: '2026-10-20', to: '2026-10-26', compare: true });
  });
});

describe('range helpers', () => {
  it('previousRange is the same length, immediately before', () => {
    expect(previousRange({ from: '2026-03-01', to: '2026-03-31' })).toEqual({ from: '2026-01-29', to: '2026-02-28' });
    expect(previousRange({ from: '2026-01-01', to: '2026-01-01' })).toEqual({ from: '2025-12-31', to: '2025-12-31' });
  });

  it('presetRange crosses a leap day', () => {
    expect(presetRange('7d', new Date('2028-03-02T00:00:00Z'))).toEqual({ from: '2028-02-25', to: '2028-03-02' });
  });

  it('labels custom ranges with the year once', () => {
    expect(rangeLabel('custom', { from: '2026-09-01', to: '2026-09-30' })).toBe('Sep 1 – Sep 30, 2026');
    expect(rangeLabel('custom', { from: '2025-12-20', to: '2026-01-05' })).toBe('Dec 20, 2025 – Jan 5, 2026');
  });

  it('round-trips through URL params', () => {
    const r = parseRange({ range: 'custom', from: '2026-09-01', to: '2026-09-30', cmp: '1' }, { now });
    expect(parseRange(rangeToParams(r), { now }).value).toEqual(r.value);
  });
});
