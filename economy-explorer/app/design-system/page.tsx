import Link from 'next/link';
import type { Metadata } from 'next';
import { Badge, Button, Card, ChartCard, EmptyState, InfoTip, MiniSpark, PageHeading, SectionNav, SkeletonLine, SkeletonTable, StatCard, TableWrap, WindowTabs } from '@democracycraft/design-system';
import { BackLink, Pagination } from '@democracycraft/design-system/next';
import { FilterSelect, Toolbar } from '@/components/Toolbar';
import { ThemePreview } from './theme-preview';

export const metadata: Metadata = { title: 'Design system', robots: { index: false, follow: false } };

const examples = [
  { name: 'Example community shop', type: 'BUSINESS', balance: '$12,450.00' },
  { name: 'Example player', type: 'PERSONAL', balance: '$850.00' },
  { name: 'Example building company', type: 'BUSINESS', balance: '$7,200.00' },
  { name: 'Example savings', type: 'PERSONAL', balance: '$1,520.00' },
];

/** Real package components with fictional fixtures; no database or privileged data. */
export default async function DesignSystemPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const raw = await searchParams;
  const q = typeof raw.q === 'string' ? raw.q : '';
  const type = typeof raw.type === 'string' ? raw.type : '';
  const days = raw.days === '7' ? 7 : 30;
  const filtered = examples.filter(row => row.name.toLowerCase().includes(q.toLowerCase()) && (!type || row.type === type));
  const totalPages = Math.max(1, Math.ceil(filtered.length / 2));
  const page = raw.page === '2' && totalPages > 1 ? 2 : 1;
  const params = { q, type, days: String(days) };
  return <>
    <PageHeading title="Design system"><ThemePreview /></PageHeading>
    <p className="page-intro">Shared foundations and components for the Economy Explorer. All values below are fictional examples.</p>
    <Card>
      <h2 className="card-title">Foundations</h2>
      <div className="ds-swatches">
        {['bg', 'bg-soft', 'bg-card', 'bg-elev', 'accent', 'good', 'warn', 'bad'].map(token => <div key={token}>
          <div className="ds-swatch" style={{ background: `var(--${token})` }} />
          <code>--{token}</code>
        </div>)}
      </div>
      <p>Body text · <span className="muted">Secondary metadata</span> · <span className="mono">Tabular values: 12,345.67</span></p>
    </Card>
    <div className="page-heading"><h2>Economy overview</h2>
      <WindowTabs basePath="/design-system" days={days} params={{ q, type }} />
      <SectionNav label="Preview sections"><a href="#metrics" className="active" aria-current="location">Overview</a><a href="#directory">Directory</a><a href="#states">States</a></SectionNav>
    </div>
    <div className="kpi-grid" id="metrics">
      <StatCard label="Personal supply" value="$1.24M" meta="Current player-held balances" />
      <StatCard label={`Active players / ${days}d`} value="842" meta="32 first seen in this period" />
      <StatCard label={`Transactions / ${days}d`} value="18,420" meta="+8.2% vs previous period" />
      <StatCard label="Market volume" value="$284K" meta="Historical trading activity" />
    </div>
    <div className="charts-grid">
      <ChartCard><h2 className="chart-title">Chart surface</h2><p className="chart-subtitle">A decorative trend with its meaning available in text.</p><MiniSpark values={[12, 18, 16, 24, 21, 32, 36]} width={240} height={80} /><p>Example volume rose from 12 to 36 over seven days.</p></ChartCard>
      <Card><h2 className="card-title">Actions and context</h2><p>Current holdings<InfoTip>Balances describe current holdings; a date window applies to activity.</InfoTip></p><div className="toolbar"><Button variant="primary">Primary action</Button><Button>Secondary action</Button><Button disabled>Unavailable</Button></div><BackLink href="#directory" label="Back to results" /></Card>
    </div>
    <Card id="directory">
      <h2 className="card-title">Directory</h2>
      <p>Search, change account type, and paginate to exercise the same URL behavior as the explorer.</p>
      <Toolbar searchPlaceholder="Search example accounts" filters={<FilterSelect paramKey="type" ariaLabel="Example account type" options={[{ value: '', label: 'All types' }, { value: 'PERSONAL', label: 'Personal' }, { value: 'BUSINESS', label: 'Business' }]} />} right={<Link href="/design-system#directory" className="btn">Reset filters</Link>} />
      {filtered.length ? <TableWrap label="Example accounts"><table className="data-table"><thead><tr><th scope="col">Account</th><th scope="col">Type</th><th scope="col" className="amount">Balance</th></tr></thead><tbody>{filtered.slice((page - 1) * 2, page * 2).map(row => <tr key={row.name}><td>{row.name}</td><td><Badge className={`badge-${row.type}`}>{row.type}</Badge></td><td className="amount">{row.balance}</td></tr>)}</tbody></table></TableWrap> : <EmptyState><p>No example accounts match these filters.</p><Link href="/design-system#directory">Clear filters</Link></EmptyState>}
      <Pagination page={page} totalPages={totalPages} totalItems={filtered.length} basePath="/design-system" searchParams={params} />
    </Card>
    <Card id="states"><h2 className="card-title">Loading and empty states</h2><div className="stack-sm"><div role="status"><span className="sr-only">Loading example results</span><SkeletonLine w="40%" /></div><SkeletonTable rows={2} cols={3} /><EmptyState><p>No activity in this period.</p><a href="/design-system?days=30">View the last 30 days</a></EmptyState></div></Card>
  </>;
}
