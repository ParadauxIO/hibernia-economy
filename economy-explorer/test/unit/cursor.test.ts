import { describe, it, expect } from 'vitest';
import { decodeCursor, encodeCursor, InvalidCursorError } from '@/lib/pagination/cursor';

const at = new Date('2026-10-26T12:34:56.789Z');

describe('keyset cursor', () => {
  it('round-trips (timestamp, id) at millisecond precision', () => {
    const token = encodeCursor({ at, id: 9_300_123 });
    expect(decodeCursor(token)).toEqual({ at, id: 9_300_123 });
  });

  it('is opaque, URL-safe base64url', () => {
    const token = encodeCursor({ at, id: 42 });
    expect(token).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(token).not.toContain('42');
    expect(encodeURIComponent(token)).toBe(token);
  });

  it('round-trips the edges of the valid range', () => {
    for (const c of [
      { at: new Date(0), id: 0 },
      { at: new Date(8.64e15), id: Number.MAX_SAFE_INTEGER },
    ]) {
      expect(decodeCursor(encodeCursor(c))).toEqual(c);
    }
  });

  it('orders distinct rows with the same timestamp by id', () => {
    const a = decodeCursor(encodeCursor({ at, id: 1 }))!;
    const b = decodeCursor(encodeCursor({ at, id: 2 }))!;
    expect(a.at.getTime()).toBe(b.at.getTime());
    expect(a.id).not.toBe(b.id);
  });

  it.each([
    ['an invalid date', { at: new Date(NaN), id: 1 }],
    ['a negative timestamp', { at: new Date(-1), id: 1 }],
    ['a fractional id', { at, id: 1.5 }],
    ['a negative id', { at, id: -1 }],
    ['an unsafe id', { at, id: 2 ** 53 }],
  ])('refuses to encode %s', (_label, cursor) => {
    expect(() => encodeCursor(cursor)).toThrow(InvalidCursorError);
  });

  const b64 = (s: string) => btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

  it.each([
    ['nothing', undefined],
    ['null', null],
    ['an empty string', ''],
    ['non-base64url characters', 'abc$%^'],
    ['standard base64 padding', `${encodeCursor({ at, id: 1 })}==`],
    ['an overlong token', 'A'.repeat(200)],
    ['garbage that decodes', b64('hello world')],
    ['an unknown version', b64(`v2.${at.getTime()}.1`)],
    ['missing parts', b64(`v1.${at.getTime()}`)],
    ['extra parts', b64(`v1.${at.getTime()}.1.2`)],
    ['a signed id', b64(`v1.${at.getTime()}.-1`)],
    ['a leading-zero id', b64(`v1.${at.getTime()}.01`)],
    ['an exponent', b64('v1.1e12.1')],
    ['a timestamp past the Date range', b64('v1.8640000000000001.1')],
    ['an id past 2^53', b64(`v1.${at.getTime()}.9007199254740993`)],
  ])('decodes %s to null', (_label, token) => {
    expect(decodeCursor(token as string | null | undefined)).toBeNull();
  });
});
