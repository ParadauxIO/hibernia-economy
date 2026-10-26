import './economy.css';
import type { ReactNode } from 'react';
import { ErrorState, Section, SkeletonChart, SkeletonTable } from '@democracycraft/design-system';
import { formatUtc } from '@democracycraft/design-system/format';
import type { RangeCoverage } from '@/lib/services/rollup';

// Shared pieces of the economy pages (PAR-541): the rollup coverage line, a
// per-section error boundary and the sized skeletons each <Suspense> shows.

const day = (d: string) => new Date(`${d}T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
const hhmm = (iso: string) => `${iso.slice(11, 16)} UTC`;

/**
 * "Updated 14:05 UTC" when the whole range is ready, otherwise what the
 * rollups cover so far. `snapshots` is for wealth history, which starts on
 * the worker's first run and is never reconstructed.
 */
export function CoverageNote({ coverage, snapshots = false }: { coverage: RangeCoverage; snapshots?: boolean }) {
  if (!coverage.coveredFrom) {
    return <p className="coverage-note" data-state="preparing" role="status">
      Preparing history. The explorer is building its daily summaries; the first figures appear within a few minutes.
    </p>;
  }
  const updated = coverage.updatedAt
    ? <> · Updated <time dateTime={coverage.updatedAt} title={formatUtc(coverage.updatedAt)}>{hhmm(coverage.updatedAt)}</time></>
    : null;
  if (!coverage.covered) {
    return <p className="coverage-note" data-state="preparing" role="status">
      {snapshots
        ? <>Snapshots start on {day(coverage.coveredFrom)}, when recording began. Earlier days are not reconstructed.</>
        : <>Preparing history: covered back to {day(coverage.coveredFrom)}. Totals include only the covered days.</>}
      {updated}
    </p>;
  }
  return <p className="coverage-note">{updated ? <>Updated <time dateTime={coverage.updatedAt!} title={formatUtc(coverage.updatedAt!)}>{hhmm(coverage.updatedAt!)}</time></> : 'Up to date'}</p>;
}

/**
 * Run a section's data load; a failure renders an inline error for that
 * section instead of failing the page, and is logged for the operator.
 */
export async function attempt<T>(label: string, load: () => Promise<T>): Promise<{ ok: true; value: T } | { ok: false }> {
  try {
    return { ok: true, value: await load() };
  } catch (error) {
    console.error(`[economy] ${label} failed`, error);
    return { ok: false };
  }
}

export function SectionError({ title }: { title: ReactNode }) {
  return <Section title={title}>
    <ErrorState title="This part of the page didn’t load">Try again in a moment. The rest of the page is unaffected.</ErrorState>
  </Section>;
}

/** A section placeholder at the loaded section's size: heading, a note line and the chart or table. */
export function SectionSkeleton({ title, chartHeight, rows }: { title: ReactNode; chartHeight?: number; rows?: number }) {
  return <Section title={title} aria-busy="true">
    {chartHeight !== undefined ? <SkeletonChart height={chartHeight} label={`Loading ${typeof title === 'string' ? title.toLowerCase() : 'chart'}`} /> : <SkeletonTable rows={rows ?? 5} cols={4} />}
  </Section>;
}
