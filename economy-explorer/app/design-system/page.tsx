import './gallery.css';
import type { Metadata } from 'next';
import Link from 'next/link';
import {
  AccessGate, Delta, DescriptionList, EmptyState, EntityBadge, ErrorState, ExportMenu, InfoTip, MetricCard, MetricRow, MiniSpark, Money, Num,
  PageHeader, Pct, PlayerHead, Section, Segmented, SkeletonChart, SkeletonMetricRow, SkeletonTable, SkeletonText, TableWrap, Tag, When,
} from '@democracycraft/design-system';
import { CursorPager, EntityLink, Pager, RangePicker, Tabs } from '@democracycraft/design-system/next';
import {
  BalanceDistribution, BalanceLine, InOutChart, PriceLine, TaxRevenueTimeline, TimeSeriesChart, VolumeTimeline, Waterfall, WealthDonut,
} from '@/components/charts/AreaTimeline';
import { Candlestick } from '@/components/charts/Candlestick';
import { Heatmap } from '@/components/charts/Heatmap';
import { parseRange } from '@/lib/range';
import { DialogDemo, PopoverDemo, SegmentedButtonsDemo } from './demos';
import * as f from './fixtures';
import { ThemePreview } from './theme-preview';

export const metadata: Metadata = { title: 'Design system', robots: { index: false, follow: false } };

const TYPE_SCALE = [
  ['--text-3xl', 'Page title', '32 / 40'],
  ['--text-2xl', 'Metric value', '24 / 32'],
  ['--text-xl', 'Section heading', '20 / 28'],
  ['--text-lg', 'Card title', '16 / 24'],
  ['--text-md', 'Body text', '14 / 20'],
  ['--text-sm', 'Table body, secondary text', '13 / 18'],
  ['--text-xs', 'Captions and axis labels', '12 / 16'],
] as const;

const SURFACES = ['--bg', '--bg-soft', '--bg-card', '--bg-elev', '--border', '--border-soft'];
const TEXT = ['--fg', '--fg-soft', '--fg-muted', '--fg-faint'];
const MEANING = ['--accent', '--accent-fill', '--pos', '--neg', '--good', '--warn', '--bad'];

/** Every primitive and chart in every state, with fictional fixtures. No database. */
export default async function DesignSystemPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const raw = await searchParams;
  const range = parseRange(raw, { now: new Date('2026-10-26T12:00:00Z') });
  const days = raw.days === '7' ? '7' : '30';
  const page = Math.min(12, Math.max(1, Number(raw.page) || 4));
  // A per-request server render: the "minutes ago" sample is relative to the request.
  // eslint-disable-next-line react-hooks/purity
  const now = Date.now();

  return <>
    <PageHeader
      eyebrow={<>Economy Explorer · <Link href="/design-system/mock-account">Mock account page</Link> · <Link href="/design-system/mock-me">Mock /me overview</Link></>}
      title="Design system"
      subtitle="Tokens, primitives and charts, shown in every state with fictional data. Switch brand and appearance to check all four combinations."
      actions={<ThemePreview />}
      tabs={<Tabs label="Gallery sections" items={[
        { label: 'Foundations', href: '#foundations', current: true },
        { label: 'Figures', href: '#figures' },
        { label: 'Structure', href: '#structure' },
        { label: 'Controls', href: '#controls' },
        { label: 'States', href: '#states' },
        { label: 'Charts', href: '#charts' },
      ]} />}
    />

    <Section id="foundations" title="Foundations" description="One accent per brand. Colour carries meaning only: money direction, status, and chart categories.">
      <div className="ds-grid-2">
        <div className="ds-type">
          {TYPE_SCALE.map(([token, use, size]) => <div key={token} className="ds-type-row">
            <span className="ds-type-sample" style={{ font: `var(--weight-semibold) var(${token}) / var(${token.replace('text', 'leading')}) var(--sans)` }}>{use}</span>
            <code>{token}</code><span className="muted small">{size}</span>
          </div>)}
        </div>
        <div className="ds-swatch-groups">
          {[['Surfaces', SURFACES], ['Text', TEXT], ['Meaning', MEANING]].map(([name, list]) => <div key={name as string}>
            <p className="ds-label">{name}</p>
            <div className="ds-swatches">{(list as string[]).map((t) => <div key={t} className="ds-swatch"><span style={{ background: `var(${t})` }} /><code>{t}</code></div>)}</div>
          </div>)}
          <div>
            <p className="ds-label">Chart categories and sequential scale</p>
            <div className="ds-strip">{Array.from({ length: 10 }, (_, i) => <span key={i} title={`--cat-${i + 1}`} style={{ background: `var(--cat-${i + 1})` }} />)}</div>
            <div className="ds-strip">{Array.from({ length: 6 }, (_, i) => <span key={i} title={`--seq-${i}`} style={{ background: `var(--seq-${i})` }} />)}</div>
          </div>
          <div>
            <p className="ds-label">Spacing (4 px base) and radius</p>
            <div className="ds-spacing">{[1, 2, 3, 4, 5, 6, 8, 10, 12, 16].map((n) => <span key={n} title={`--space-${n}`} style={{ width: `var(--space-${n})` }} />)}</div>
            <div className="ds-radii">{['sm', 'md', 'lg', 'pill'].map((r) => <span key={r} style={{ borderRadius: `var(--radius-${r})` }}><code>{r}</code></span>)}</div>
          </div>
        </div>
      </div>
    </Section>

    <Section id="figures" divider title="Figures" description="Numbers are the heroes: tabular figures, exact money from lib/money, colour only where direction matters.">
      <DescriptionList columns={2} items={[
        { label: 'Money, full', value: <Money value="1234567.891" /> },
        { label: 'Money, compact (exact in tooltip)', value: <Money value="40213.55" compact /> },
        { label: 'Minting account', value: <Money value="-157004112.80" compact /> },
        { label: 'Signed and toned', value: <><Money value="18400.00" sign="exceptZero" tone="auto" />{'  '}<Money value="-312.75" tone="auto" /></> },
        { label: 'Integer cents (bigint)', value: <Money value={1999n} /> },
        { label: 'Missing value', value: <Money value={null} /> },
        { label: 'Num', value: <><Num value={18420} /> · <Num value={1_284_000} compact unit="transactions" /></> },
        { label: 'Pct', value: <><Pct value={0.082} /> · <Pct value={-0.031} sign="always" tone="auto" /></> },
        { label: 'Delta', value: <Delta value={0.082} label="vs previous 30 days" /> },
        { label: 'Delta, inverse (fines)', value: <Delta value={0.12} tone="inverse" label="vs last month" /> },
        { label: 'When, minutes ago', value: <When value={new Date(now - 7 * 60_000)} /> },
        { label: 'When, this year / older', value: <><When value="2026-03-14T10:00:00Z" /> · <When value="2025-10-25T10:00:00Z" time /></> },
      ]} />
    </Section>

    <Section id="structure" divider title="Structure" description="PageHeader → RangePicker → MetricRow → Sections. Sections are separated by whitespace and hairlines; borders only around real units."
      actions={<ExportMenu hrefs={{ page: { csv: '#csv-page', json: '#json-page' }, all: { csv: '#csv-all', json: '#json-all' } }} allDescription="1,204 rows" />}>
      <RangePicker value={range.value} max="2026-10-26" />
      <p className="muted small">Parsed by lib/range: {range.label}, {range.range.from} to {range.range.to}{range.previous ? `, compared with ${range.previous.from} to ${range.previous.to}` : ''}.</p>
      <MetricRow label="Economy summary">
        <MetricCard label="Personal supply" value={<Money value="48210334.10" compact />} info="Current balances held in personal accounts. Not affected by the time range." meta="Current holdings" />
        <MetricCard label="Volume" value={<Money value="1842210.00" compact />} delta={0.082} deltaLabel="vs previous 30 days" spark={f.sparkValues} />
        <MetricCard label="Transactions" value={<Num value={18420} />} delta={-0.031} deltaLabel="vs previous 30 days" />
        <MetricCard label="Active players" value={<Num value={842} />} delta={null} deltaLabel="no previous period" />
      </MetricRow>
      <div className="ds-grid-2">
        <Section level={3} title="Section, level 3" description="Nested sections use h3." actions={<Link href="#structure" className="btn btn-quiet">View all</Link>}>
          <p>Body text sits at 14/20 with a 72-character measure. <span className="muted">Secondary text is muted, never smaller than 12 px.</span></p>
        </Section>
        <Section level={3} title="Boxed section" boxed>
          <p className="ds-flush">Use <code>boxed</code> for one real unit, such as a chart or a short form.</p>
        </Section>
      </div>
      <MetricCard label="A single metric" value={<Money value="84210.55" />} meta="As of 09:12 UTC" />
    </Section>

    <Section id="entities" divider title="Entities" description="Minecraft flavour comes from heads and item icons only. Types are a dot and a word, not a chip.">
      <div className="ds-row ds-wrap">
        {['PERSONAL', 'BUSINESS', 'GOVERNMENT', 'SYSTEM'].map((t) => <EntityBadge key={t} type={t} />)}
        <Tag>Archived</Tag><Tag tone="pos">Exempt</Tag><Tag tone="warn">Overdue</Tag><Tag tone="accent">New</Tag>
        <span className="badge badge-BUSINESS">BUSINESS</span><span className="muted small">(legacy markup, same look)</span>
      </div>
      <div className="ds-row ds-wrap">
        <PlayerHead uuid={f.people.ada.uuid} size={16} /><PlayerHead uuid={f.people.grace.uuid} size={24} /><PlayerHead uuid={f.people.alan.uuid} size={40} />
        <PlayerHead uuid={null} name="Unknown" size={24} /><span className="muted small">heads via /api/avatar, fallback without a UUID</span>
      </div>
      <div className="ds-row ds-wrap">
        <EntityLink kind="player" href="#entities" name={f.people.ada.name} uuid={f.people.ada.uuid} preview="/design-system/preview/player" />
        <EntityLink kind="account" href="#entities" name="Riverside Builders · Operating" accountType="BUSINESS" id={1204} preview="/design-system/preview/account" />
        <EntityLink kind="firm" href="#entities" name="Riverside Builders" preview="/design-system/preview/firm" />
        <EntityLink kind="item" href="#entities" name="Diamond" preview="/design-system/preview/item" />
        <span className="muted small">hover or focus for the lazy preview card</span>
      </div>
    </Section>

    <Section id="controls" divider title="Controls">
      <div className="ds-row ds-wrap">
        <button type="button" className="btn btn-primary">Primary</button>
        <button type="button" className="btn">Secondary</button>
        <button type="button" className="btn btn-quiet">Quiet</button>
        <button type="button" className="btn" disabled>Unavailable</button>
        <InfoTip>Help text opens on hover, focus or tap, and Escape closes it.</InfoTip>
      </div>
      <div className="ds-row ds-wrap">
        <label className="field"><span className="field-label">Search</span><input type="search" className="input" placeholder="Accounts, players, firms" /></label>
        <label className="field"><span className="field-label">Type</span><select className="select" defaultValue="BUSINESS"><option value="">Any</option><option value="BUSINESS">Business</option></select></label>
        <label className="field"><span className="field-label">From</span><input type="date" className="input" defaultValue="2026-10-01" /></label>
      </div>
      <div className="ds-row ds-wrap">
        <Segmented label="Time period" value={days} items={[{ value: '7', label: '7 days', href: '/design-system?days=7#controls' }, { value: '30', label: '30 days', href: '/design-system?days=30#controls' }]} />
        <SegmentedButtonsDemo />
      </div>
      <div className="ds-row ds-wrap"><PopoverDemo /><DialogDemo /><ExportMenu hrefs={{ page: { csv: '#csv' } }} /></div>
      <TableWrap label="Example accounts">
        <table className="data-table">
          <thead><tr><th scope="col">Account</th><th scope="col">Type</th><th scope="col" className="amount">Balance</th><th scope="col" className="amount">Last activity</th></tr></thead>
          <tbody>
            {f.counterparties.map((c) => <tr key={c.id}>
              <td><EntityLink kind="account" href="#controls" name={c.name} id={c.id} /></td>
              <td><EntityBadge type={c.type} /></td>
              <td className="amount"><Money value={c.total} /></td>
              <td className="amount"><When value="2026-10-24T18:02:00Z" /></td>
            </tr>)}
          </tbody>
        </table>
      </TableWrap>
      <TableWrap label="Empty example">
        <table className="data-table"><thead><tr><th scope="col">Account</th><th scope="col" className="amount">Balance</th></tr></thead>
          <tbody><tr><td colSpan={2} className="empty">No accounts match these filters.</td></tr></tbody></table>
      </TableWrap>
      <Pager page={page} totalPages={12} totalItems={1204} basePath="/design-system" searchParams={{ days }} noun="accounts" />
      <CursorPager newerHref="/design-system?cursor=newer#controls" olderHref="/design-system?cursor=older#controls" summary="1,000+ transactions"
        jump={{ basePath: '/design-system', param: 'at', searchParams: { days }, max: '2026-10-26' }} />
    </Section>

    <Section id="states" divider title="States" description="Empty, failed and private are different situations and say so.">
      <div className="ds-grid-2">
        <EmptyState title="No transactions in this period" action={<Link href="/design-system?range=90d#states" className="btn">Show 90 days</Link>}>
          <p>Nothing settled between Sep 27 and Oct 26. Try a longer range.</p>
        </EmptyState>
        <ErrorState action={<button type="button" className="btn">Try again</button>} reference="8f3a91c2">
          <p>The balance history took too long to load. Your other data is unaffected.</p>
        </ErrorState>
        <AccessGate kind="login" title="This account's history is private" description="Owners, members and firm finance staff can see it." action={<button type="button" className="btn btn-primary">Log in to see your own account</button>} />
        <AccessGate kind="request" title="You can't see this account's history" description="Your login works, but you have no relationship with this account."
          grants={['the account owner or a member', 'finance staff of Riverside Builders', 'in a government group with access to this unit']} action={<Link href="/docs" className="btn">How access works</Link>} />
        <AccessGate kind="link" inline title="Link your Minecraft account" description="Run /explorer link in game to see your own accounts." action={<Link href="/link" className="btn">Link account</Link>} />
        <AccessGate kind="private" inline title="Sales attribution is private" description="Only the shop owner sees who bought from them." />
      </div>
      <div className="ds-grid-2">
        <div role="status" aria-label="Loading example"><SkeletonText lines={3} /></div>
        <SkeletonTable rows={3} cols={3} />
      </div>
      <SkeletonMetricRow count={4} />
      <SkeletonChart height={160} label="Example chart placeholder" busy={false} />
    </Section>

    <Section id="charts" divider title="Charts" description="Recharts loads only on routes that draw charts, after first paint, behind a skeleton at the final size. Every chart has a summary and a data table.">
      <div className="ds-grid-2">
        <Section level={3} id="chart-volume-outlier" title="One outlier day" description="A $999B day is clamped with a flag; log scale is one click away.">
          <VolumeTimeline data={f.volumeWithOutlier} />
        </Section>
        <Section level={3} id="chart-dual" title="Two axes" description="Each axis is labelled and coloured with its series.">
          <VolumeTimeline data={f.volumeNormal} />
        </Section>
        <Section level={3} id="chart-treasury" title="Negative balance" description="Axis width fits the longest label (−$157M).">
          <BalanceLine data={f.treasuryBalance} />
        </Section>
        <Section level={3} id="chart-player" title="Around $40,000" description="The label production clipped to 0,000.">
          <BalanceLine data={f.playerBalance} />
        </Section>
        <Section level={3} id="chart-inout" title="Money in and out" description="In above the axis, out below.">
          <InOutChart data={f.inOut} />
        </Section>
        <Section level={3} id="chart-price" title="Spiky price over idle days" description="Monotone curves never dip below zero.">
          <PriceLine data={f.priceHistory} />
        </Section>
        <Section level={3} id="chart-donut" title="Donut" description="Shares of positive balances; negatives stay in the table.">
          <WealthDonut data={f.wealthByType} />
        </Section>
        <Section level={3} id="chart-distribution" title="Distribution">
          <BalanceDistribution data={f.balanceBuckets} />
        </Section>
        <Section level={3} id="chart-waterfall" title="Waterfall" description="Opening balance → categories → closing balance. When the changes are small next to the balance, the axis is fitted to the running total and the opening and closing bars are drawn cut.">
          <Waterfall label="October statement" title="Fitted axis" description="A $41K balance moved by a few thousand." opening="41210.30" steps={f.monthCategories} />
          <Waterfall label="First month statement" title="Axis from zero" description="Changes as large as the balance keep a zero baseline." opening="1200.00" steps={f.monthCategories} />
        </Section>
        <Section level={3} id="chart-empty" title="Empty">
          <TimeSeriesChart data={[]} label="Volume by day" series={[{ key: 'v', label: 'Volume' }]} empty="No activity in this period." />
        </Section>
      </div>
      <Section level={3} id="chart-tax" title="Many series" description="Legend items toggle their series.">
        <TaxRevenueTimeline data={f.taxRevenue} />
      </Section>
      <Section level={3} id="chart-heatmap" title="Weekday × hour" description="Fills the width; all 24 hours on every screen.">
        <Heatmap cells={f.weekHours} />
      </Section>
      <Section level={3} id="chart-candles" title="Candlestick" description="Server-rendered SVG with HTML axis labels.">
        <Candlestick candles={f.candles} />
      </Section>
      <Section level={3} title="Inline sparkline" description="Server SVG for tables and metrics. Decorative: the figure next to it carries the meaning.">
        <div className="ds-row"><MiniSpark values={f.sparkValues} width={120} height={28} fill={false} /><span>Volume up <Pct value={0.24} /> over 9 days</span></div>
      </Section>
    </Section>
  </>;
}
