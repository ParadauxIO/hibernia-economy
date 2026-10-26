// `@democracycraft/design-system/table`: the table and filter-chip system (spec §5.5).
// DataTable and TableEmpty are server-safe; TablePopover is a client component.
// Importing anything here brings in table.css.
export { DataTable, TableEmpty, columnOptions } from './DataTable';
export type { Column, ColumnOption, DataTableProps, Density, LinkComponent, SortDirection } from './DataTable';
export { TablePager } from './Pager';
export { TablePopover } from './Popover';
export type { TablePopoverProps } from './Popover';
