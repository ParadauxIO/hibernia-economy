'use client';
// Toolbar for searchParams-driven SSR pages. Updates the URL on input change;
// the RSC parent re-renders with new data. No client-side data fetch — the
// HTML round-trip is the data fetch.

import { Button, Input, Select, ToolbarLayout } from '@democracycraft/design-system';
import { useRouter, useSearchParams, usePathname } from 'next/navigation';
import { useState, useTransition, type ReactNode } from 'react';
import { useHydrated } from '@democracycraft/design-system/use-hydrated';

interface ToolbarProps {
  searchPlaceholder?: string;
  filters?: ReactNode;
  right?: ReactNode;
  filterLabels?: Record<string, string>;
}

export function Toolbar({ searchPlaceholder, filters, right, filterLabels = {} }: ToolbarProps) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const urlQ = params.get('q') ?? '';
  const [input, setInput] = useState(urlQ);
  const [syncedQ, setSyncedQ] = useState(urlQ);
  const [pending, startTransition] = useTransition();

  // Resync the box when the URL `q` changes externally (back/forward nav, a
  // HeaderSearch redirect) so the visible text never drifts from the URL.
  // Adjusted during render rather than in an effect, so there's no frame with
  // the stale text.
  if (syncedQ !== urlQ) {
    setSyncedQ(urlQ);
    setInput(urlQ);
  }

  function pushSearch(next: string) {
    const sp = new URLSearchParams(params);
    if (next) sp.set('q', next);
    else sp.delete('q');
    sp.delete('page'); // reset to page 1 on search change
    startTransition(() => {
      router.replace(`${pathname}?${sp.toString()}`);
    });
  }

  const labels = { q: 'Search', ...filterLabels };
  const active = Object.entries(labels).filter(([key]) => params.get(key));

  function removeFilters(keys: string[]) {
    const sp = new URLSearchParams(params);
    keys.forEach(key => sp.delete(key));
    sp.delete('page');
    if (keys.includes('q')) setInput('');
    startTransition(() => router.replace(`${pathname}?${sp.toString()}`));
  }

  return <div className="results-toolbar" aria-busy={pending}>
    <ToolbarLayout filters={filters} actions={right} search={
      <div className="toolbar-search-group">
        <label className="search-field">
          <span className="field-label">Search</span>
          <Input
            type="search"
            aria-label={searchPlaceholder ?? 'Search results'}
            className="toolbar-search"
            value={input}
            placeholder={searchPlaceholder ?? 'Search…'}
            onChange={event => setInput(event.target.value)}
            onKeyDown={event => { if (event.key === 'Enter') pushSearch(input); }}
            onBlur={event => {
              if (event.relatedTarget instanceof Element && event.relatedTarget.closest('[data-search-action]')) return;
              if (input !== (params.get('q') ?? '')) pushSearch(input);
            }}
          />
        </label>
        <Button disabled={pending} onMouseDown={event => event.preventDefault()} onClick={() => pushSearch(input)}>Search</Button>
      </div>
    } />
    {active.length > 0 && <div className="filter-summary" aria-label="Active filters">
      <span className="field-label">Filtered by</span>
      {active.map(([key, label]) => <button type="button" className="filter-chip" data-search-action key={key} disabled={pending} aria-label={`Remove ${label}: ${params.get(key)} filter`} onClick={() => removeFilters([key])}>
        <span>{label}: <strong>{params.get(key)}</strong></span><span aria-hidden>×</span>
      </button>)}
      <button type="button" className="filter-reset" data-search-action disabled={pending} onClick={() => removeFilters(Object.keys(labels))}>Clear filters</button>
    </div>}
    <span className="results-status" role="status">{pending ? 'Updating results…' : ''}</span>
  </div>;
}

export function FilterSelect({
  paramKey,
  ariaLabel,
  options,
}: {
  paramKey: string;
  ariaLabel: string;
  options: { value: string; label: string }[];
}) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const value = params.get(paramKey) ?? options[0]?.value ?? '';
  const ready = useHydrated();
  const [pending, startTransition] = useTransition();

  function onChange(next: string) {
    const sp = new URLSearchParams(params);
    if (next) sp.set(paramKey, next);
    else sp.delete(paramKey);
    sp.delete('page');
    startTransition(() => {
      router.replace(`${pathname}?${sp.toString()}`);
    });
  }

  return (
    <label className="filter-field"><span className="field-label">{ariaLabel}</span>
    <Select disabled={!ready || pending} aria-busy={pending} aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} className="filter-select">
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </Select></label>
  );
}

/** A URL-param-driven text/number/date filter input (commits on Enter/blur). */
export function FilterInput({
  paramKey,
  ariaLabel,
  placeholder,
  type = 'text',
  width,
}: {
  paramKey: string;
  ariaLabel: string;
  placeholder?: string;
  type?: 'text' | 'number' | 'date';
  width?: number;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const urlV = params.get(paramKey) ?? '';
  const [val, setVal] = useState(urlV);
  const [syncedV, setSyncedV] = useState(urlV);
  const [, startTransition] = useTransition();

  if (syncedV !== urlV) {
    setSyncedV(urlV);
    setVal(urlV);
  }

  function commit(next: string) {
    if (next === (params.get(paramKey) ?? '')) return;
    const sp = new URLSearchParams(params);
    if (next) sp.set(paramKey, next);
    else sp.delete(paramKey);
    sp.delete('page');
    startTransition(() => router.replace(`${pathname}?${sp.toString()}`));
  }

  return (
    <Input
      type={type}
      aria-label={ariaLabel}
      className="filter-select"
      placeholder={placeholder}
      value={val}
      style={width ? { width } : undefined}
      onChange={(e) => setVal(e.target.value)}
      onKeyDown={(e) => { if (e.key === 'Enter') commit(val); }}
      onBlur={() => commit(val)}
    />
  );
}
