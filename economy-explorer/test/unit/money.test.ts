import { describe, it, expect } from 'vitest';
import {
  abs, add, compare, divRound, formatMoney, MoneyParseError, negate, parseMoney, ratio, sign, sub, sum, sumBy,
  toDbString, toNumber, tryParseMoney, ZERO,
} from '@/lib/money';

describe('parseMoney', () => {
  it.each([
    ['0', 0n], ['0.00', 0n], ['-0.00', 0n], ['1', 100n], ['1.5', 150n], ['1234.56', 123456n],
    ['-1234.56', -123456n], ['+7.01', 701n], ['-0.50', -50n], ['-1.50', -150n], ['000012.30', 1230n], [' 3.20 ', 320n],
  ])('parses %j', (input, cents) => {
    expect(parseMoney(input)).toBe(cents);
  });

  it('rounds extra fraction digits half away from zero', () => {
    expect(parseMoney('0.004')).toBe(0n);
    expect(parseMoney('0.005')).toBe(1n);
    expect(parseMoney('-0.005')).toBe(-1n);
    expect(parseMoney('-0.0049999')).toBe(0n);
    expect(parseMoney('2.999')).toBe(300n);
    expect(parseMoney('-2.999')).toBe(-300n);
    expect(parseMoney('12.345678')).toBe(1235n); // an AVG() over DECIMAL(19,2)
  });

  it('stays exact beyond 2^53 cents', () => {
    const big = '123456789012345678901234.56';
    expect(parseMoney(big)).toBe(12345678901234567890123456n);
    expect(toDbString(parseMoney(big))).toBe(big);
    expect(parseMoney('-90071992547409.93')).toBe(-9007199254740993n); // 2^53 + 1, which a double cannot hold
  });

  it.each(['', ' ', 'abc', '1e5', '1,000.00', '1.2.3', '--1', '+-1', '.5', '5.', 'NaN', 'Infinity', '$5', '0x10', '1 000'])(
    'rejects malformed %j',
    (input) => {
      expect(() => parseMoney(input)).toThrow(MoneyParseError);
      expect(tryParseMoney(input)).toBeNull();
    },
  );

  it('rejects non-strings at runtime and treats nullish as absent in tryParseMoney', () => {
    expect(() => parseMoney(12 as unknown as string)).toThrow(MoneyParseError);
    expect(() => parseMoney(null as unknown as string)).toThrow(MoneyParseError);
    expect(tryParseMoney(null)).toBeNull();
    expect(tryParseMoney(undefined)).toBeNull();
  });
});

describe('toDbString', () => {
  it.each([[0n, '0.00'], [5n, '0.05'], [-5n, '-0.05'], [-50n, '-0.50'], [100n, '1.00'], [-123450n, '-1234.50']])('%s → %s', (c, s) => {
    expect(toDbString(c)).toBe(s);
    expect(parseMoney(s)).toBe(c);
  });
});

describe('arithmetic', () => {
  it('adds, subtracts, negates and takes magnitudes exactly', () => {
    expect(add(10n, 20n)).toBe(30n);
    expect(sub(10n, 25n)).toBe(-15n);
    expect(negate(-7n)).toBe(7n);
    expect(abs(-7n)).toBe(7n);
    expect(abs(7n)).toBe(7n);
    expect(sign(-1n)).toBe(-1);
    expect(sign(ZERO)).toBe(0);
    expect(sign(3n)).toBe(1);
  });

  it('compares for sorting', () => {
    expect([30n, -5n, 10n].sort(compare)).toEqual([-5n, 10n, 30n]);
    expect(compare(1n, 1n)).toBe(0);
  });

  it('sums decimal strings without double drift', () => {
    // 0.1 + 0.2 + 0.3 is 0.6000000000000001 in a double.
    expect([0.1, 0.2, 0.3].reduce((s, x) => s + x, 0)).not.toBe(0.6);
    expect(toDbString(sum(['0.10', '0.20', '0.30']))).toBe('0.60');
    expect(sum([])).toBe(0n);
    expect(sum(['1.00', 250n, '-0.75'])).toBe(275n);
    expect(sumBy([{ a: '1.10' }, { a: '2.20' }], (r) => r.a)).toBe(330n);
  });

  it('sums past 2^53 cents exactly', () => {
    const rows = Array.from({ length: 1000 }, () => '9007199254740.99');
    expect(sum(rows)).toBe(900719925474099000n);
  });

  it('a malformed string in a sum throws instead of turning into NaN', () => {
    expect(() => sum(['1.00', 'oops'])).toThrow(MoneyParseError);
  });

  it('divides with half-away-from-zero rounding', () => {
    expect(divRound(10n, 3)).toBe(3n);
    expect(divRound(5n, 2)).toBe(3n);
    expect(divRound(-5n, 2)).toBe(-3n);
    expect(divRound(5n, -2)).toBe(-3n);
    expect(divRound(-4n, 3)).toBe(-1n);
    expect(divRound(0n, 7n)).toBe(0n);
    expect(() => divRound(1n, 0)).toThrow(RangeError);
  });

  it('ratio and toNumber are display helpers', () => {
    expect(ratio(25n, 100n)).toBe(0.25);
    expect(ratio(1n, 3n)).toBeCloseTo(1 / 3, 9);
    expect(ratio(-50n, 200n)).toBe(-0.25);
    expect(ratio(1n, 0n)).toBeNull();
    expect(toNumber(-123456n)).toBe(-1234.56);
  });
});

describe('formatMoney', () => {
  it('formats in full with grouping, a real minus and the currency symbol', () => {
    expect(formatMoney(123456789n)).toBe('$1,234,567.89');
    expect(formatMoney(-4200n)).toBe('−$42.00');
    expect(formatMoney(0n)).toBe('$0.00');
    expect(formatMoney(5n)).toBe('$0.05');
    expect(formatMoney('-1234.5')).toBe('−$1,234.50');
    expect(formatMoney('12345678901234567890123.45')).toBe('$12,345,678,901,234,567,890,123.45');
  });

  it('formats compact with K/M/B/T and promotes rounding at unit boundaries', () => {
    expect(formatMoney(95000n, { compact: true })).toBe('$950.00');
    expect(formatMoney(150000n, { compact: true })).toBe('$1.5K');
    expect(formatMoney(1201000000n, { compact: true })).toBe('$12.01M');
    expect(formatMoney(250000000000n, { compact: true })).toBe('$2.50B');
    expect(formatMoney(-150000n, { compact: true })).toBe('−$1.5K');
    expect(formatMoney(99999999n, { compact: true })).toBe('$1.00M'); // 999,999.99 is not "1000.0K"
    expect(formatMoney(99999n, { compact: true })).toBe('$999.99');
    expect(formatMoney(99_999_999_999_999n, { compact: true })).toBe('$1.00T');
    expect(formatMoney(10n ** 20n, { compact: true })).toBe('$1,000,000.00T');
  });

  it('supports sign modes', () => {
    expect(formatMoney(100n, { sign: 'always' })).toBe('+$1.00');
    expect(formatMoney(0n, { sign: 'always' })).toBe('+$0.00');
    expect(formatMoney(0n, { sign: 'exceptZero' })).toBe('$0.00');
    expect(formatMoney(100n, { sign: 'exceptZero' })).toBe('+$1.00');
    expect(formatMoney(-100n, { sign: 'always' })).toBe('−$1.00');
    expect(formatMoney(-100n, { sign: 'never' })).toBe('$1.00');
  });

  it('can omit or override the currency', () => {
    expect(formatMoney(-150n, { currency: false })).toBe('−1.50');
    expect(formatMoney(150n, { currency: '£' })).toBe('£1.50');
  });

  it('shows an em dash for malformed strings', () => {
    expect(formatMoney('abc')).toBe('—');
  });
});
