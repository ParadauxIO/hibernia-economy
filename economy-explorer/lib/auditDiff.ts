// Before/after values on explorer_audit rows (spec §12). Pure and client-safe:
// lib/audit.ts serialises with it, and the /admin/audit detail dialog diffs
// with it.

/** Each JSON column is capped so one oversized payload can't bloat the table. */
export const AUDIT_JSON_MAX = 16 * 1024;

/** A value recorded before or after a mutation: plain JSON. */
export type AuditValue = null | boolean | number | string | AuditValue[] | { [key: string]: AuditValue };

/** JSON for a before/after column, or null. Oversized values keep a valid, truncated preview. */
export function serializeAuditValue(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  // JSON.stringify returns undefined for a bare function or symbol, whatever its typing says.
  let text: string | undefined;
  try {
    text = JSON.stringify(value, (_key, v: unknown) => {
      if (typeof v === 'bigint') return v.toString();
      if (v instanceof Date) return v.toISOString();
      return v;
    }) as string | undefined;
  } catch {
    return JSON.stringify({ unserializable: true });
  }
  if (text === undefined) return null;
  if (text.length <= AUDIT_JSON_MAX) return text;
  return JSON.stringify({ truncated: true, preview: text.slice(0, AUDIT_JSON_MAX - 64) });
}

/** Parse a stored column. Anything unparseable becomes the raw text, never an error. */
export function parseAuditValue(text: string | null): AuditValue {
  if (text === null) return null;
  try {
    return JSON.parse(text) as AuditValue;
  } catch {
    return text;
  }
}

export interface AuditDiffRow {
  /** Dotted path into the value ("owner.uuid"); "" for a scalar value. */
  key: string;
  before: AuditValue | undefined;
  after: AuditValue | undefined;
  /** 'added' and 'removed' compare against a missing key, not against null. */
  change: 'added' | 'removed' | 'changed' | 'same';
}

function isRecord(v: AuditValue | undefined): v is { [key: string]: AuditValue } {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function same(a: AuditValue | undefined, b: AuditValue | undefined): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/**
 * Field-by-field comparison of two recorded values. Objects are walked by key
 * (keys sorted, nested objects flattened to dotted paths); arrays and scalars
 * compare whole, so a role's permission list shows as one changed row.
 */
export function diffAuditValues(before: AuditValue, after: AuditValue): AuditDiffRow[] {
  const rows: AuditDiffRow[] = [];
  const walk = (prefix: string, b: AuditValue | undefined, a: AuditValue | undefined) => {
    if (isRecord(b) || isRecord(a)) {
      const bo = isRecord(b) ? b : {};
      const ao = isRecord(a) ? a : {};
      // A value that changed shape (object ↔ scalar) is one row.
      if ((b !== undefined && b !== null && !isRecord(b)) || (a !== undefined && a !== null && !isRecord(a))) {
        rows.push({ key: prefix, before: b, after: a, change: 'changed' });
        return;
      }
      const keys = [...new Set([...Object.keys(bo), ...Object.keys(ao)])].sort();
      for (const k of keys) {
        const path = prefix ? `${prefix}.${k}` : k;
        const inB = isRecord(b) && Object.hasOwn(bo, k);
        const inA = isRecord(a) && Object.hasOwn(ao, k);
        if (isRecord(bo[k]) || isRecord(ao[k])) {
          walk(path, inB ? bo[k] : undefined, inA ? ao[k] : undefined);
          continue;
        }
        rows.push({
          key: path,
          before: inB ? bo[k] : undefined,
          after: inA ? ao[k] : undefined,
          change: !inB ? 'added' : !inA ? 'removed' : same(bo[k], ao[k]) ? 'same' : 'changed',
        });
      }
      return;
    }
    const change = b === undefined || b === null ? (a === undefined || a === null ? 'same' : 'added')
      : a === undefined || a === null ? 'removed'
      : same(b, a) ? 'same' : 'changed';
    rows.push({ key: prefix, before: b, after: a, change });
  };
  walk('', before, after);
  return rows;
}

/** Short display text for one recorded value. */
export function formatAuditValue(v: AuditValue | undefined): string {
  if (v === undefined) return '—';
  if (v === null) return 'null';
  if (typeof v === 'string') return v === '' ? '""' : v;
  return JSON.stringify(v);
}
