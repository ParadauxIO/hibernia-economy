import type { ComponentProps, ReactNode } from 'react';

export function PageHeading({ title, children }: { title: ReactNode; children?: ReactNode }) {
  return <div className="page-heading"><h1>{title}</h1>{children}</div>;
}

export function Card({ className = '', ...props }: ComponentProps<'div'>) {
  return <div className={`card ${className}`.trim()} {...props} />;
}

export function Badge({ className = '', ...props }: ComponentProps<'span'>) {
  return <span className={`badge ${className}`.trim()} {...props} />;
}

export function ChartCard({ className = '', ...props }: ComponentProps<'div'>) {
  return <div className={`chart-card ${className}`.trim()} {...props} />;
}

/** Values arrive formatted; units, financial rules and comparisons belong to the host. */
export function StatCard({ label, value, meta, children }: { label: ReactNode; value: ReactNode; meta?: ReactNode; children?: ReactNode }) {
  return <div className="kpi">
    <div className="kpi-label">{label}</div>
    <div className="kpi-value">{value}</div>
    {meta != null && <div className="kpi-meta">{meta}</div>}
    {children}
  </div>;
}

export function EmptyState({ children, ...props }: Omit<ComponentProps<'div'>, 'className'>) {
  return <div className="state-empty" {...props}>{children}</div>;
}

/** Links remain host-owned so routing and permission decisions stay outside the package. */
export function SectionNav({ label = 'Section', children }: { label?: string; children: ReactNode }) {
  return <nav className="section-tabs" aria-label={label}>{children}</nav>;
}
