'use client';

import { MenuGroup, MenuItem, Popover } from './popover/Popover';

export interface ExportHrefs {
  /** Exports of exactly the rows on screen. */
  page?: { csv?: string; json?: string } | undefined;
  /** Exports of every result the current filters select (a streaming route). */
  all?: { csv?: string; json?: string } | undefined;
}

export interface ExportMenuProps {
  /** URLs only: the host builds them and authorizes the download route. */
  hrefs: ExportHrefs;
  label?: string | undefined;
  /** Size hint under "All results", e.g. "1,204 rows". */
  allDescription?: string | undefined;
}

/** CSV / JSON for this page or all results. UI only; renders nothing without hrefs. */
export function ExportMenu({ hrefs, label = 'Export', allDescription }: ExportMenuProps) {
  const page = hrefs.page ?? {};
  const all = hrefs.all ?? {};
  if (!page.csv && !page.json && !all.csv && !all.json) return null;
  return <Popover role="menu" label={label} placement="bottom-end" trigger={<button type="button" className="btn btn-quiet export-trigger">
    <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M8 2.5v8M4.5 7 8 10.5 11.5 7M3 13.5h10" /></svg>
    {label}
  </button>}>
    {(page.csv || page.json) && <MenuGroup label="This page">
      {page.csv && <MenuItem href={page.csv} download>CSV</MenuItem>}
      {page.json && <MenuItem href={page.json} download>JSON</MenuItem>}
    </MenuGroup>}
    {(all.csv || all.json) && <MenuGroup label="All results">
      {all.csv && <MenuItem href={all.csv} download description={allDescription}>CSV</MenuItem>}
      {all.json && <MenuItem href={all.json} download description={allDescription}>JSON</MenuItem>}
    </MenuGroup>}
  </Popover>;
}
