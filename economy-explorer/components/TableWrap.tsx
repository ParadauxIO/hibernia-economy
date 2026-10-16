import type { CSSProperties, ReactNode } from 'react';

/**
 * Horizontally scrollable table container. On narrow screens the table overflows,
 * so the wrapper is a named, focusable region: keyboard users can tab to it and
 * scroll with the arrow keys (WCAG 2.1.1; axe scrollable-region-focusable).
 */
export function TableWrap({ label, style, children }: { label: string; style?: CSSProperties; children: ReactNode }) {
  return (
    <div className="table-wrap" role="region" aria-label={label} tabIndex={0} style={style}>
      {children}
    </div>
  );
}
