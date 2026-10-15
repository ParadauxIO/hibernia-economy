'use client';

import { useSyncExternalStore } from 'react';
import { fmtTs } from '@/lib/format';

const subscribe = () => () => {};
const serverTimezone = () => 'UTC';
const browserTimezone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

/** UTC during SSR/hydration, then the browser's IANA zone (including DST). */
export function LocalTimestamp({ value }: { value: string | Date | null }) {
  const timeZone = useSyncExternalStore(subscribe, browserTimezone, serverTimezone);
  if (!value) return <>—</>;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return <>—</>;
  const zoneLabel = new Intl.DateTimeFormat('en-US', { timeZone, timeZoneName: 'short' })
    .formatToParts(date).find(part => part.type === 'timeZoneName')?.value ?? timeZone;
  return <time dateTime={date.toISOString()} title={`${date.toISOString()} · ${timeZone}`}>
    {fmtTs(value, timeZone)} <span className="muted small">{zoneLabel}</span>
  </time>;
}
