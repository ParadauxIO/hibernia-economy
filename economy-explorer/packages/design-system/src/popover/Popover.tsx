'use client';

import {
  cloneElement, isValidElement, useCallback, useEffect, useId, useLayoutEffect, useRef, useState,
  type CSSProperties, type FocusEvent, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent as ReactMouseEvent, type ReactElement, type ReactNode, type Ref,
} from 'react';
import { createPortal } from 'react-dom';
import { cx } from '../cx';
import { computePosition, focusables, type Placement } from './position';

export interface PopoverApi { close: () => void }

/** Props injected into the trigger element. Spread them if you render the trigger yourself. */
export interface TriggerProps {
  ref: Ref<HTMLElement>;
  'aria-expanded': boolean;
  'aria-haspopup': 'dialog' | 'menu' | 'listbox';
  'aria-controls'?: string | undefined;
  onClick: (event: ReactMouseEvent<HTMLElement>) => void;
  onKeyDown: (event: ReactKeyboardEvent<HTMLElement>) => void;
}

export interface PopoverProps {
  /** A single focusable element (normally a <button>). It receives ref, ARIA and click handling. */
  trigger: ReactElement;
  /** Panel content, or a function receiving `{ close }`. */
  children: ReactNode | ((api: PopoverApi) => ReactNode);
  /** Accessible name of the panel. Required for role="dialog" unless the content has a heading you label it with. */
  label?: string | undefined;
  /** dialog (default): a small non-modal form, e.g. a filter editor. menu: a list of actions (use MenuItem). */
  role?: 'dialog' | 'menu' | 'listbox' | undefined;
  placement?: Placement | undefined;
  open?: boolean | undefined;
  defaultOpen?: boolean | undefined;
  onOpenChange?: ((open: boolean) => void) | undefined;
  /** Open as a bottom sheet at ≤ 720 px (filter editors on phones). */
  sheet?: boolean | undefined;
  /** Where focus goes once the panel is visible. first (default): the first menu item, else a `[data-autofocus]` control, else the first focusable element; panel: the panel itself. */
  initialFocus?: 'first' | 'panel' | undefined;
  /** Minimum panel width; "trigger" matches the trigger's width. */
  minWidth?: number | 'trigger' | undefined;
  className?: string | undefined;
}

const NARROW = '(max-width: 720px)';

/**
 * The one accessible floating-panel base. Non-modal: Escape, an outside click or
 * tabbing away closes it, and focus returns to the trigger when the panel closed
 * from inside. Rendered in a portal with position: fixed, flipped and clamped to
 * the viewport, so it is never clipped by a scrolling table.
 */
export function Popover({ trigger, children, label, role = 'dialog', placement = 'bottom-start', open: controlled, defaultOpen = false, onOpenChange, sheet = false, initialFocus = 'first', minWidth, className }: PopoverProps) {
  const [uncontrolled, setUncontrolled] = useState(defaultOpen);
  const open = controlled ?? uncontrolled;
  const id = useId();
  // The trigger element lives in state (not a ref) so render never reads a ref.
  const [triggerEl, setTriggerEl] = useState<HTMLElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [style, setStyle] = useState<CSSProperties>({ visibility: 'hidden' });
  const [asSheet, setAsSheet] = useState(false);
  const [placed, setPlaced] = useState(false);

  const setOpen = useCallback((next: boolean) => {
    if (controlled === undefined) setUncontrolled(next);
    onOpenChange?.(next);
  }, [controlled, onOpenChange]);

  const close = useCallback((returnFocus = true) => {
    setOpen(false);
    if (returnFocus) triggerEl?.focus();
  }, [setOpen, triggerEl]);

  const place = useCallback(() => {
    const anchor = triggerEl;
    const panel = panelRef.current;
    if (!anchor || !panel) return;
    const narrow = sheet && window.matchMedia(NARROW).matches;
    setAsSheet(narrow);
    if (narrow) { setStyle({}); return; }
    const rect = anchor.getBoundingClientRect();
    const width = minWidth === 'trigger' ? rect.width : minWidth;
    const pos = computePosition(rect, { width: Math.max(panel.offsetWidth, width ?? 0), height: panel.offsetHeight }, placement, { width: window.innerWidth, height: window.innerHeight });
    setStyle({ top: pos.top, left: pos.left, minWidth: width });
  }, [placement, sheet, minWidth, triggerEl]);

  useLayoutEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- measuring and positioning before paint is what layout effects are for */
    if (!open) { setPlaced(false); return; }
    place();
    setPlaced(true);
    /* eslint-enable react-hooks/set-state-in-effect */
  // Remeasure after switching between sheet and floating-panel geometry.
  }, [open, place, asSheet]);

  // Move focus in only once the panel is positioned. Until then it is
  // `visibility: hidden`, and browsers ignore focus() on hidden elements (jsdom
  // doesn't, which is why only a real browser showed the bug: Escape then had
  // nothing inside the panel to act on). Prefers a `[data-autofocus]` control.
  useEffect(() => {
    if (!open || !placed) return;
    const panel = panelRef.current;
    if (!panel || panel.contains(document.activeElement)) return;
    const target = initialFocus === 'first'
      ? (role === 'menu' ? panel.querySelector<HTMLElement>('[role="menuitem"]') : panel.querySelector<HTMLElement>('[data-autofocus]') ?? focusables(panel)[0])
      : null;
    (target ?? panel).focus({ preventScroll: true });
  }, [open, placed, initialFocus, role]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: PointerEvent) => {
      const t = event.target as Node;
      if (panelRef.current?.contains(t) || triggerEl?.contains(t)) return;
      close(false);
    };
    window.addEventListener('pointerdown', onPointer, true);
    window.addEventListener('scroll', place, { capture: true, passive: true });
    window.addEventListener('resize', place);
    return () => {
      window.removeEventListener('pointerdown', onPointer, true);
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
    };
  }, [open, close, place, triggerEl]);

  const onPanelKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') { event.stopPropagation(); close(); return; }
    if (role !== 'menu') return;
    const items = Array.from(panelRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not([aria-disabled="true"])') ?? []);
    if (items.length === 0) return;
    const at = items.indexOf(document.activeElement as HTMLElement);
    const go = (i: number) => { event.preventDefault(); items[(i + items.length) % items.length]?.focus(); };
    if (event.key === 'ArrowDown') go(at + 1);
    else if (event.key === 'ArrowUp') go(at - 1);
    else if (event.key === 'Home') go(0);
    else if (event.key === 'End') go(items.length - 1);
    else if (event.key === 'Tab') close(false);
  };

  const onPanelBlur = (event: FocusEvent<HTMLDivElement>) => {
    const next = event.relatedTarget as Node | null;
    if (!next || panelRef.current?.contains(next) || triggerEl?.contains(next)) return;
    close(false);
  };

  if (!isValidElement(trigger)) throw new Error('Popover trigger must be a single element');
  const original = trigger.props as { onClick?: (e: ReactMouseEvent<HTMLElement>) => void; onKeyDown?: (e: ReactKeyboardEvent<HTMLElement>) => void };
  const triggerProps: TriggerProps = {
    ref: setTriggerEl,
    'aria-expanded': open,
    'aria-haspopup': role,
    'aria-controls': open ? id : undefined,
    onClick: (event) => { original.onClick?.(event); if (!event.defaultPrevented) setOpen(!open); },
    onKeyDown: (event) => {
      original.onKeyDown?.(event);
      if (role === 'menu' && !open && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) { event.preventDefault(); setOpen(true); }
    },
  };

  const content = typeof children === 'function' ? children({ close: () => close() }) : children;
  return <>
    {cloneElement(trigger, triggerProps as unknown as Record<string, unknown>)}
    {open && typeof document !== 'undefined' && createPortal(<>
      {asSheet && <div className="popover-scrim" aria-hidden="true" />}
      <div
        ref={panelRef}
        id={id}
        role={role}
        aria-label={label}
        aria-modal={undefined}
        tabIndex={-1}
        className={cx('popover-panel', asSheet && 'is-sheet', role === 'menu' && 'is-menu', className)}
        style={style}
        onKeyDown={onPanelKeyDown}
        onBlur={onPanelBlur}
        // Choosing a menu item closes the menu and returns focus to the trigger.
        onClick={role === 'menu' ? (event) => {
          const item = (event.target as HTMLElement).closest('[role="menuitem"]');
          if (item && item.getAttribute('aria-disabled') !== 'true') close();
        } : undefined}
      >
        {content}
      </div>
    </>, document.body)}
  </>;
}

/** An action inside a role="menu" Popover. Renders an <a> when given href. */
export function MenuItem({ href, onSelect, children, disabled = false, download, description }: { href?: string; onSelect?: () => void; children: ReactNode; disabled?: boolean; download?: boolean | string; description?: ReactNode }) {
  const body = <><span className="menu-item-label">{children}</span>{description != null && <span className="menu-item-description">{description}</span>}</>;
  if (href && !disabled) return <a role="menuitem" tabIndex={-1} className="menu-item" href={href} download={download === true ? '' : download || undefined} onClick={() => onSelect?.()}>{body}</a>;
  return <button type="button" role="menuitem" tabIndex={-1} className="menu-item" aria-disabled={disabled || undefined} onClick={() => { if (!disabled) onSelect?.(); }}>{body}</button>;
}

/** A labelled group of menu items with a divider above. */
export function MenuGroup({ label, children }: { label: string; children: ReactNode }) {
  return <div role="group" aria-label={label} className="menu-group"><div className="menu-group-label" aria-hidden="true">{label}</div>{children}</div>;
}
