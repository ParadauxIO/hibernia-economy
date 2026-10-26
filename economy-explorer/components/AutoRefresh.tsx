'use client';
// Re-renders the current page from the server (router.refresh()) once per
// cache window, so live figures stay current without client fetching.
//
// Timing (spec §4.3):
//  - Aligned to wall-clock window boundaries, like lib/cache's epoch-aligned
//    windows, so a refresh asks for a window the server has (or is computing).
//  - Jittered by ±10 s around a point 10 s after boundary + lag, so tabs don't
//    all arrive in the same instant, and none arrives before the new window exists.
//  - Skipped while the tab is hidden. When it becomes visible again and the
//    data is older than one window, it refreshes promptly.
// The "updated" label re-renders every 30 s, not every second.
//
// Refreshing only the live sections instead of the whole tree is ECON's
// change (§4.3); this keeps router.refresh().

import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';

interface AutoRefreshProps {
  windowMs?: number;
  lagMs?: number;
  label?: string;
}

export const JITTER_MS = 10_000;
const LABEL_TICK_MS = 30_000;

/** Delay until the next refresh: the next boundary + lag, then a jitter of up to ±JITTER_MS around +JITTER_MS. */
export function nextRefreshDelay(now: number, windowMs: number, lagMs: number, random: () => number = Math.random): number {
  // The jitter span can't exceed what's left of the window after the lag.
  const half = Math.max(0, Math.min(JITTER_MS, (windowMs - lagMs) / 2 - 500));
  const offset = half + (random() * 2 - 1) * half; // in [0, 2·half]
  return windowMs - (now % windowMs) + lagMs + offset;
}

export function AutoRefresh({ windowMs = 30_000, lagMs = 1_500, label = 'Live' }: AutoRefreshProps) {
  const router = useRouter();
  const [lastTick, setLastTick] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    let last = Date.now();
    const refresh = () => {
      router.refresh();
      last = Date.now();
      setLastTick(last);
      setNow(last);
    };
    const arm = () => {
      timer = setTimeout(() => {
        if (document.visibilityState === 'visible') refresh();
        arm();
      }, nextRefreshDelay(Date.now(), windowMs, lagMs));
    };
    const onVisibility = () => {
      if (document.visibilityState !== 'visible') return;
      setNow(Date.now());
      if (Date.now() - last >= windowMs) {
        clearTimeout(timer);
        refresh();
        arm();
      }
    };
    arm();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [router, windowMs, lagMs]);

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), LABEL_TICK_MS);
    return () => clearInterval(id);
  }, []);

  return (
    <span className="live-status" title={`Refreshes about every ${Math.round(windowMs / 1000)} seconds while this tab is open`}>
      <span className="live-dot" aria-hidden="true" /> {label} · updated {fmtAgo(now - lastTick)}
    </span>
  );
}

export function fmtAgo(ms: number): string {
  const minutes = Math.floor(Math.max(0, ms) / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  return `${hours} h ago`;
}
