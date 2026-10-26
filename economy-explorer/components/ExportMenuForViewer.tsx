'use client';
// The design system's ExportMenu, shown only to signed-in viewers: the export
// route needs a viewer to authorize, rate-limit and audit the download.

import { ExportMenu, type ExportHrefs } from '@democracycraft/design-system';
import { useIsLoggedIn } from '@/components/ViewerContext';

export function ExportMenuForViewer({ hrefs, allDescription }: { hrefs: ExportHrefs; allDescription?: string }) {
  const loggedIn = useIsLoggedIn();
  if (!loggedIn) return null;
  return <ExportMenu hrefs={hrefs} allDescription={allDescription} />;
}
