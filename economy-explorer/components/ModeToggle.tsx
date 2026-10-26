'use client';
// Surface-mode picker: cycles System → Light → Dark → System. The choice is a
// cookie; the layout's pre-paint script applies it before first paint, and the
// toggle flips <html data-mode> directly. Nothing on the server reads the mode,
// so a change needs no re-render. The command palette's "Toggle theme" uses
// the same store through toggleMode().
//
// 'system' follows the OS preference: it resolves via matchMedia and stays
// live via a change listener while System is selected.

import { useEffect, useSyncExternalStore } from 'react';
import type { ModeChoice } from '@/lib/theme';

const modeListeners = new Set<() => void>();
function subscribeMode(listener: () => void): () => void {
  modeListeners.add(listener);
  return () => {
    modeListeners.delete(listener);
  };
}

const NEXT: Record<ModeChoice, ModeChoice> = { system: 'light', light: 'dark', dark: 'system' };
const LABEL: Record<ModeChoice, string> = { system: 'System', light: 'Light', dark: 'Dark' };

/** Concrete mode for a choice: 'system' resolves to the OS preference. */
function resolve(choice: ModeChoice): 'dark' | 'light' {
  if (choice !== 'system') return choice;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function readModeCookie(): ModeChoice {
  if (typeof document === 'undefined') return 'dark';
  const m = document.cookie.match(/(?:^|; )mode=([^;]+)/);
  const v = m?.[1] ? decodeURIComponent(m[1]).toLowerCase() : 'dark';
  return v === 'light' || v === 'system' ? v : 'dark';
}

export function setModeChoice(choice: ModeChoice): void {
  document.documentElement.setAttribute('data-mode', resolve(choice));
  document.cookie = `mode=${choice}; path=/; max-age=${60 * 60 * 24 * 365}; samesite=lax`;
  for (const listener of modeListeners) listener();
}

/** Switch between light and dark from whatever is showing now. */
export function toggleMode(): 'dark' | 'light' {
  const next = resolve(readModeCookie()) === 'dark' ? 'light' : 'dark';
  setModeChoice(next);
  return next;
}

export function useModeChoice(): ModeChoice {
  return useSyncExternalStore(subscribeMode, readModeCookie, () => 'dark' as const);
}

export function ModeToggle({ className }: { className?: string }) {
  const choice = useModeChoice();

  // While on System, track the OS preference live.
  useEffect(() => {
    if (choice !== 'system') return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const apply = () => document.documentElement.setAttribute('data-mode', mq.matches ? 'dark' : 'light');
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, [choice]);

  return (
    <button
      type="button"
      className={`shell-icon-button${className ? ` ${className}` : ''}`}
      onClick={() => setModeChoice(NEXT[choice])}
      aria-label={`Theme: ${LABEL[choice]}. Switch to ${LABEL[NEXT[choice]]}.`}
      title={`Theme: ${LABEL[choice]} (click for ${LABEL[NEXT[choice]]})`}
    >
      <ModeIcon choice={choice} />
    </button>
  );
}

function ModeIcon({ choice }: { choice: ModeChoice }) {
  const common = { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.75, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true };
  if (choice === 'system') {
    return <svg {...common}><rect x="3" y="4" width="18" height="12" rx="2" /><path d="M8 20h8M12 16v4" /></svg>;
  }
  if (choice === 'light') {
    return <svg {...common}><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></svg>;
  }
  return <svg {...common}><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" /></svg>;
}
