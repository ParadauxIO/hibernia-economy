'use client';
// A small anchored popover for table chips and menus, built on the native HTML
// `popover` attribute (top layer, light dismiss, Escape) with our own placement.
// On phones it becomes a bottom sheet.
//
// The props mirror the shared accessible Popover being built for the design
// system (PAR-533): `open` / `onOpenChange` / `anchorRef` / `label`. When that
// lands, swap this file's body for it and keep the call sites.

import { useCallback, useEffect, useLayoutEffect, useRef, type ReactNode, type RefObject } from 'react';

export interface TablePopoverProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Element the popover sits under; focus returns to it on close. */
  anchorRef: RefObject<HTMLElement | null>;
  /** Accessible name of the dialog. */
  label: string;
  children: ReactNode;
  className?: string;
  /** Changes when the body is swapped (menu → editor) so focus and placement follow. */
  contentKey?: string;
  /** Width hint in px for placement before the content has been measured. */
  width?: number;
}

const SHEET_QUERY = '(max-width: 720px)';
const GAP = 6;
const MARGIN = 8;

type PopoverElement = HTMLDivElement;

/** The popover API is missing in older browsers and in jsdom. */
function supportsPopover(el: PopoverElement): boolean {
  return 'showPopover' in el && 'hidePopover' in el;
}

const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect;

const FOCUSABLE = 'input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])';

export function TablePopover({ open, onOpenChange, anchorRef, label, children, className, contentKey = '', width = 288 }: TablePopoverProps) {
  const ref = useRef<PopoverElement>(null);
  const onOpenChangeRef = useRef(onOpenChange);
  useIsoLayoutEffect(() => { onOpenChangeRef.current = onOpenChange; });

  const place = useCallback(() => {
    const el = ref.current;
    const anchor = anchorRef.current;
    if (!el || !anchor || typeof window === 'undefined') return;
    const sheet = typeof window.matchMedia === 'function' && window.matchMedia(SHEET_QUERY).matches;
    el.classList.toggle('is-sheet', sheet);
    if (sheet) {
      el.style.removeProperty('top');
      el.style.removeProperty('left');
      return;
    }
    const a = anchor.getBoundingClientRect();
    const w = el.offsetWidth || width;
    const h = el.offsetHeight;
    const left = Math.min(Math.max(MARGIN, a.left), window.innerWidth - w - MARGIN);
    const below = a.bottom + GAP;
    const top = h && below + h > window.innerHeight - MARGIN && a.top - GAP - h > MARGIN ? a.top - GAP - h : below;
    el.style.left = `${Math.round(left)}px`;
    el.style.top = `${Math.round(top)}px`;
  }, [anchorRef, width]);

  // Open / close the native popover to match `open`, and move focus in and back out.
  useIsoLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const native = supportsPopover(el);
    // Without the API the attribute only hides the element; the class drives it instead.
    if (!native) el.removeAttribute('popover');
    if (open) {
      if (native && !el.matches(':popover-open')) el.showPopover();
      place();
      const first = el.querySelector<HTMLElement>('[data-autofocus]') ?? el.querySelector<HTMLElement>(FOCUSABLE);
      first?.focus({ preventScroll: true });
    } else if (native && el.matches(':popover-open')) {
      el.hidePopover();
    }
  }, [open, place, contentKey]);

  // Native light dismiss (outside click, Escape) closes the popover without us:
  // report it back so the owner's state follows.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const onToggle = (e: Event) => {
      const next = (e as Event & { newState?: string }).newState;
      if (next === 'closed') {
        onOpenChangeRef.current(false);
        anchorRef.current?.focus({ preventScroll: true });
      }
    };
    el.addEventListener('toggle', onToggle);
    return () => el.removeEventListener('toggle', onToggle);
  }, [anchorRef]);

  // Keep it attached to its anchor while the page moves. Also the fallback
  // outside-click dismissal where the popover API is missing (older browsers, jsdom).
  useEffect(() => {
    if (!open) return;
    const onMove = () => place();
    const onDown = (e: PointerEvent) => {
      const el = ref.current;
      if (!el || supportsPopover(el)) return;
      const target = e.target as Node | null;
      if (target && !el.contains(target) && !anchorRef.current?.contains(target)) onOpenChangeRef.current(false);
    };
    window.addEventListener('resize', onMove);
    window.addEventListener('scroll', onMove, true);
    document.addEventListener('pointerdown', onDown);
    return () => {
      window.removeEventListener('resize', onMove);
      window.removeEventListener('scroll', onMove, true);
      document.removeEventListener('pointerdown', onDown);
    };
  }, [open, place, anchorRef]);

  return (
    <div
      ref={ref}
      popover="auto"
      role="dialog"
      aria-label={label}
      className={`dt-popover${open ? ' is-open' : ''}${className ? ` ${className}` : ''}`}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          e.stopPropagation();
          onOpenChange(false);
          anchorRef.current?.focus({ preventScroll: true });
        }
      }}
    >
      {open ? children : null}
    </div>
  );
}
