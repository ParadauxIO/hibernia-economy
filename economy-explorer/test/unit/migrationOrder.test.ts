import { describe, it, expect } from 'vitest';
import { basename } from 'node:path';
import { migrationFiles } from '../integration/db';

// The integration + e2e DB is built by applying these in order, so a lexical
// sort (V10 before V2) would build a different schema than Flyway does.
describe('Flyway migration order used by the test DB', () => {
  it('applies every migration in numeric version order', () => {
    const versions = migrationFiles().map((f) => Number(/^V(\d+)__/.exec(basename(f))?.[1]));
    expect(versions[0]).toBe(1);
    expect(versions).toEqual([...versions].sort((a, b) => a - b));
    expect(new Set(versions).size).toBe(versions.length);
    expect(versions.indexOf(10)).toBeGreaterThan(versions.indexOf(9));
  });
});
