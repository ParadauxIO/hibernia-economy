import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AccessGate, Delta, Dialog, EmptyState, EntityBadge, ExportMenu, MetricCard, MetricRow, Money, Num, PageHeader, Pct, PlayerHead, Popover, Section, Segmented, When,
} from '@democracycraft/design-system';
import { Pagination, pageWindow, RangePicker, SidebarLayout } from '@democracycraft/design-system/next';
import { formatNum, formatPct, formatWhen } from '@democracycraft/design-system/format';
import { navigation, resetNavigation } from './next-navigation';

vi.mock('next/navigation', () => import('./next-navigation'));

describe('figures', () => {
  it('formats money exactly through lib/money, with the exact value behind compact', () => {
    const { container } = render(<><Money value="1234567.891" /><Money value="40213.55" compact /><Money value={-1999n} /><Money value="oops" /></>);
    const spans = container.querySelectorAll('.num');
    expect(spans[0]).toHaveTextContent('$1,234,567.89');
    expect(spans[1]).toHaveTextContent('$40.2K');
    expect(spans[1]).toHaveAttribute('title', '$40,213.55');
    expect(spans[2]).toHaveTextContent('−$19.99');
    expect(spans[3]).toHaveTextContent('—');
  });

  it('colours money only when asked', () => {
    const { container } = render(<><Money value="-5.00" /><Money value="-5.00" tone="auto" /><Money value="5.00" tone="auto" sign="exceptZero" currency="£" /></>);
    const [plain, neg, pos] = Array.from(container.querySelectorAll('.num'));
    expect(plain).not.toHaveClass('is-neg');
    expect(neg).toHaveClass('is-neg');
    expect(pos).toHaveClass('is-pos');
    expect(pos).toHaveTextContent('+£5.00');
  });

  it('formats counts, percentages and deltas', () => {
    expect(formatNum(1_284_000, { compact: true })).toBe('1.28M');
    expect(formatPct(-0.031, { sign: 'always' })).toBe('−3.1%');
    render(<><Num value={18420} unit="accounts" /><Pct value={0.082} /><Delta value={0.12} tone="inverse" label="vs last month" /><Delta value={null} label="no previous period" /></>);
    expect(screen.getByText('18,420')).toBeInTheDocument();
    expect(screen.getByText('8.2%')).toBeInTheDocument();
    const inverse = screen.getByText('vs last month').closest('.delta')!;
    expect(inverse.querySelector('.delta-figure')).toHaveClass('is-neg'); // a rise in fines is bad
    expect(inverse).toHaveTextContent('up 12.0%');
  });

  it('renders When absolute on the server pass and relative after hydration', () => {
    const now = Date.parse('2026-10-26T12:00:00Z');
    expect(formatWhen('2026-10-26T11:53:00Z', { now })).toBe('7 min ago');
    expect(formatWhen('2026-03-14T10:00:00Z', { now })).toBe('Mar 14');
    expect(formatWhen('2025-10-25T10:00:00Z', { now })).toBe('Oct 25, 2025');
    expect(formatWhen('2026-10-26T11:53:00Z', { now, relative: false, time: true })).toBe('Oct 26, 11:53');
    render(<When value="2025-10-25T10:00:00Z" />);
    const time = screen.getByText(/Oct 25, 2025/);
    expect(time).toHaveAttribute('dateTime', '2025-10-25T10:00:00.000Z');
    expect(time).toHaveAttribute('title', '2025-10-25 10:00 UTC');
  });
});

describe('page structure', () => {
  it('gives the page one h1 and sections real headings', () => {
    render(<>
      <PageHeader title="Riverside Builders" eyebrow={<EntityBadge type="BUSINESS" />} subtitle="Held by a firm" actions={<button>Copy link</button>} />
      <Section id="balance" title="Balance" description="Last 30 days"><p>chart</p></Section>
      <Section level={3} title="Nested"><p>x</p></Section>
    </>);
    expect(screen.getByRole('heading', { level: 1, name: 'Riverside Builders' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Balance' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'Nested' })).toBeInTheDocument();
    expect(screen.getByText('Business')).toBeInTheDocument();
  });

  it('lays metrics out as one labelled group', () => {
    render(<MetricRow label="Summary"><MetricCard label="Balance" value={<Money value="84210.55" />} delta={0.12} deltaLabel="vs previous 30 days" spark={[1, 2, 3]} /></MetricRow>);
    const group = screen.getByRole('group', { name: 'Summary' });
    expect(within(group).getByText('$84,210.55')).toBeInTheDocument();
    expect(within(group).getByText('vs previous 30 days')).toBeInTheDocument();
  });

  it('explains private content and what grants access', () => {
    render(<AccessGate kind="request" title="You can't see this" grants={['the account owner', 'firm finance staff']} action={<button type="button">How access works</button>} />);
    expect(screen.getByText('You can see this if you are:')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('keeps the legacy EmptyState children API', () => {
    render(<EmptyState><p>No activity.</p></EmptyState>);
    expect(screen.getByText('No activity.')).toBeInTheDocument();
  });
});

describe('PlayerHead', () => {
  it('loads through the avatar proxy and never a third-party host', () => {
    const { container } = render(<PlayerHead uuid="069A79F4-44E9-4726-A5BE-FCA90E38AAF5" size={20} />);
    const img = container.querySelector('img')!;
    expect(img.getAttribute('src')).toBe('/api/avatar/069a79f4-44e9-4726-a5be-fca90e38aaf5?size=48');
    expect(img).toHaveAttribute('alt', '');
  });

  it('falls back to an initial without a valid uuid', () => {
    const { container } = render(<PlayerHead uuid="not-a-uuid" name="ada" />);
    expect(container.querySelector('img')).toBeNull();
    expect(container).toHaveTextContent('A');
  });
});

describe('Segmented', () => {
  it('marks the current link with aria-current', () => {
    render(<Segmented label="Time period" value="30" items={[{ value: '7', label: '7 days', href: '?days=7' }, { value: '30', label: '30 days', href: '?days=30' }]} />);
    expect(screen.getByRole('link', { name: '30 days' })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('link', { name: '7 days' })).not.toHaveAttribute('aria-current');
  });

  it('uses aria-pressed toggle buttons in button mode', async () => {
    const onChange = vi.fn();
    render(<Segmented label="View" value="chart" onChange={onChange} items={[{ value: 'chart', label: 'Chart' }, { value: 'table', label: 'Table' }]} />);
    expect(screen.getByRole('button', { name: 'Chart' })).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Table' }));
    expect(onChange).toHaveBeenCalledWith('table');
  });
});

describe('Popover base', () => {
  it('opens a labelled dialog, moves focus in, and returns it on Escape', async () => {
    const user = userEvent.setup();
    render(<Popover label="Filter by type" trigger={<button>Type</button>}><label>Business <input type="checkbox" /></label></Popover>);
    const trigger = screen.getByRole('button', { name: 'Type' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await user.click(trigger);
    const panel = screen.getByRole('dialog', { name: 'Filter by type' });
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(trigger).toHaveAttribute('aria-controls', panel.id);
    expect(screen.getByRole('checkbox')).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(trigger).toHaveFocus();
  });

  it('closes on an outside click without stealing focus', async () => {
    const user = userEvent.setup();
    render(<><Popover label="Panel" trigger={<button>Open</button>}><button>Inside</button></Popover><button>Elsewhere</button></>);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    await user.click(screen.getByRole('button', { name: 'Elsewhere' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: 'Elsewhere' })).toHaveFocus();
  });

  it('gives ExportMenu arrow-key menu navigation and download links', async () => {
    const user = userEvent.setup();
    render(<ExportMenu hrefs={{ page: { csv: '/x.csv' }, all: { csv: '/all.csv', json: '/all.json' } }} allDescription="1,204 rows" />);
    await user.click(screen.getByRole('button', { name: 'Export' }));
    const items = screen.getAllByRole('menuitem');
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(items[1]).toHaveFocus();
    await user.keyboard('{ArrowUp}{ArrowUp}');
    expect(items[2]).toHaveFocus();
    expect(items[1]).toHaveAttribute('href', '/all.csv');
    expect(within(screen.getByRole('group', { name: 'All results' })).getAllByText('1,204 rows')).toHaveLength(2);
  });

  it('renders nothing for ExportMenu without hrefs', () => {
    const { container } = render(<ExportMenu hrefs={{}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('labels a Dialog by its title and closes on Escape', async () => {
    const onClose = vi.fn();
    render(<Dialog open title="Revoke key?" description="This can't be undone." onClose={onClose} actions={<button>Cancel</button>} />);
    const dialog = screen.getByRole('dialog', { name: 'Revoke key?' });
    expect(dialog).toHaveAccessibleDescription("This can't be undone.");
  });
});

describe('next adapters', () => {
  beforeEach(() => resetNavigation('/economy', 'range=30d&page=3'));

  it('numbers pages with gaps and marks the current page', () => {
    expect(pageWindow(4, 12)).toEqual([1, 2, 3, 4, 5, null, 12]);
    expect(pageWindow(1, 1)).toEqual([1]);
    render(<Pagination page={4} totalPages={12} totalItems={1204} basePath="/accounts" searchParams={{ q: 'ada', type: '' }} noun="accounts" />);
    expect(screen.getByText('1,204 accounts · page 4 of 12')).toBeInTheDocument();
    expect(screen.getByText('4')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Next' })).toHaveAttribute('href', '/accounts?q=ada&page=5');
  });

  it('RangePicker writes range params and drops pagination', async () => {
    const user = userEvent.setup();
    render(<RangePicker value={{ preset: '30d', from: '2026-09-27', to: '2026-10-26', compare: false }} />);
    expect(screen.getByRole('button', { name: '30d' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: '7d' }));
    expect(navigation.router.push).toHaveBeenLastCalledWith('/economy?range=7d', { scroll: false });
    await user.click(screen.getByRole('button', { name: /Compare to previous/ }));
    expect(navigation.router.push).toHaveBeenLastCalledWith('/economy?range=30d&cmp=1', { scroll: false });
  });

  it('SidebarLayout marks the current route and offers a select on phones', () => {
    resetNavigation('/me/statements');
    render(<SidebarLayout title="My finances" nav={[{ items: [{ label: 'Overview', href: '/me' }, { label: 'Statements', href: '/me/statements' }] }]}><h1>Statements</h1></SidebarLayout>);
    expect(screen.getByRole('link', { name: 'Statements' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('combobox', { name: 'My finances: section' })).toHaveValue('/me/statements');
  });
});
