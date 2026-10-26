// Legacy layout components, kept so existing pages render unchanged. New pages
// use PageHeader, Section, MetricRow / MetricCard and EntityBadge (Page.tsx).
import type { ComponentProps, ReactNode } from 'react';

/** @deprecated Use PageHeader. */
export function PageHeading({ title, children }: { title: ReactNode; children?: ReactNode }) {
  return <div className="page-heading"><h1>{title}</h1>{children}</div>;
}

/** A bordered unit. Use for things that really are one unit; sections don't need one. */
export function Card({ className = '', ...props }: ComponentProps<'div'>) {
  return <div className={`card ${className}`.trim()} {...props} />;
}

/** @deprecated Use EntityBadge for types and Tag for states. */
export function Badge({ className = '', ...props }: ComponentProps<'span'>) {
  return <span className={`badge ${className}`.trim()} {...props} />;
}

/** @deprecated Use Section (optionally boxed) around a chart. */
export function ChartCard({ className = '', ...props }: ComponentProps<'div'>) {
  return <div className={`chart-card ${className}`.trim()} {...props} />;
}

/** @deprecated Use MetricCard inside a MetricRow. */
export function StatCard({ label, value, meta, children }: { label: ReactNode; value: ReactNode; meta?: ReactNode; children?: ReactNode }) {
  return <div className="kpi">
    <div className="kpi-label">{label}</div>
    <div className="kpi-value">{value}</div>
    {meta != null && <div className="kpi-meta">{meta}</div>}
    {children}
  </div>;
}

/** Links remain host-owned so routing and permission decisions stay outside the package. */
export function SectionNav({ label = 'Section', children }: { label?: string; children: ReactNode }) {
  return <nav className="section-tabs" aria-label={label}>{children}</nav>;
}
