'use client';

import {
  useCallback, useEffect, useId, useLayoutEffect, useRef, useState,
  type CSSProperties, type ReactElement, type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { computePosition, type Placement } from './position';

export interface HoverCardProps {
  /** The element that opens the card on hover or keyboard focus, normally a link. */
  trigger: ReactElement;
  /** Supplementary content. Everything in it must also be reachable on the target page. */
  children: ReactNode;
  /** Called the first time the card opens, e.g. to start loading its data. */
  onFirstOpen?: (() => void) | undefined;
  placement?: Placement | undefined;
  openDelay?: number | undefined;
  closeDelay?: number | undefined;
}

/**
 * Supplementary detail on hover or focus (WCAG 1.4.13): it can be hovered
 * without closing, Escape dismisses it, and it stays until the pointer or focus
 * leaves. It never takes focus, so it must not contain the only route to anything.
 */
export function HoverCard({ trigger, children, onFirstOpen, placement = 'bottom-start', openDelay = 350, closeDelay = 150 }: HoverCardProps) {
  const [open, setOpen] = useState(false);
  const opened = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const anchorRef = useRef<HTMLSpanElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [style, setStyle] = useState<CSSProperties>({ visibility: 'hidden' });
  const id = useId();

  const schedule = useCallback((next: boolean, delay: number) => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      if (next && !opened.current) { opened.current = true; onFirstOpen?.(); }
      setOpen(next);
    }, delay);
  }, [onFirstOpen]);

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  useLayoutEffect(() => {
    const anchor = anchorRef.current;
    const panel = panelRef.current;
    if (!open || !anchor || !panel) return;
    const target = anchor.firstElementChild ?? anchor;
    // Describe the trigger itself (not the wrapper) while the card is open.
    target.setAttribute('aria-describedby', id);
    const pos = computePosition(target.getBoundingClientRect(), { width: panel.offsetWidth, height: panel.offsetHeight }, placement, { width: window.innerWidth, height: window.innerHeight });
    setStyle({ top: pos.top, left: pos.left });
    return () => target.removeAttribute('aria-describedby');
  }, [open, placement, children, id]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    const onScroll = () => setOpen(false);
    window.addEventListener('keydown', onKey);
    window.addEventListener('scroll', onScroll, { capture: true, passive: true });
    return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('scroll', onScroll, true); };
  }, [open]);

  // A thin inline wrapper listens for hover and (bubbling) focus, so the trigger
  // keeps its own props and refs untouched.
  return <>
    <span ref={anchorRef} className="hover-card-anchor"
      onPointerEnter={() => schedule(true, openDelay)} onPointerLeave={() => schedule(false, closeDelay)}
      onFocus={() => schedule(true, 0)} onBlur={() => schedule(false, 0)}>
      {trigger}
    </span>
    {open && typeof document !== 'undefined' && createPortal(
      <div ref={panelRef} id={id} role="tooltip" className="popover-panel hover-card" style={style}
        onPointerEnter={() => schedule(true, 0)} onPointerLeave={() => schedule(false, closeDelay)}>
        {children}
      </div>,
      document.body,
    )}
  </>;
}
