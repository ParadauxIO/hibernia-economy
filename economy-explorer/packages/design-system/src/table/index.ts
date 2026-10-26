// `@democracycraft/design-system/table`: the table system (spec §5.5). Server-safe.
// Filter popovers use the shared base in `@democracycraft/design-system/popover`.
// Importing anything here brings in table.css.
export { DataTable, TableEmpty, columnOptions } from './DataTable';
export type { Column, ColumnOption, DataTableProps, Density, LinkComponent, SortDirection } from './DataTable';
export { TablePager } from './Pager';
