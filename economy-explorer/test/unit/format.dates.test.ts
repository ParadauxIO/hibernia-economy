import { describe, it, expect, afterEach } from 'vitest';
import { fmtDate, fmtTs } from '@/lib/format';

const originalTZ = process.env.TZ;
afterEach(() => {
  if (originalTZ === undefined) delete process.env.TZ;
  else process.env.TZ = originalTZ;
});

describe('fmtDate', () => {
  it('formats a bare date as its UTC bucket date', () => {
    expect(fmtDate('2026-05-01')).toBe('May 1');
  });

  it('parses a full ISO timestamp as-is (non-10-length branch)', () => {
    expect(fmtDate('2026-05-01T10:00:00Z')).toBe('May 1');
    expect(fmtDate('2026-12-25T23:00:00Z')).toBe('Dec 25');
  });
});

describe('fmtTs', () => {
  it('returns an em dash for null', () => {
    expect(fmtTs(null)).toBe('—');
  });

  it('formats an ISO string with month/day/time', () => {
    expect(fmtTs('2026-05-01T10:00:00Z')).toBe('May 1, 10:00 AM');
  });

  it('formats a Date object identically to its ISO string', () => {
    expect(fmtTs(new Date('2026-05-01T10:00:00Z'))).toBe('May 1, 10:00 AM');
  });

  it('pads minutes to two digits', () => {
    expect(fmtTs('2026-05-01T13:05:00Z')).toBe('May 1, 1:05 PM');
  });
});


describe('timezone boundaries', () => {
  it.each(['America/Los_Angeles', 'Pacific/Auckland'])('keeps UTC chart dates and fallback timestamps on a %s host', (zone) => {
    process.env.TZ = zone;
    expect(fmtDate('2026-05-01')).toBe('May 1');
    expect(fmtTs('2026-05-01T00:30:00Z')).toBe('May 1, 12:30 AM');
  });

  it('moves an instant into the previous or next local calendar day', () => {
    expect(fmtTs('2026-05-01T00:30:00Z', 'America/Los_Angeles')).toBe('Apr 30, 5:30 PM');
    expect(fmtTs('2026-05-01T23:30:00Z', 'Pacific/Auckland')).toBe('May 2, 11:30 AM');
  });

  it('uses the offset at the instant, including the spring DST jump', () => {
    expect(fmtTs('2026-03-08T06:30:00Z', 'America/New_York')).toBe('Mar 8, 1:30 AM');
    expect(fmtTs('2026-03-08T07:30:00Z', 'America/New_York')).toBe('Mar 8, 3:30 AM');
  });

  it('supports fractional-hour offsets and Date objects', () => {
    expect(fmtTs(new Date('2026-05-01T00:00:00Z'), 'Asia/Kathmandu')).toBe('May 1, 5:45 AM');
  });
});
