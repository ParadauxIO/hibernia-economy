// LocalTimestamp renders UTC on the server and during hydration, then switches
// to the browser's IANA zone. The browser zone comes from
// Intl.DateTimeFormat().resolvedOptions().timeZone, which we stub per test
// (process.env.TZ can't be changed reliably mid-process).
import { renderToString } from 'react-dom/server';
import type { ReactElement } from 'react';
import { hydrateRoot, type Root } from 'react-dom/client';
// RTL's act flags the act environment for React; vitest has no globals for it to hook.
import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { LocalTimestamp } from '@/components/LocalTimestamp';

const ISO = '2026-03-08T15:04:00.000Z'; // a Sunday afternoon in UTC, after US DST began
const realResolvedOptions = Intl.DateTimeFormat.prototype.resolvedOptions;

function stubBrowserZone(timeZone: string): void {
  vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockImplementation(function (this: Intl.DateTimeFormat) {
    return { ...realResolvedOptions.call(this), timeZone };
  });
}

/** ICU may use U+202F before AM/PM; compare on plain spaces. */
function text(el: Element | null): string {
  return (el?.textContent ?? '').replace(/\s/g, ' ');
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('LocalTimestamp in the browser', () => {
  it.each([
    ['UTC', 'Mar 8, 3:04 PM UTC'],
    ['America/New_York', 'Mar 8, 11:04 AM EDT'],
    ['Europe/Dublin', 'Mar 8, 3:04 PM GMT'],
    ['Asia/Kolkata', 'Mar 8, 8:34 PM GMT+5:30'],
    // Crosses midnight: the local calendar date differs from UTC.
    ['Australia/Sydney', 'Mar 9, 2:04 AM GMT+11'],
    ['Pacific/Honolulu', 'Mar 8, 5:04 AM HST'],
  ])('shows %s local time with its zone label', (zone, expected) => {
    stubBrowserZone(zone);
    const { container } = render(<LocalTimestamp value={ISO} />);
    const time = container.querySelector('time');
    expect(text(time)).toBe(expected);
    // The machine-readable value and tooltip always carry the exact UTC instant.
    expect(time).toHaveAttribute('datetime', ISO);
    expect(time).toHaveAttribute('title', `${ISO} · ${zone}`);
  });

  it('follows DST for the instant shown, not for today', () => {
    stubBrowserZone('America/New_York');
    const { container } = render(
      <>
        <LocalTimestamp value="2026-01-15T12:00:00Z" />
        <LocalTimestamp value="2026-07-15T12:00:00Z" />
      </>,
    );
    expect([...container.querySelectorAll('time')].map(text)).toEqual(['Jan 15, 7:00 AM EST', 'Jul 15, 8:00 AM EDT']);
  });

  it('accepts a Date as well as a string', () => {
    stubBrowserZone('UTC');
    const { container } = render(<LocalTimestamp value={new Date(ISO)} />);
    expect(container.querySelector('time')).toHaveAttribute('datetime', ISO);
  });

  it('falls back to UTC when the browser reports no zone', () => {
    stubBrowserZone('');
    const { container } = render(<LocalTimestamp value={ISO} />);
    expect(text(container.querySelector('time'))).toBe('Mar 8, 3:04 PM UTC');
    expect(container.querySelector('time')).toHaveAttribute('title', `${ISO} · UTC`);
  });

  it.each([
    ['null', null],
    ['an unparseable string', 'not a date'],
    ['an empty string', ''],
  ])('renders an em dash for %s', (_label, value) => {
    const { container } = render(<span data-testid="ts"><LocalTimestamp value={value} /></span>);
    expect(screen.getByTestId('ts')).toHaveTextContent(/^—$/);
    expect(container.querySelector('time')).toBeNull();
  });
});

describe('LocalTimestamp server render and hydration', () => {
  it('server-renders UTC whatever the browser zone is', () => {
    stubBrowserZone('Australia/Sydney');
    const host = document.createElement('div');
    host.innerHTML = renderToString(<LocalTimestamp value={ISO} />);
    const time = host.querySelector('time');
    expect(text(time)).toBe('Mar 8, 3:04 PM UTC');
    expect(time).toHaveAttribute('title', `${ISO} · UTC`);
    expect(time).toHaveAttribute('datetime', ISO);
  });

  /** Server-render `server`, then hydrate `client` over it; report recoverable errors. */
  async function hydrate(server: ReactElement, client: ReactElement) {
    const container = document.createElement('div');
    document.body.appendChild(container);
    container.innerHTML = renderToString(server);
    const serverTime = container.querySelector('time');
    const recoverable = vi.fn();
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    let root: Root | undefined;
    await act(async () => {
      root = hydrateRoot(container, client, { onRecoverableError: recoverable });
      await Promise.resolve();
    });
    onTestFinished(() => {
      act(() => root?.unmount());
      container.remove();
    });
    return { container, serverTime, recoverable, consoleError };
  }

  it('hydrates the UTC markup without a mismatch, then switches to local time', async () => {
    stubBrowserZone('Australia/Sydney');
    const el = <LocalTimestamp value={ISO} />;
    const { container, serverTime, recoverable, consoleError } = await hydrate(el, el);

    expect(recoverable).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
    // Same DOM node: hydrated in place, not thrown away and client-rendered.
    expect(container.querySelector('time')).toBe(serverTime);
    expect(text(serverTime)).toBe('Mar 9, 2:04 AM GMT+11');
    expect(serverTime).toHaveAttribute('title', `${ISO} · Australia/Sydney`);
    expect(serverTime).toHaveAttribute('datetime', ISO);
  });

  it('(control) the harness does catch a hydration mismatch', async () => {
    stubBrowserZone('UTC');
    const { recoverable } = await hydrate(<LocalTimestamp value={ISO} />, <LocalTimestamp value="2026-03-08T16:04:00Z" />);
    expect(recoverable).toHaveBeenCalled();
  });
});
