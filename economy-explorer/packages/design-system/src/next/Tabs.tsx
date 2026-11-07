import Link from 'next/link';
import type { Route } from 'next';
import type { ReactNode } from 'react';

export interface TabItem<T extends string = string> {
  label: ReactNode;
  href: Route<T>;
  current?: boolean | undefined;
  /** A count shown after the label, muted. */
  count?: number | string | undefined;
}

/** Route tabs under a PageHeader: links with aria-current, an underline for the current one. */
export function Tabs<T extends string>({ items, label }: { items: TabItem<T>[]; label: string }) {
  return <nav className="tabs" aria-label={label}>
    {items.map((item) => <Link key={item.href} href={item.href} prefetch={false} className="tab" aria-current={item.current ? 'page' : undefined}>
      {item.label}{item.count !== undefined && <span className="tab-count">{item.count}</span>}
    </Link>)}
  </nav>;
}
