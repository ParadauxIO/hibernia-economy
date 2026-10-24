/** Translate mysql-client delimiters without changing stored-program bodies.
 * mysql2's multi-statement protocol lets MariaDB parse routine-internal `;`s.
 */
export function migrationSql(source: string): string {
  let delimiter = ';';
  let quote = '';
  let blockComment = false;
  return source.split('\n').map((line) => {
    const directive = !quote && !blockComment && /^\s*DELIMITER\s+(\S+)\s*$/i.exec(line);
    if (directive && directive[1]) {
      delimiter = directive[1];
      return '';
    }
    let result = '';
    for (let i = 0; i < line.length;) {
      const ch = line[i];
      const next = line[i + 1];
      if (blockComment) {
        if (ch === '*' && next === '/') {
          result += '*/'; i += 2; blockComment = false;
        } else { result += ch; i++; }
      } else if (quote) {
        result += ch; i++;
        if (ch === '\\' && i < line.length) { result += line[i++]; }
        else if (ch === quote) {
          if (line[i] === quote) { result += line[i++]; }
          else quote = '';
        }
      } else if (ch === '#' || (ch === '-' && next === '-' && /\s/.test(line[i + 2] ?? ' '))) {
        result += line.slice(i); break;
      } else if (ch === '/' && next === '*') {
        result += '/*'; i += 2; blockComment = true;
      } else if (ch === "'" || ch === '"' || ch === '`') {
        quote = ch; result += ch; i++;
      } else if (delimiter !== ';' && line.startsWith(delimiter, i)) {
        result += ';'; i += delimiter.length;
      } else { result += ch; i++; }
    }
    return result;
  }).join('\n');
}
