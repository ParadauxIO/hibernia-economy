'use client';

import type { ReactNode } from 'react';
import { cx } from './cx';

export interface SegmentedItem {
  value: string;
  label: ReactNode;
  /** Link mode: each option is a URL (e.g. ?days=7). The current one gets aria-current. */
  href?: string | undefined;
  disabled?: boolean | undefined;
}

export interface SegmentedProps {
  items: SegmentedItem[];
  value: string;
  /** Button mode: called with the chosen value; options get aria-pressed. */
  onChange?: ((value: string) => void) | undefined;
  /** Accessible name of the group, e.g. "Time period". */
  label: string;
  size?: 'sm' | 'md' | undefined;
  className?: string | undefined;
}

/**
 * A small set of mutually exclusive options. With hrefs it renders links (state
 * in the URL, aria-current); with onChange, toggle buttons (aria-pressed).
 * Replaces WindowTabs and the hand-rolled `.window-tabs`.
 */
export function Segmented({ items, value, onChange, label, size = 'sm', className }: SegmentedProps) {
  const links = items.every((item) => item.href !== undefined);
  if (links) {
    return <nav className={cx('segmented', `segmented-${size}`, className)} aria-label={label}>
      {items.map((item) => item.disabled
        ? <span key={item.value} className="segmented-item" aria-disabled="true">{item.label}</span>
        : <a key={item.value} href={item.href} className="segmented-item" aria-current={item.value === value ? 'true' : undefined}>{item.label}</a>)}
    </nav>;
  }
  return <div className={cx('segmented', `segmented-${size}`, className)} role="group" aria-label={label}>
    {items.map((item) => <button key={item.value} type="button" className="segmented-item" aria-pressed={item.value === value} disabled={item.disabled}
      onClick={() => { if (item.value !== value) onChange?.(item.value); }}>{item.label}</button>)}
  </div>;
}
