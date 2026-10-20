'use client';
import { useCallback, useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from 'react';

/** Hover, keyboard and touch help; Escape dismisses without moving focus. */
export function InfoTip({ children, label = 'More information' }: { children: ReactNode; label?: string }) {
  const id = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const [position, setPosition] = useState<CSSProperties | null>(null);
  const open = position !== null;

  const show = useCallback(() => {
    if (!trigger.current) return;
    const rect = trigger.current.getBoundingClientRect();
    if (rect.bottom < 0 || rect.top > window.innerHeight) { setPosition(null); return; }
    const width = Math.min(280, window.innerWidth - 24);
    const above = rect.top > window.innerHeight / 2;
    setPosition({
      width,
      left: Math.max(12, Math.min(rect.left + rect.width / 2 - width / 2, window.innerWidth - width - 12)),
      ...(above ? { bottom: window.innerHeight - rect.top + 6 } : { top: rect.bottom + 6 }),
    });
  }, []);

  useEffect(() => {
    if (!open) return;
    window.addEventListener('scroll', show, { capture: true, passive: true });
    window.addEventListener('resize', show);
    return () => {
      window.removeEventListener('scroll', show, true);
      window.removeEventListener('resize', show);
    };
  }, [open, show]);

  return <span className="info-tip" data-open={open} onMouseEnter={show} onMouseLeave={() => { if (document.activeElement !== trigger.current) setPosition(null); }}>
    <button ref={trigger} className="info-tip-icon" type="button" aria-label={label} aria-describedby={id} onFocus={show} onBlur={() => setPosition(null)} onClick={show} onKeyDown={event => { if (event.key === 'Escape') { setPosition(null); event.stopPropagation(); } }}>?</button>
    <span id={id} className="info-tip-body" role="tooltip" style={position ?? undefined} hidden={!open}>{children}</span>
  </span>;
}
