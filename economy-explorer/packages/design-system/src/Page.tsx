import type { ComponentProps, ReactNode } from 'react';
import { cx } from './cx';
import { InfoTip } from './InfoTip';
import { MiniSpark } from './MiniSpark';
import { Delta, type DeltaProps } from './Numbers';

export interface PageHeaderProps {
  title: ReactNode;
  /** One sentence under the title. */
  subtitle?: ReactNode | undefined;
  /** Small line above the title, e.g. an EntityBadge and an id. */
  eyebrow?: ReactNode | undefined;
  /** A PlayerHead or item icon beside the title. */
  avatar?: ReactNode | undefined;
  /** Facts under the subtitle (owner, created date…), separated by the host. */
  meta?: ReactNode | undefined;
  /** Buttons or an ExportMenu, right-aligned. */
  actions?: ReactNode | undefined;
  /** A Tabs element; rendered on a hairline under the header. */
  tabs?: ReactNode | undefined;
}

/** The single h1 of a page. Replaces PageHeading and raw `.page-heading`. */
export function PageHeader({ title, subtitle, eyebrow, avatar, meta, actions, tabs }: PageHeaderProps) {
  return <header className={cx('page-header', tabs != null && 'has-tabs')}>
    <div className="page-header-main">
      {avatar != null && <div className="page-header-avatar">{avatar}</div>}
      <div className="page-header-text">
        {eyebrow != null && <div className="page-header-eyebrow">{eyebrow}</div>}
        <h1 className="page-header-title">{title}</h1>
        {subtitle != null && <p className="page-header-subtitle">{subtitle}</p>}
        {meta != null && <div className="page-header-meta">{meta}</div>}
      </div>
      {actions != null && <div className="page-header-actions">{actions}</div>}
    </div>
    {tabs != null && <div className="page-header-tabs">{tabs}</div>}
  </header>;
}

export interface SectionProps extends Omit<ComponentProps<'section'>, 'title'> {
  title?: ReactNode | undefined;
  description?: ReactNode | undefined;
  /** Controls for this section only (a Segmented, a link to the full list). */
  actions?: ReactNode | undefined;
  /** h2 by default; h3 for a section nested inside another. */
  level?: 2 | 3 | undefined;
  /** A hairline above the section, for long pages with many peers. */
  divider?: boolean | undefined;
  /** Put the content in a bordered unit. Use for things that really are one unit (a chart, a table). */
  boxed?: boolean | undefined;
}

/**
 * A titled part of a page with a real heading. Replaces `card-title` divs.
 * Sections are separated by whitespace (and optionally a hairline), not boxes.
 */
export function Section({ title, description, actions, level = 2, divider = false, boxed = false, className, id, children, ...rest }: SectionProps) {
  const H = level === 3 ? 'h3' : 'h2';
  const headingId = id ? `${id}-title` : undefined;
  const hasHeader = title != null || description != null || actions != null;
  return <section id={id} aria-labelledby={title != null ? headingId : undefined} className={cx('section', `section-l${level}`, divider && 'has-divider', className)} {...rest}>
    {hasHeader && <div className="section-header">
      <div className="section-header-text">
        {title != null && <H id={headingId} className="section-title">{title}</H>}
        {description != null && <p className="section-description">{description}</p>}
      </div>
      {actions != null && <div className="section-actions">{actions}</div>}
    </div>}
    {boxed ? <div className="section-box">{children}</div> : children}
  </section>;
}

/** A row of metrics read as one summary: one hairline panel, dividers between figures. */
export function MetricRow({ label, children, className }: { label?: string; children: ReactNode; className?: string }) {
  return <div className={cx('metric-row', className)} role="group" aria-label={label}>{children}</div>;
}

export interface MetricCardProps {
  label: ReactNode;
  /** A <Money>, <Num> or <Pct>. */
  value: ReactNode;
  /** Change vs the previous period as a fraction; null = no previous period. */
  delta?: number | null | undefined;
  /** e.g. "vs previous 30 days". */
  deltaLabel?: string | undefined;
  /** inverse when a fall is good (fines, costs). */
  deltaTone?: DeltaProps['tone'] | undefined;
  /** Explanation behind an InfoTip next to the label. */
  info?: ReactNode | undefined;
  /** Server-rendered sparkline values (oldest first). */
  spark?: number[] | undefined;
  /** One short line of context under the value. */
  meta?: ReactNode | undefined;
}

/** One figure: label, large tabular value, delta and context. Replaces StatCard and `.kpi`. */
export function MetricCard({ label, value, delta, deltaLabel, deltaTone, info, spark, meta }: MetricCardProps) {
  return <div className="metric">
    <div className="metric-label">{label}{info != null && <InfoTip {...(typeof label === 'string' ? { label: `About ${label}` } : {})}>{info}</InfoTip>}</div>
    <div className="metric-value-row">
      <div className="metric-value">{value}</div>
      {spark && spark.length > 1 && <MiniSpark values={spark} width={72} height={24} fill={false} />}
    </div>
    {(delta !== undefined || deltaLabel) && <div className="metric-delta"><Delta value={delta} label={deltaLabel} tone={deltaTone} /></div>}
    {meta != null && <div className="metric-meta">{meta}</div>}
  </div>;
}

const TYPE_LABELS: Record<string, string> = { PERSONAL: 'Personal', BUSINESS: 'Business', GOVERNMENT: 'Government', SYSTEM: 'System' };

/** Account or entity type as a small dot and plain text. Not a chip. */
export function EntityBadge({ type, label }: { type: string; label?: ReactNode }) {
  return <span className="entity-badge" data-type={type}>
    <span className="entity-dot" aria-hidden="true" />{label ?? TYPE_LABELS[type] ?? type.charAt(0) + type.slice(1).toLowerCase()}
  </span>;
}

/** A quiet neutral tag for states such as "Archived" or "Exempt". */
export function Tag({ tone = 'neutral', children }: { tone?: 'neutral' | 'pos' | 'neg' | 'warn' | 'accent'; children: ReactNode }) {
  return <span className="tag" data-tone={tone}>{children}</span>;
}

/** Label / value pairs (account facts, transaction details). */
export function DescriptionList({ items, columns = 1 }: { items: { label: ReactNode; value: ReactNode }[]; columns?: 1 | 2 }) {
  return <dl className={cx('dlist', columns === 2 && 'dlist-2')}>
    {items.map((item, i) => <div key={i} className="dlist-row"><dt>{item.label}</dt><dd>{item.value}</dd></div>)}
  </dl>;
}
