'use client';
// Toolbar for searchParams-driven SSR pages. Updates the URL on input change;
// the RSC parent re-renders with new data. No client-side data fetch — the
// HTML round-trip is the data fetch.

import { useRouter, useSearchParams, usePathname } from 'next/navigation';
import { useState, useTransition, type ReactNode } from 'react';
import { useHydrated } from '@/components/useHydrated';

interface ToolbarProps {
  searchPlaceholder?: string;
  filters?: ReactNode;
  right?: ReactNode;
}

export function Toolbar({ searchPlaceholder, filters, right }: ToolbarProps) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const urlQ = params.get('q') ?? '';
  const [input, setInput] = useState(urlQ);
  const [syncedQ, setSyncedQ] = useState(urlQ);
  const [, startTransition] = useTransition();

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

  return (
    <div className="toolbar">
      <input
        type="search"
        aria-label={searchPlaceholder ?? 'Search results'}
        className="toolbar-search"
        value={input}
        placeholder={searchPlaceholder ?? 'Search…'}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') pushSearch(input);
        }}
        onBlur={() => {
          if (input !== (params.get('q') ?? '')) pushSearch(input);
        }}
      />
      <div className="toolbar-filters">{filters}</div>
      <div className="toolbar-right">{right}</div>
    </div>
  );
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
    <select disabled={!ready || pending} aria-busy={pending} aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)} className="filter-select">
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
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
    <input
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
