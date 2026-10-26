'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import type { Route } from 'next';
import { useState, useTransition } from 'react';
import { Popover } from '../popover/Popover';
import { RANGE_PARAMS, RANGE_PRESETS, type RangePreset, type RangeValue } from '../range';
import { Segmented } from '../Segmented';

export interface RangePickerProps {
  /** The parsed current range (lib/range.ts `parseRange(...).value`). */
  value: RangeValue;
  /** Subset / order of presets to offer. Default: all of 24h, 7d, 30d, 90d, YTD, 1y, All. */
  presets?: readonly RangePreset[] | undefined;
  /** Offer the "Compare to previous period" toggle. */
  allowCompare?: boolean | undefined;
  /** Earliest / latest selectable custom day. */
  min?: string | undefined;
  max?: string | undefined;
  /** Extra params removed on change (pagination cursors). `page` is always removed. */
  resetParams?: string[] | undefined;
}

/**
 * Preset ranges, a custom from/to, and "compare to previous". Writes `range`,
 * `from`, `to` and `cmp` to the URL and lets the server re-render; parsing lives
 * in the host's lib/range.ts.
 */
export function RangePicker({ value, presets = RANGE_PRESETS.map((p) => p.key), allowCompare = true, min, max, resetParams = [] }: RangePickerProps) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const [pending, startTransition] = useTransition();
  const [from, setFrom] = useState(value.from);
  const [to, setTo] = useState(value.to);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);

  const navigate = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params.toString());
    for (const key of ['page', 'days', ...resetParams]) next.delete(key);
    for (const [k, v] of Object.entries(patch)) { if (v === null) next.delete(k); else next.set(k, v); }
    const qs = next.toString();
    startTransition(() => router.push(`${pathname}${qs ? `?${qs}` : ''}` as Route, { scroll: false }));
  };

  const options = RANGE_PRESETS.filter((p) => presets.includes(p.key));
  const customLabel = value.preset === 'custom' ? `${value.from} – ${value.to}` : 'Date range';

  return <div className="range-picker" aria-busy={pending || undefined}>
    <Segmented label="Time range" value={value.preset}
      items={options.map((p) => ({ value: p.key, label: <span title={p.title}>{p.label}</span> }))}
      onChange={(key) => navigate({ [RANGE_PARAMS.range]: key, [RANGE_PARAMS.from]: null, [RANGE_PARAMS.to]: null })} />
    <Popover open={open} onOpenChange={(next) => {
      if (next) { setFrom(value.from); setTo(value.to); setError(''); }
      setOpen(next);
    }} label="Custom range" placement="bottom-end" sheet trigger={<button type="button" className="btn btn-quiet range-custom" aria-pressed={value.preset === 'custom'}>{customLabel}</button>}>
      {({ close }) => <form className="range-form" onSubmit={(event) => {
        event.preventDefault();
        if (!from || !to) return;
        if (from > to) { setError('End date must be on or after start date.'); return; }
        close();
        navigate({ [RANGE_PARAMS.range]: 'custom', [RANGE_PARAMS.from]: from, [RANGE_PARAMS.to]: to });
      }}>
        <label className="field"><span className="field-label">From</span><input type="date" className="input" value={from} min={min} max={to || max} onChange={(e) => { setFrom(e.target.value); setError(''); }} required /></label>
        <label className="field"><span className="field-label">To</span><input type="date" className="input" value={to} min={from || min} max={max} onChange={(e) => { setTo(e.target.value); setError(''); }} required /></label>
        <p className="range-form-note">Days are in UTC and include both ends.</p>
        {error && <p role="alert">{error}</p>}
        <div className="range-form-actions">
          <button type="button" className="btn btn-quiet" onClick={close}>Cancel</button>
          <button type="submit" className="btn btn-primary">Apply range</button>
        </div>
      </form>}
    </Popover>
    {allowCompare && <button type="button" className="btn btn-quiet range-compare" aria-pressed={value.compare}
      onClick={() => navigate({ [RANGE_PARAMS.compare]: value.compare ? null : '1' })}>
      <span className="range-compare-box" aria-hidden="true" />Compare to previous
    </button>}
  </div>;
}
