import { describe, it, expect } from 'vitest';
import { parseDaysWindow, MAX_DAYS_WINDOW } from '@/lib/util/daysWindow';

// `?days=N` on /chestshop/firms/<id> — the in-game `/firm sales export` deep link.
describe('parseDaysWindow', () => {
  it('returns null (all-time) when the param is absent', () => {
    expect(parseDaysWindow(undefined)).toBeNull();
  });

  it('accepts a plain positive integer', () => {
    expect(parseDaysWindow('1')).toBe(1);
    expect(parseDaysWindow('30')).toBe(30);
    expect(parseDaysWindow(' 7 ')).toBe(7);
    expect(parseDaysWindow('007')).toBe(7);
  });

  it('clamps an oversized window instead of rejecting it', () => {
    expect(parseDaysWindow(String(MAX_DAYS_WINDOW))).toBe(MAX_DAYS_WINDOW);
    expect(parseDaysWindow(String(MAX_DAYS_WINDOW + 1))).toBe(MAX_DAYS_WINDOW);
    expect(parseDaysWindow('999999999')).toBe(MAX_DAYS_WINDOW);
  });

  it('treats anything that is not a positive integer as all-time', () => {
    for (const bad of ['', '0', '-5', '+5', '1.5', '1e3', '0x10', '30d', 'abc', 'NaN', 'Infinity', '9999999999', "1; DROP TABLE firm"]) {
      expect(parseDaysWindow(bad), bad).toBeNull();
    }
  });
});
