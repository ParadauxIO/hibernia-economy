// @democracycraft/design-system — root entry. Works from server and client
// components; files that need the browser carry their own 'use client'.
// Next-specific adapters live in `/next`, the floating-panel base in `/popover`.

// Page structure
export { PageHeader, Section, MetricRow, MetricCard, EntityBadge, Tag, DescriptionList } from './Page';
export type { PageHeaderProps, SectionProps, MetricCardProps } from './Page';

// Figures and time
export { Money, Num, Pct, Delta, type MoneyProps, type NumProps, type PctProps, type DeltaProps, type Tone } from './Numbers';
export { When, type WhenProps } from './When';
export { formatNum, formatPct, formatWhen, formatUtc, compactMagnitude, MINUS } from './format';
export type { SignDisplay } from './format';

// Entities
export { PlayerHead, avatarSize, type PlayerHeadProps } from './PlayerHead';
export type { EntityPreviewData } from './EntityPreview';

// Controls
export { Button, Input, Select, ToolbarLayout } from './Controls';
export { Segmented, type SegmentedProps, type SegmentedItem } from './Segmented';
export { ExportMenu, type ExportMenuProps, type ExportHrefs } from './ExportMenu';
export { InfoTip } from './InfoTip';
export { RANGE_PRESETS, RANGE_PARAMS, type RangePreset, type RangeValue } from './range';

// States
export { EmptyState, ErrorState, AccessGate, SkeletonLine, SkeletonText, SkeletonTable, SkeletonMetricRow, SkeletonChart } from './States';
export type { EmptyStateProps, ErrorStateProps, AccessGateProps, AccessGateKind } from './States';

// Data display
export { TableWrap } from './TableWrap';
export { MiniSpark } from './MiniSpark';

// Floating panels (also at `/popover`)
export { Popover, MenuItem, MenuGroup, HoverCard, Dialog } from './popover';

// Legacy (existing pages; replace when a page is rebuilt)
export { WindowTabs } from './WindowTabs';
export { Badge, Card, ChartCard, PageHeading, StatCard, SectionNav } from './Layout';
