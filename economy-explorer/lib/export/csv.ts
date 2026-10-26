// CSV cell encoding shared by the client "this page" buttons (CsvButton) and the
// streaming full-export route (app/api/export). Client-safe: no server imports.

export type CsvCell = string | number | bigint | null | undefined;

/** A plain decimal number ("-3.50", "1200"): spreadsheets read it as a number, never a formula. */
const PLAIN_NUMBER_RE = /^-?\d+(\.\d+)?$/;
/** A leading character Excel, Sheets or LibreOffice would treat as the start of a formula. */
const FORMULA_START_RE = /^[=+\-@\t\r]/;

/**
 * One CSV field. Neutralises formula injection: exported fields include
 * attacker-controllable Minecraft strings (display names, transaction messages,
 * item and player names), so a cell starting with `=`, `+`, `-`, `@`, tab or CR
 * is prefixed with `'` to force it to a literal. Plain numbers, including
 * negative amounts, are left alone so they stay numeric in a spreadsheet. Then
 * RFC 4180 quoting for commas, quotes and line breaks.
 */
export function escapeCsvCell(value: CsvCell): string {
  if (value === null || value === undefined) return '';
  let s = String(value);
  if (FORMULA_START_RE.test(s) && !PLAIN_NUMBER_RE.test(s)) s = `'${s}`;
  if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

/** One CSV line, without the line terminator. */
export function csvLine(cells: readonly CsvCell[]): string {
  return cells.map(escapeCsvCell).join(',');
}
