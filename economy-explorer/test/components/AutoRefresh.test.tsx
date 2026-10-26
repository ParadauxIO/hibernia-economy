import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AutoRefresh, fmtAgo, JITTER_MS, nextRefreshDelay } from '@/components/AutoRefresh';
import { navigation, resetNavigation } from './next-navigation';

vi.mock('next/navigation', () => import('./next-navigation'));

let visibility: DocumentVisibilityState = 'visible';

function setVisibility(next: DocumentVisibilityState) {
  visibility = next;
  document.dispatchEvent(new Event('visibilitychange'));
}

beforeEach(() => {
  vi.useFakeTimers();
  // A window boundary at t=0 makes the schedule easy to read.
  vi.setSystemTime(new Date('2026-10-26T12:00:00.000Z'));
  vi.spyOn(Math, 'random').mockReturnValue(0.5); // no jitter offset beyond the +10 s centre
  visibility = 'visible';
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility });
  resetNavigation();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('nextRefreshDelay', () => {
  it('lands between boundary + lag and boundary + lag + 20 s', () => {
    const now = 7_000; // 7 s into a 30 s window
    expect(nextRefreshDelay(now, 30_000, 1_500, () => 0)).toBe(23_000 + 1_500);
    expect(nextRefreshDelay(now, 30_000, 1_500, () => 0.5)).toBe(23_000 + 1_500 + JITTER_MS);
    expect(nextRefreshDelay(now, 30_000, 1_500, () => 0.999999)).toBeCloseTo(23_000 + 1_500 + 2 * JITTER_MS, -1);
  });

  it('never jitters past the next window on short windows', () => {
    for (const r of [0, 0.5, 0.99]) {
      const d = nextRefreshDelay(0, 10_000, 1_000, () => r);
      expect(d).toBeGreaterThanOrEqual(11_000);
      expect(d).toBeLessThan(20_000);
    }
  });
});

describe('AutoRefresh', () => {
  it('refreshes once per window while the tab is visible', async () => {
    render(<AutoRefresh windowMs={30_000} lagMs={1_500} />);
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000 + 1_500 + JITTER_MS - 1); });
    expect(navigation.router.refresh).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(navigation.router.refresh).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(navigation.router.refresh).toHaveBeenCalledTimes(2);
  });

  it('skips refreshes while the tab is hidden', async () => {
    render(<AutoRefresh windowMs={30_000} lagMs={1_500} />);
    act(() => setVisibility('hidden'));
    await act(async () => { await vi.advanceTimersByTimeAsync(5 * 60_000); });
    expect(navigation.router.refresh).not.toHaveBeenCalled();
  });

  it('catches up as soon as a stale tab becomes visible again', async () => {
    render(<AutoRefresh windowMs={30_000} lagMs={1_500} />);
    act(() => setVisibility('hidden'));
    await act(async () => { await vi.advanceTimersByTimeAsync(2 * 60_000); });
    act(() => setVisibility('visible'));
    expect(navigation.router.refresh).toHaveBeenCalledTimes(1);
  });

  it('does not refresh on return when the data is still fresh', async () => {
    render(<AutoRefresh windowMs={30_000} lagMs={1_500} />);
    act(() => setVisibility('hidden'));
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000); });
    act(() => setVisibility('visible'));
    expect(navigation.router.refresh).not.toHaveBeenCalled();
  });

  it('updates its relative time every 30 s, not every second', async () => {
    render(<AutoRefresh windowMs={10 * 60_000} lagMs={0} label="Live" />);
    expect(screen.getByText(/Live · updated just now/)).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(89_000); });
    // 89 s have passed but the label last re-rendered at 60 s.
    expect(screen.getByText(/updated 1 min ago/)).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(31_000); });
    expect(screen.getByText(/updated 2 min ago/)).toBeInTheDocument();
  });
});

describe('fmtAgo', () => {
  it.each([[0, 'just now'], [59_999, 'just now'], [60_000, '1 min ago'], [59 * 60_000, '59 min ago'], [2 * 3_600_000, '2 h ago']])('%i ms → %s', (ms, text) => {
    expect(fmtAgo(ms)).toBe(text);
  });
});
