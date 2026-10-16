'use client';
// Surface-mode picker: cycles System → Light → Dark → System. The choice is a
// server-rendered cookie (read by the layout), so there's no flash and no
// hydration mismatch — on click we set the cookie, flip <html data-mode>
// immediately for instant feedback, then refresh so server components re-render.
//
// 'system' follows the OS preference: it resolves via matchMedia (the layout's
// pre-paint script does the same on load) and stays live via a change listener
// while System is selected.

import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useSyncExternalStore } from 'react';
import type { ModeChoice } from '@/lib/theme';

// The cookie is the store: SSR renders the 'dark' default, the client reads the
// cookie right after hydration, and the toggle notifies after writing it.
const modeListeners = new Set<() => void>();
function subscribeMode(listener: () => void): () => void {
  modeListeners.add(listener);
  return () => {
    modeListeners.delete(listener);
  };
}

const NEXT: Record<ModeChoice, ModeChoice> = { system: 'light', light: 'dark', dark: 'system' };
const LABEL: Record<ModeChoice, string> = { system: 'System', light: 'Light', dark: 'Dark' };

/** Concrete mode for a choice — 'system' resolves to the OS preference. */
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

export function ModeToggle() {
  const router = useRouter();
  // Read the cookie client-side (the layout no longer resolves it server-side,
  // so it can stay static). The pre-paint script already set data-mode; this
  // just syncs the toggle's own label. SSR renders the 'dark' default.
  const choice = useSyncExternalStore(subscribeMode, readModeCookie, () => 'dark' as const);

  // While on System, track the OS preference live so toggling the OS theme
  // updates the page without a reload.
  useEffect(() => {
    if (choice !== 'system') return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const apply = () => document.documentElement.setAttribute('data-mode', mq.matches ? 'dark' : 'light');
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, [choice]);

  const cycle = useCallback(() => {
    const next = NEXT[choice];
    document.documentElement.setAttribute('data-mode', resolve(next));
    document.cookie = `mode=${next}; path=/; max-age=${60 * 60 * 24 * 365}; samesite=lax`;
    for (const listener of modeListeners) listener();
    router.refresh();
  }, [choice, router]);

  return (
    <button
      type="button"
      className="mode-toggle"
      onClick={cycle}
      aria-label={`Theme: ${LABEL[choice]}. Switch to ${LABEL[NEXT[choice]]}.`}
      title={`Theme: ${LABEL[choice]} (click for ${LABEL[NEXT[choice]]})`}
    >
      <ModeIcon choice={choice} />
    </button>
  );
}

function ModeIcon({ choice }: { choice: ModeChoice }) {
  if (choice === 'system') {
    // monitor
    return (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <rect x="2" y="3" width="20" height="14" rx="2" />
        <path d="M8 21h8M12 17v4" />
      </svg>
    );
  }
  if (choice === 'light') {
    // sun
    return (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" strokeLinecap="round" />
      </svg>
    );
  }
  // moon
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
    </svg>
  );
}
