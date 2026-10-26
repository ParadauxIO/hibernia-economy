// CSV for the entity pages' page-level exports. Values are written as given
// (money stays a DECIMAL string); cells that a spreadsheet would run as a
// formula are prefixed with a quote.

function cell(value: unknown): string {
  let s = value === null || value === undefined ? '' : String(value);
  if (/^[=+\-@\t\r]/.test(s) && !/^-?\d+(\.\d+)?$/.test(s)) s = `'${s}`;
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** RFC 4180 CSV with a header row from the first object's keys. */
export function toCsv(rows: readonly Record<string, unknown>[], columns?: readonly string[]): string {
  const keys = columns ?? (rows[0] ? Object.keys(rows[0]) : []);
  const lines = [keys.map(cell).join(','), ...rows.map((r) => keys.map((k) => cell(r[k])).join(','))];
  return `${lines.join('\r\n')}\r\n`;
}
