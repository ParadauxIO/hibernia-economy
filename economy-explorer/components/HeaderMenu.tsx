'use client';
// The phone menu: every section and its pages in one sheet, plus the theme
// switch. A native modal <dialog>, so the browser provides the focus trap,
// inert page and Escape; focus returns to the Menu button on close.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { useViewer } from '@/components/ViewerContext';
import { ModeToggle } from '@/components/ModeToggle';
import { CloseIcon, MenuIcon } from '@/components/HeaderIcons';
import { itemMatches, sectionForPath, visibleSections } from '@/lib/nav';

export function HeaderMenu() {
  const path = usePathname();
  const viewer = useViewer();
  const [open, setOpen] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const active = sectionForPath(path);

  useEffect(() => {
    const d = dialog.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    else if (!open && d.open) {
      d.close();
      button.current?.focus();
    }
  }, [open]);

  return (
    <>
      <button ref={button} type="button" className="shell-icon-button shell-menu-button" aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(true)}>
        <MenuIcon size={18} />
        <span className="sr-only">Menu</span>
      </button>
      <dialog
        ref={dialog}
        className="shell-menu"
        aria-label="Menu"
        onCancel={(e) => { e.preventDefault(); setOpen(false); }}
        onClick={(e) => { if (e.target === dialog.current) setOpen(false); }}
      >
        <div className="shell-menu-head">
          <span className="shell-menu-title">Menu</span>
          <button type="button" className="shell-icon-button" onClick={() => setOpen(false)}>
            <CloseIcon size={18} />
            <span className="sr-only">Close menu</span>
          </button>
        </div>
        <nav aria-label="Site" className="shell-menu-nav">
          {visibleSections(viewer).map((section) => {
            const items = section.items?.filter((i) => !i.visible || i.visible(viewer));
            return (
              <div key={section.id} className="shell-menu-section">
                {items ? (
                  <>
                    <p className={`shell-menu-section-label${active?.id === section.id ? ' is-current' : ''}`}>{section.label}</p>
                    <ul>
                      {items.map((item) => (
                        <li key={item.href}>
                          <Link href={item.href} prefetch={false} className="shell-menu-link" aria-current={itemMatches(item, path) ? 'page' : undefined} onClick={() => setOpen(false)}>
                            {item.label}
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </>
                ) : (
                  <Link href={section.href} prefetch={false} className="shell-menu-link is-section" aria-current={active?.id === section.id ? 'page' : undefined} onClick={() => setOpen(false)}>
                    {section.label}
                  </Link>
                )}
              </div>
            );
          })}
        </nav>
        <div className="shell-menu-foot">
          <span>Appearance</span>
          <ModeToggle />
        </div>
      </dialog>
    </>
  );
}
