// Opaque keyset cursors (spec §4.3) for unbounded feeds ordered by
// `(timestamp DESC, id DESC)`: ledger `(settlement_time, txn_id)`, sales
// `(occurred_at, sale_id)`, fines `(issued_at, fine_id)`.
//
// The token is base64url text, so it is URL-safe as a `?cursor=` value without
// escaping. Callers must treat it as opaque: the payload is versioned so the
// encoding can change without breaking links in flight, and decode returns null
// for anything it does not recognise so a tampered or stale URL falls back to the
// first page instead of erroring.
//
// Timestamps are carried at millisecond precision, which matches the JS Date the
// DB driver gives us; the ledger columns are TIMESTAMP (seconds) or TIMESTAMP(3).
//
// No `server-only`: links are built on both sides.

/** A position in a `(timestamp, id)` ordered list: the last row of the current page. */
export interface KeysetCursor {
  at: Date;
  id: number;
}

const VERSION = 'v1';
// Generous: "v1.<13 digit ms>.<16 digit id>" is ~32 chars, ~44 as base64url.
const MAX_TOKEN_LENGTH = 96;
const BASE64URL_RE = /^[A-Za-z0-9_-]+$/;
const INT_RE = /^(0|[1-9]\d*)$/;
// The ECMAScript Date range.
const MAX_DATE_MS = 8.64e15;

// The payload is ASCII, so btoa/atob (Node and browsers alike) are enough.
function toBase64Url(text: string): string {
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function fromBase64Url(token: string): string | null {
  try {
    return atob(token.replace(/-/g, '+').replace(/_/g, '/'));
  } catch {
    return null;
  }
}

export class InvalidCursorError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'InvalidCursorError';
  }
}

function assertValid(cursor: KeysetCursor): void {
  const ms = cursor.at instanceof Date ? cursor.at.getTime() : NaN;
  if (!Number.isSafeInteger(ms) || ms < 0 || ms > MAX_DATE_MS) throw new InvalidCursorError('cursor timestamp is not a valid date');
  if (!Number.isSafeInteger(cursor.id) || cursor.id < 0) throw new InvalidCursorError('cursor id must be a non-negative safe integer');
}

/** Encode a cursor. Throws {@link InvalidCursorError} for an invalid date or id. */
export function encodeCursor(cursor: KeysetCursor): string {
  assertValid(cursor);
  return toBase64Url(`${VERSION}.${cursor.at.getTime()}.${cursor.id}`);
}

/** Decode a token from a URL. `null` for a missing, malformed, tampered or unknown-version token. */
export function decodeCursor(token: string | null | undefined): KeysetCursor | null {
  if (typeof token !== 'string' || token.length === 0 || token.length > MAX_TOKEN_LENGTH) return null;
  if (!BASE64URL_RE.test(token)) return null;
  const text = fromBase64Url(token);
  // Re-encoding must reproduce the token exactly; rejects non-canonical padding bits.
  if (text === null || toBase64Url(text) !== token) return null;
  const parts = text.split('.');
  if (parts.length !== 3 || parts[0] !== VERSION) return null;
  const [, msText = '', idText = ''] = parts;
  if (!INT_RE.test(msText) || !INT_RE.test(idText)) return null;
  const cursor = { at: new Date(Number(msText)), id: Number(idText) };
  try {
    assertValid(cursor);
  } catch {
    return null;
  }
  return cursor;
}

