export type Placement = 'bottom-start' | 'bottom-end' | 'bottom' | 'top-start' | 'top-end' | 'top';

export interface Rect { top: number; left: number; bottom: number; right: number; width: number; height: number }

const GAP = 6;
const MARGIN = 8;

/**
 * Where to put a floating panel next to its anchor, in viewport (position: fixed)
 * coordinates. Flips to the other side when the preferred side doesn't fit and
 * the other side has more room, and keeps the panel inside the viewport.
 */
export function computePosition(anchor: Rect, panel: { width: number; height: number }, placement: Placement, viewport: { width: number; height: number }): { top: number; left: number; side: 'top' | 'bottom' } {
  const [preferred, align = 'center'] = placement.split('-') as ['top' | 'bottom', 'start' | 'end' | undefined];
  const below = viewport.height - anchor.bottom - GAP - MARGIN;
  const above = anchor.top - GAP - MARGIN;
  let side: 'top' | 'bottom' = preferred;
  if (preferred === 'bottom' && panel.height > below && above > below) side = 'top';
  if (preferred === 'top' && panel.height > above && below > above) side = 'bottom';
  const top = side === 'bottom' ? anchor.bottom + GAP : anchor.top - GAP - panel.height;
  let left = align === 'start' ? anchor.left : align === 'end' ? anchor.right - panel.width : anchor.left + anchor.width / 2 - panel.width / 2;
  left = Math.max(MARGIN, Math.min(left, viewport.width - panel.width - MARGIN));
  return { top: Math.max(MARGIN, top), left, side };
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function focusables(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => !el.hasAttribute('hidden') && el.getAttribute('aria-hidden') !== 'true');
}
