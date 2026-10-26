// UTC calendar-day arithmetic on 'YYYY-MM-DD' strings. Rollups are keyed by UTC
// day, and the worker's sessions run with time_zone = '+00:00', so a day string
// D covers settlement times [D 00:00:00, D+1 00:00:00).

const DAY_MS = 86_400_000;
const DAY_RE = /^\d{4}-\d{2}-\d{2}$/;

/** An inclusive range of UTC days, e.g. `{ from: '2026-10-01', to: '2026-10-31' }`. */
export interface DayRange {
  from: string;
  to: string;
}

export function isDay(value: string): boolean {
  if (!DAY_RE.test(value)) return false;
  const t = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(t) && new Date(t).toISOString().slice(0, 10) === value;
}

function parse(day: string): number {
  if (!isDay(day)) throw new RangeError(`not a YYYY-MM-DD day: ${day}`);
  return Date.parse(`${day}T00:00:00Z`);
}

export function utcDay(at: Date = new Date()): string {
  return at.toISOString().slice(0, 10);
}

export function addDays(day: string, n: number): string {
  return new Date(parse(day) + n * DAY_MS).toISOString().slice(0, 10);
}

/** Whole days from `a` to `b` (positive when b is later). */
export function daysBetween(a: string, b: string): number {
  return Math.round((parse(b) - parse(a)) / DAY_MS);
}

export function monthStart(day: string): string {
  parse(day);
  return `${day.slice(0, 7)}-01`;
}

export function monthEnd(day: string): string {
  const d = new Date(parse(monthStart(day)));
  d.setUTCMonth(d.getUTCMonth() + 1);
  return addDays(d.toISOString().slice(0, 10), -1);
}

/** Every day of the range, oldest first. Empty when from > to. */
export function eachDay(range: DayRange): string[] {
  const out: string[] = [];
  for (let d = range.from; d <= range.to; d = addDays(d, 1)) out.push(d);
  return out;
}

/** The SQL TIMESTAMP literal at the start of a UTC day. */
export function dayStart(day: string): string {
  parse(day);
  return `${day} 00:00:00`;
}

export function assertRange(range: DayRange): void {
  parse(range.from);
  parse(range.to);
  if (range.from > range.to) throw new RangeError(`empty day range ${range.from}..${range.to}`);
}
