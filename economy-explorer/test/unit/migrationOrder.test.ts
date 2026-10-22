import { describe, it, expect } from 'vitest';
import { basename } from 'node:path';
import { migrationFiles, migrationSql } from '../integration/db';

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

describe('migration SQL sent to MariaDB', () => {
  it('preserves SQL without client directives, including compound bodies', () => {
    const sql = "CREATE PROCEDURE p() BEGIN SELECT 'a;b'; SELECT 2; END;\nCALL p();\n";
    expect(migrationSql(sql)).toBe(sql);
  });

  it('translates custom terminators without splitting bodies or changing literals', () => {
    const sql = [
      'DELIMITER $$',
      '-- keep this comment $$',
      'CREATE PROCEDURE p()',
      'BEGIN',
      "  SELECT 'a;b', '$$';",
      'END$$',
      'CALL p()$$',
      'DELIMITER ;',
      'DROP PROCEDURE p;',
    ].join('\n');
    expect(migrationSql(sql)).toBe([
      '',
      '-- keep this comment $$',
      'CREATE PROCEDURE p()',
      'BEGIN',
      "  SELECT 'a;b', '$$';",
      'END;',
      'CALL p();',
      '',
      'DROP PROCEDURE p;',
    ].join('\n'));
  });
});
