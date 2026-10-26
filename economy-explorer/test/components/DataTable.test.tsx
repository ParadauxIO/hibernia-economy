import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DataTable, TableEmpty, TablePager, columnOptions, type Column } from '@democracycraft/design-system/table';

interface Row { id: number; name: string; type: string; balance: string }

const columns: Column<Row>[] = [
  { key: 'name', header: 'Account', sort: 'name', cell: (r) => r.name },
  { key: 'type', header: 'Type', secondary: true, optional: true, cell: (r) => r.type },
  { key: 'balance', header: 'Balance', sort: 'balance', numeric: true, cell: (r) => r.balance, footer: 'Σ 3.00' },
];
const rows: Row[] = [
  { id: 1, name: 'Alice', type: 'Personal', balance: '1.00' },
  { id: 2, name: 'Acme', type: 'Business', balance: '2.00' },
];
const sortHref = (field: string, dir: string) => `/accounts?sort=${dir === 'desc' ? '-' : ''}${field}`;

describe('DataTable', () => {
  it('marks only the sorted column with aria-sort and links headers to the next order', () => {
    render(<DataTable id="t" label="Accounts" columns={columns} rows={rows} rowKey={(r) => r.id} sort={{ field: 'balance', dir: 'desc' }} sortHref={sortHref} />);
    const headers = screen.getAllByRole('columnheader');
    expect(headers.map((h) => h.getAttribute('aria-sort'))).toEqual([null, null, 'descending']);
    // The sorted column flips; an unsorted text column starts ascending.
    expect(within(headers[2]!).getByRole('link')).toHaveAttribute('href', '/accounts?sort=balance');
    expect(within(headers[0]!).getByRole('link')).toHaveAttribute('href', '/accounts?sort=name');
    expect(within(headers[0]!).getByRole('link')).toHaveAccessibleName('Account, sort ascending');
    // Unsortable headers are plain text.
    expect(within(headers[1]!).queryByRole('link')).toBeNull();
  });

  it('reports ascending sorts and starts numeric columns descending', () => {
    render(<DataTable id="t" label="Accounts" columns={columns} rows={rows} rowKey={(r) => r.id} sort={{ field: 'name', dir: 'asc' }} sortHref={sortHref} />);
    const headers = screen.getAllByRole('columnheader');
    expect(headers[0]).toHaveAttribute('aria-sort', 'ascending');
    expect(within(headers[2]!).getByRole('link')).toHaveAttribute('href', '/accounts?sort=-balance');
  });

  it('renders row links as real anchors and folds secondary columns into details', () => {
    render(<DataTable id="t" label="Accounts" columns={columns} rows={rows} rowKey={(r) => r.id} rowHref={(r) => `/accounts/${r.id}`} />);
    const link = screen.getByRole('link', { name: 'Alice' });
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', '/accounts/1');
    const firstRow = screen.getAllByRole('row')[1]!;
    const details = firstRow.querySelector('details.dt-more')!;
    expect(within(details as HTMLElement).getByText('Type')).toBeInTheDocument();
    expect(within(details as HTMLElement).getByText('Personal')).toBeInTheDocument();
    expect(firstRow.querySelectorAll('[data-col="type"]')).toHaveLength(2); // the cell and its phone copy
  });

  it('is a named, focusable scroll region with a caption, numeric alignment and a totals footer', () => {
    const { container } = render(<DataTable id="t" label="Accounts" columns={columns} rows={rows} rowKey={(r) => r.id} density="compact" />);
    const region = screen.getByRole('region', { name: 'Accounts' });
    expect(region).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('table', { name: 'Accounts' })).toHaveClass('dt--compact');
    expect(container.querySelector('tbody td[data-col="balance"]')).toHaveClass('dt-num');
    expect(container.querySelector('tfoot')).toHaveTextContent('Σ 3.00');
  });

  it('shows one empty row spanning the table, naming the active filters', () => {
    render(
      <DataTable
        id="t" label="Accounts" columns={columns} rows={[]} rowKey={(r) => r.id}
        empty={<TableEmpty noun="accounts" filters={[{ text: 'Type: Business', removeHref: '/a?f=b' }, { text: 'Balance > $10k', removeHref: '/a?f=t' }]} clearHref="/a" />}
      />,
    );
    const cell = screen.getAllByRole('cell')[0]!;
    expect(cell).toHaveAttribute('colspan', '3');
    expect(cell).toHaveTextContent('No accounts match Type: Business and Balance > $10k.');
    // The most recent filter is the one offered for removal.
    expect(screen.getByRole('link', { name: 'Remove Balance > $10k' })).toHaveAttribute('href', '/a?f=t');
    expect(screen.getByRole('link', { name: 'Clear all filters' })).toHaveAttribute('href', '/a');
    expect(document.querySelector('tfoot')).toBeNull();
  });

  it('exposes column options without render functions', () => {
    expect(columnOptions(columns)).toEqual([
      { key: 'name', label: 'Account', optional: false },
      { key: 'type', label: 'Type', optional: true },
      { key: 'balance', label: 'Balance', optional: false },
    ]);
  });
});

describe('TablePager', () => {
  it('links the steps it can take and disables the rest', () => {
    render(<TablePager page={1} totalPages={3} hrefFor={(p) => `/a?page=${p}`} />);
    expect(screen.getByRole('navigation', { name: 'Pagination' })).toHaveTextContent('Page 1 of 3');
    expect(screen.queryByRole('link', { name: 'Previous page' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Next page' })).toHaveAttribute('href', '/a?page=2');
    expect(screen.getByRole('link', { name: 'Last page' })).toHaveAttribute('href', '/a?page=3');
  });

  it('renders nothing for a single page', () => {
    const { container } = render(<TablePager page={1} totalPages={1} hrefFor={() => '/'} />);
    expect(container).toBeEmptyDOMElement();
  });
});
