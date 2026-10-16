'use client';
// Polls the current RSC page via router.refresh(). The page keeps its full SSR
// semantics — refresh triggers the server to re-render with new data; no client
// fetches.
//
// Ticks are aligned to wall-clock boundaries of `windowMs` (not relative to
// mount), so the refresh lands in lockstep with the server's epoch-aligned cache
// reset (lib/cache). `lagMs` lets the new window's value be computed before we
// fetch it. Because every client refreshes on the same boundary, the resulting
// request burst hits the warm shared cache (cheap reads) — the heavy query ran
// once for the window, under the cache's cross-pod lock.

import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';

interface AutoRefreshProps {
  windowMs?: number;
  lagMs?: number;
  label?: string;
}

export function AutoRefresh({ windowMs = 30_000, lagMs = 1_500, label = 'Live' }: AutoRefreshProps) {
  const router = useRouter();
  const [lastTick, setLastTick] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());

  // Schedule each refresh at the next window boundary + lag, then re-arm.
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    const arm = () => {
      const ms = Date.now();
      const delay = windowMs - (ms % windowMs) + lagMs;
      timer = setTimeout(() => {
        router.refresh();
        setLastTick(Date.now());
        arm();
      }, delay);
    };
    arm();
    return () => clearTimeout(timer);
  }, [router, windowMs, lagMs]);

  // Tick clock so the "Xs ago" label updates without re-rendering the parent.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const secs = Math.max(0, Math.floor((now - lastTick) / 1000));
  return (
    <span className="updated-pill" title={`refreshes every ${windowMs / 1000}s`}>
      <span className="updated-dot" /> {label} · {fmtAgo(secs)}
    </span>
  );
}

function fmtAgo(s: number): string {
  if (s < 5) return 'just now';
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  return `${m}m ago`;
}
