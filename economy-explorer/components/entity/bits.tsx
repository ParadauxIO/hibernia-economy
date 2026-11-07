// Small server-rendered pieces shared by the entity pages (PAR-537).
import { formatUtc } from '@democracycraft/design-system';
import type { RangeCoverage } from '@/lib/services/rollup';

const DAY_FMT = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
const TIME_FMT = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' });

/** "Mar 4, 2024", always with the year (spec §1.4 item 7). UTC; exact instant in the tooltip. */
export function DateWithYear({ value }: { value: Date | string | null | undefined }) {
  if (!value) return <span className="is-empty">—</span>;
  const d = typeof value === 'string' ? new Date(value) : value;
  if (!Number.isFinite(d.getTime())) return <span className="is-empty">—</span>;
  return <time dateTime={d.toISOString()} title={formatUtc(d)}>{DAY_FMT.format(d)}</time>;
}

/** "09:12 UTC". */
export function TimeUtc({ value }: { value: Date | string }) {
  const d = typeof value === 'string' ? new Date(value) : value;
  return <time dateTime={d.toISOString()} title={formatUtc(d)}>{TIME_FMT.format(d)} UTC</time>;
}

export function formatDay(day: string): string {
  return DAY_FMT.format(new Date(`${day}T00:00:00Z`));
}

/**
 * Freshness and backfill state of a rollup-backed section: "Updated 09:12 UTC",
 * or "Preparing history: covered back to Mar 4, 2026" while the worker is
 * still backfilling the range. Never a fallback to live queries (§4.2).
 */
export function CoverageNote({ coverage }: { coverage: RangeCoverage }) {
  if (!coverage.coveredFrom) {
    return <p className="coverage-note" data-state="partial">Preparing history: not computed yet. It appears here within a few minutes.</p>;
  }
  const updated = coverage.updatedAt ? <>Updated <TimeUtc value={coverage.updatedAt} /></> : null;
  if (!coverage.covered) {
    return <p className="coverage-note" data-state="partial">Preparing history: covered back to {formatDay(coverage.coveredFrom)}{updated && <> · {updated}</>}</p>;
  }
  return updated ? <p className="coverage-note">{updated}</p> : null;
}

