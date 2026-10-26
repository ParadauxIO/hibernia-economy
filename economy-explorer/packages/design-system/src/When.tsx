'use client';

import { useSyncExternalStore } from 'react';
import { formatUtc, formatWhen, toDate } from './format';

const subscribe = () => () => {};
const browserZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
const serverZone = () => 'UTC';

export interface WhenProps {
  value: string | number | Date | null | undefined;
  /** Include the time of day for dates older than 24 hours. */
  time?: boolean | undefined;
  /** "5 min ago" under 24 hours (default). Off for columns that must stay absolute. */
  relative?: boolean | undefined;
}

/**
 * A point in time. Relative under 24 h ("5 min ago"), "Oct 25" within the
 * current year, "Oct 25, 2025" otherwise. The server renders the absolute UTC
 * form, so hydration never depends on the clock; the browser then switches to
 * local time. The exact UTC instant is always in the tooltip.
 */
export function When({ value, time = false, relative = true }: WhenProps) {
  const zone = useSyncExternalStore(subscribe, browserZone, serverZone);
  const hydrated = useSyncExternalStore(subscribe, () => true, () => false);
  if (value === null || value === undefined) return <span className="is-empty">—</span>;
  const date = toDate(value);
  if (!Number.isFinite(date.getTime())) return <span className="is-empty">—</span>;
  return <time className="when" dateTime={date.toISOString()} title={formatUtc(date)}>
    {formatWhen(date, { timeZone: zone, relative: relative && hydrated, time })}
  </time>;
}
