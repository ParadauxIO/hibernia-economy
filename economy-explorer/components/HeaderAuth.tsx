'use client';
// The account control at the right of the top bar (spec §6.1). Logged out: a
// quiet "Log in". Logged in: a chip with the player's head and their personal
// balance, opening a menu of their own pages. The balance arrives as a
// server-rendered slot (streamed behind Suspense by the layout), so this
// component never sees or formats money itself.

import Link from 'next/link';
import type { Route } from 'next';
import { useEffect, useState, useTransition, type ReactNode } from 'react';
import { PlayerHead } from '@democracycraft/design-system';
import { Popover } from '@democracycraft/design-system/popover';
import { useViewer } from '@/components/ViewerContext';
import { ChevronDownIcon } from '@/components/HeaderIcons';
import { loginAction, logoutAction } from '@/lib/actions/auth';
import { ACCOUNT_LINKS } from '@/lib/nav';

export function HeaderAuth({ balance }: { balance?: ReactNode }) {
  const v = useViewer();
  const [loggingOut, startLogout] = useTransition();
  const [open, setOpen] = useState(false);
  useFirstItemFocus(open);

  if (v.anon) {
    return (
      <form action={loginAction}>
        <button type="submit" className="btn shell-login">Log in</button>
      </form>
    );
  }

  const name = v.minecraftName ?? 'Your account';
  const showBalance = v.linked && balance != null;
  const trigger = (
    <button type="button" className="shell-account">
      <PlayerHead uuid={v.minecraftUuid} name={v.minecraftName} size={20} />
      <span className="shell-account-name">{name}</span>
      {showBalance && <span className="shell-account-balance"><span className="sr-only">, personal balance</span>{' '}{balance}</span>}
      <ChevronDownIcon size={14} />
    </button>
  );

  return (
    <Popover trigger={trigger} role="menu" open={open} onOpenChange={setOpen} label={`Account: ${name}`} placement="bottom-end" className="shell-account-menu">
      {/* Repeats the trigger for sighted users; screen readers already heard it there. */}
      <div className="shell-account-head" aria-hidden="true">
        <PlayerHead uuid={v.minecraftUuid} name={v.minecraftName} size={32} />
        <div>
          <p className="shell-account-head-name">{name}</p>
          {showBalance ? (
            <p className="shell-account-head-meta">Personal balance <span className="shell-account-head-balance">{balance}</span></p>
          ) : (
            <p className="shell-account-head-meta">{v.linked ? 'Signed in' : 'Minecraft account not linked'}</p>
          )}
        </div>
      </div>
      {!v.linked && (
        <div className="shell-menu-group" role="group" aria-label="Link">
          <Link role="menuitem" tabIndex={-1} href="/link" prefetch={false} className="menu-item shell-link-cta">
            <span className="menu-item-label">Link your Minecraft account</span>
            <span className="menu-item-description">Connect your in-game identity to see your own finances.</span>
          </Link>
        </div>
      )}
      <div className="shell-menu-group" role="group" aria-label="Your pages">
        {ACCOUNT_LINKS.map((link) => (
          <Link key={link.href} role="menuitem" tabIndex={-1} href={link.href as Route} prefetch={false} className="menu-item">
            <span className="menu-item-label">{link.label}</span>
          </Link>
        ))}
      </div>
      {v.isAdmin && (
        <div className="shell-menu-group" role="group" aria-label="Administration">
          <Link role="menuitem" tabIndex={-1} href="/admin" prefetch={false} className="menu-item">
            <span className="menu-item-label">Admin</span>
          </Link>
        </div>
      )}
      <div className="shell-menu-group" role="group" aria-label="Session">
        <button
          type="button"
          role="menuitem"
          tabIndex={-1}
          className="menu-item"
          aria-disabled={loggingOut || undefined}
          onClick={() => startLogout(() => logoutAction())}
        >
          <span className="menu-item-label">{loggingOut ? 'Logging out…' : 'Log out'}</span>
        </button>
      </div>
    </Popover>
  );
}

/**
 * Put focus on the first menu item once the menu is visible. The design
 * system's Popover tries this in a layout effect, but its panel is still
 * `visibility: hidden` until positioned, so browsers ignore that focus call.
 */
function useFirstItemFocus(open: boolean): void {
  useEffect(() => {
    if (!open) return;
    const id = requestAnimationFrame(() => {
      const panel = document.querySelector<HTMLElement>('.shell-account-menu');
      if (panel && !panel.contains(document.activeElement)) panel.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    });
    return () => cancelAnimationFrame(id);
  }, [open]);
}
