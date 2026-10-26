import { describe, expect, it } from 'vitest';
import { AUDIT_JSON_MAX, diffAuditValues, formatAuditValue, parseAuditValue, serializeAuditValue } from '@/lib/auditDiff';

describe('serializeAuditValue', () => {
  it('stores null and undefined as SQL NULL', () => {
    expect(serializeAuditValue(null)).toBeNull();
    expect(serializeAuditValue(undefined)).toBeNull();
  });

  it('writes dates as ISO strings and bigints (cents) as exact decimal strings', () => {
    const text = serializeAuditValue({ at: new Date('2026-10-26T12:00:00Z'), cents: 12345678901234567890n });
    expect(JSON.parse(text!)).toEqual({ at: '2026-10-26T12:00:00.000Z', cents: '12345678901234567890' });
  });

  it('caps oversized values but keeps the column valid JSON', () => {
    const text = serializeAuditValue({ blob: 'x'.repeat(AUDIT_JSON_MAX * 2) })!;
    expect(text.length).toBeLessThanOrEqual(AUDIT_JSON_MAX);
    expect(JSON.parse(text)).toMatchObject({ truncated: true });
  });

  it('never throws on a value JSON cannot represent', () => {
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    expect(JSON.parse(serializeAuditValue(cyclic)!)).toEqual({ unserializable: true });
    expect(serializeAuditValue(() => 1)).toBeNull();
  });
});

describe('parseAuditValue', () => {
  it('round-trips JSON and degrades unparseable text to the raw string', () => {
    expect(parseAuditValue('{"a":1}')).toEqual({ a: 1 });
    expect(parseAuditValue(null)).toBeNull();
    expect(parseAuditValue('not json')).toBe('not json');
  });
});

describe('diffAuditValues', () => {
  it('reports changed, unchanged, added and removed fields, sorted by key', () => {
    const rows = diffAuditValues(
      { displayName: 'Old', archived: false, ownerUuid: 'a', gone: 1 },
      { displayName: 'New', archived: false, ownerUuid: 'a', added: true },
    );
    expect(rows).toEqual([
      { key: 'added', before: undefined, after: true, change: 'added' },
      { key: 'archived', before: false, after: false, change: 'same' },
      { key: 'displayName', before: 'Old', after: 'New', change: 'changed' },
      { key: 'gone', before: 1, after: undefined, change: 'removed' },
      { key: 'ownerUuid', before: 'a', after: 'a', change: 'same' },
    ]);
  });

  it('treats a create (no before) and a delete (no after) as all-added and all-removed', () => {
    expect(diffAuditValues(null, { name: 'G' })).toEqual([{ key: 'name', before: undefined, after: 'G', change: 'added' }]);
    expect(diffAuditValues({ name: 'G' }, null)).toEqual([{ key: 'name', before: 'G', after: undefined, change: 'removed' }]);
  });

  it('flattens nested objects to dotted paths but compares arrays whole', () => {
    const rows = diffAuditValues(
      { syncSources: { business: '7' }, roles: ['a', 'b'] },
      { syncSources: { business: '8', realty: 'w/r' }, roles: ['a', 'b'] },
    );
    expect(rows).toEqual([
      { key: 'roles', before: ['a', 'b'], after: ['a', 'b'], change: 'same' },
      { key: 'syncSources.business', before: '7', after: '8', change: 'changed' },
      { key: 'syncSources.realty', before: undefined, after: 'w/r', change: 'added' },
    ]);
    expect(diffAuditValues({ roles: ['a'] }, { roles: ['a', 'b'] })[0]?.change).toBe('changed');
  });

  it('keeps a null field distinct from a missing one', () => {
    expect(diffAuditValues({ hq: null }, { hq: 'North' })).toEqual([{ key: 'hq', before: null, after: 'North', change: 'changed' }]);
  });

  it('diffs scalars and shape changes as one row', () => {
    expect(diffAuditValues('a', 'b')).toEqual([{ key: '', before: 'a', after: 'b', change: 'changed' }]);
    expect(diffAuditValues({ a: 1 }, 'text')).toEqual([{ key: '', before: { a: 1 }, after: 'text', change: 'changed' }]);
    expect(diffAuditValues(null, null)).toEqual([{ key: '', before: null, after: null, change: 'same' }]);
  });
});

describe('formatAuditValue', () => {
  it('shows missing, null, empty and structured values distinctly', () => {
    expect(formatAuditValue(undefined)).toBe('—');
    expect(formatAuditValue(null)).toBe('null');
    expect(formatAuditValue('')).toBe('""');
    expect(formatAuditValue(['a'])).toBe('["a"]');
    expect(formatAuditValue(false)).toBe('false');
  });
});
