// Range presets shared by RangePicker (UI) and the host's lib/range.ts (parsing).
// URL params: range=<preset|custom>, from=YYYY-MM-DD, to=YYYY-MM-DD, cmp=1.

export const RANGE_PRESETS = [
  { key: '24h', label: '24h', title: 'Last 24 hours' },
  { key: '7d', label: '7d', title: 'Last 7 days' },
  { key: '30d', label: '30d', title: 'Last 30 days' },
  { key: '90d', label: '90d', title: 'Last 90 days' },
  { key: 'ytd', label: 'YTD', title: 'Year to date' },
  { key: '1y', label: '1y', title: 'Last 12 months' },
  { key: 'all', label: 'All', title: 'All time' },
] as const;

export type RangePreset = (typeof RANGE_PRESETS)[number]['key'];

export const RANGE_PARAMS = { range: 'range', from: 'from', to: 'to', compare: 'cmp' } as const;

/** What RangePicker needs to render the current selection (serialisable, from the server). */
export interface RangeValue {
  preset: RangePreset | 'custom';
  /** Inclusive UTC days, YYYY-MM-DD. */
  from: string;
  to: string;
  compare: boolean;
}
