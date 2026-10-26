import { EmptyState, When } from '@democracycraft/design-system';
import type { RangeCoverage } from '@/lib/services/rollup';

const fmtDay = (day: string) => new Date(`${day}T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });

/** "Updated 14:05" for a rollup-backed section (rollups lag by up to about five minutes). */
export function Updated({ coverage }: { coverage: RangeCoverage }) {
  if (!coverage.updatedAt) return null;
  return <span className="me-updated">Updated <When value={coverage.updatedAt} time relative={false} /></span>;
}

/**
 * Shown instead of a rollup-backed section while history is still being
 * prepared (newest first after a deploy). Never a slow live query instead.
 */
export function Preparing({ coverage, what = 'this period' }: { coverage: RangeCoverage; what?: string }) {
  return <EmptyState compact title="Preparing history">
    {coverage.coveredFrom
      ? <>Covered back to {fmtDay(coverage.coveredFrom)} so far; {what} needs more. This fills in on its own within a few hours of a release.</>
      : <>History for {what} has not been prepared yet. This fills in on its own within a few hours of a release.</>}
  </EmptyState>;
}

/** A note under a partly covered section. */
export function PartlyCovered({ coverage }: { coverage: RangeCoverage }) {
  if (coverage.covered || !coverage.coveredFrom) return null;
  return <p className="me-note">Preparing history: covered back to {fmtDay(coverage.coveredFrom)}. Earlier figures appear as they are prepared.</p>;
}
