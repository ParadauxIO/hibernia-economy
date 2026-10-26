/**
 * @deprecated The section nav now lives in the chrome (components/HeaderNav
 * HeaderSectionNav), driven by the path through lib/nav. Pages must not render
 * section tabs. Kept as a no-op only so branches that still import it compile
 * until they rebase; delete in PAR-543.
 */
export function SectionTabs(): null {
  return null;
}
