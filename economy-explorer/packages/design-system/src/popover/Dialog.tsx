'use client';

import { useEffect, useId, useRef, type ReactNode } from 'react';
import { cx } from '../cx';

export interface DialogProps {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  description?: ReactNode | undefined;
  children?: ReactNode | undefined;
  /** Buttons in the footer; put the primary action last. */
  actions?: ReactNode | undefined;
  size?: 'sm' | 'md' | 'lg' | undefined;
}

/**
 * A modal dialog on the native <dialog> element: the browser provides the focus
 * trap, inert background, Escape handling and top layer. Clicking the backdrop
 * closes it. Focus returns to whatever opened it.
 */
export function Dialog({ open, onClose, title, description, children, actions, size = 'sm' }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descId = useId();
  const opener = useRef<Element | null>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      opener.current = document.activeElement;
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
    } else if (!open && dialog.open) {
      if (typeof dialog.close === 'function') dialog.close();
      else dialog.removeAttribute('open');
      (opener.current as HTMLElement | null)?.focus();
    }
  }, [open]);

  return <dialog
    ref={ref}
    className={cx('dialog', `dialog-${size}`)}
    aria-labelledby={titleId}
    aria-describedby={description != null ? descId : undefined}
    onClose={() => { if (open) onClose(); }}
    onCancel={(event) => { event.preventDefault(); onClose(); }}
    onClick={(event) => { if (event.target === ref.current) onClose(); }}
  >
    <div className="dialog-body">
      <h2 id={titleId} className="dialog-title">{title}</h2>
      {description != null && <div id={descId} className="dialog-description">{description}</div>}
      {children}
    </div>
    {actions != null && <div className="dialog-actions">{actions}</div>}
  </dialog>;
}
