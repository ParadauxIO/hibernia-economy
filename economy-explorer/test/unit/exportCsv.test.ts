import { describe, expect, it } from 'vitest';
import { csvLine, escapeCsvCell } from '@/lib/export/csv';

describe('escapeCsvCell', () => {
  it.each([
    ['=HYPERLINK("http://evil")', `"'=HYPERLINK(""http://evil"")"`],
    ['+cmd', "'+cmd"],
    ['-2+3', "'-2+3"],
    ['@SUM(A1)', "'@SUM(A1)"],
    ['\tTab', "'\tTab"],
    ['\rReturn', `"'\rReturn"`],
    ['safe=value', 'safe=value'],
  ])('neutralises formula injection in %j', (cell, expected) => {
    expect(escapeCsvCell(cell)).toBe(expected);
  });

  it('leaves plain numbers numeric, including negative amounts', () => {
    expect(escapeCsvCell('-3.50')).toBe('-3.50');
    expect(escapeCsvCell(-12)).toBe('-12');
    expect(escapeCsvCell('1200')).toBe('1200');
    expect(escapeCsvCell(10n)).toBe('10');
  });

  it('quotes commas, quotes and line breaks, and writes nothing for null', () => {
    expect(escapeCsvCell('Acme, Inc.')).toBe('"Acme, Inc."');
    expect(escapeCsvCell('He said "hi"')).toBe('"He said ""hi"""');
    expect(escapeCsvCell('a\nb')).toBe('"a\nb"');
    expect(escapeCsvCell(null)).toBe('');
    expect(escapeCsvCell(undefined)).toBe('');
  });

  it('joins a line', () => {
    expect(csvLine([1, 'Acme, Inc.', null, '=1'])).toBe(`1,"Acme, Inc.",,'=1`);
  });
});
