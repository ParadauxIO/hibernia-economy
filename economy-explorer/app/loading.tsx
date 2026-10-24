import { SkeletonLine, SkeletonTable } from '@democracycraft/design-system';

// Root route-loading boundary. Shown immediately on navigation while the target
// page's server component streams — so a click paints a skeleton at once instead
// of freezing the old page. Generic by design (heading + table) since most pages
// are data tables; it's also what the header nav prefetches as the static shell.
export default function Loading() {
  return (
    <div aria-busy="true" aria-label="Loading">
      <div className="page-heading">
        <SkeletonLine w={200} h={26} />
      </div>
      <SkeletonTable rows={10} cols={5} />
    </div>
  );
}
