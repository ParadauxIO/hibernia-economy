import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { migrationFiles } from '../integration/db';
import { migrationSql } from '../integration/migrationSql';

describe('mysql-client migration delimiters', () => {
  it('leaves existing delimiter-free migrations byte-identical', () => {
    for (const file of migrationFiles()) {
      const sql = readFileSync(file, 'utf8');
      if (!/^DELIMITER /m.test(sql)) expect(migrationSql(sql)).toBe(sql);
    }
  });

  it('translates V42 while preserving routine-internal semicolons', () => {
    const sql = readFileSync(migrationFiles().find((file) => /V42__/.test(file))!, 'utf8');
    const converted = migrationSql(sql);
    expect(converted).not.toMatch(/^DELIMITER /m);
    expect(converted).not.toContain('$$');
    expect(converted).toContain('END;');
    expect(converted).toContain("SET NEW.system_plugin_key=NEW.display_name;");
    expect(converted).toContain('CALL system_plugin_key_preflight();');
  });

  it('preserves delimiters inside SQL literals, identifiers and comments', () => {
    const sql = "DELIMITER $$\nSELECT '$$', \"$$\", `$$`, 'escaped\\\'$$', 'doubled''$$'; -- $$\n/* $$\nDELIMITER fake\n*/\nSELECT 1$$\nDELIMITER ;\nSELECT 2;";
    expect(migrationSql(sql)).toBe("\nSELECT '$$', \"$$\", `$$`, 'escaped\\\'$$', 'doubled''$$'; -- $$\n/* $$\nDELIMITER fake\n*/\nSELECT 1;\n\nSELECT 2;");
  });
});
