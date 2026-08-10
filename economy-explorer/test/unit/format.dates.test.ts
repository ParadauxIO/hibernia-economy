// Pin the timezone so toLocaleDateString/toLocaleString are deterministic
// regardless of the host TZ. Node reads process.env.TZ per Date call on Linux,
// so setting it before any Date construction below is sufficient.
process.env.TZ = 'UTC';

import { describe, it, expect } from 'vitest';
import { fmtDate, fmtTs } from '@/lib/format';

describe('fmtDate', () => {
  it('treats a bare date (length 10) as UTC midnight (the T00:00:00Z branch)', () => {
    // Under TZ=UTC this is May 1; the point of appending T00:00:00Z is that it is
    // parsed as UTC, not local, so the day does not roll backwards.
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
