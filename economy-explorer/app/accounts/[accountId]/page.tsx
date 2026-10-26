import '@/components/entity/entity.css';
import { Suspense } from 'react';
import { notFound } from 'next/navigation';
import Link from 'next/link';
import {
  AccessGate, EntityBadge, ExportMenu, InfoTip, MetricCard, MetricRow, Money, Num, PageHeader, PlayerHead, Section,
  SkeletonChart, SkeletonTable, Tag,
} from '@democracycraft/design-system';
import { EntityLink, RangePicker } from '@democracycraft/design-system/next';
import { Breadcrumbs } from '@/components/Breadcrumbs';
import { BalanceLine, BarChart, InOutChart } from '@/components/charts/AreaTimeline';
import { ActivityCalendar } from '@/components/charts/ActivityCalendar';
import { AccountTransactions } from '@/components/entity/AccountTransactions';
import { CopyButton } from '@/components/entity/CopyButton';
import { CoverageNote, DateWithYear, TimeUtc } from '@/components/entity/bits';
import { LoginButton } from '@/components/entity/LoginButton';
import { searchCounterpartiesAction } from '@/lib/actions/entity';
import { auditRelationshipRead, auditView } from '@/lib/audit';
import { canViewAccount, findAccount, hasElevatedRead } from '@/lib/auth/access';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { accountHref, firmHref, playerHref, playerName, previewHref } from '@/lib/entityLinks';
import { accountLabel } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import { entityMetadata } from '@/lib/entityMetadata';
import { toNumber, parseMoney } from '@/lib/money';
import { payCommand } from '@/lib/payCommand';
import { parseRange, rangeToParams, type ParsedRange } from '@/lib/range';
import {
  change, getAccountContext, getActivity, getRangeHistory, getTransactionsPage,
  type AccountActivity, type AccountContext,
} from '@/lib/services/account';
import type { ExplorerAccountRow } from '@/lib/sql/ledger';
import { accountTxnsTable, type AccountTxnsState } from '@/lib/tables/accountTxns';
import { foreignParams, parseTableState, serializeTableState } from '@/lib/tables/core';

export const dynamic = 'force-dynamic';

type Params = Promise<{ accountId: string }>;
type SearchParams = Promise<Record<string, string | string[] | undefined>>;

function parseId(raw: string): number | null {
  return /^[1-9]\d{0,9}$/.test(raw) ? Number(raw) : null;
}

const TYPE_EXPLAINER: Record<string, string> = {
  PERSONAL: 'A player’s own wallet. Its balance is public; its history is private to the player and anyone they share the account with.',
  BUSINESS: 'A firm’s money. It belongs to the firm, not to its proprietor: firms can have shareholders, and some hold other players’ deposits. Balance and activity totals are public.',
  GOVERNMENT: 'Public money held by a government. Its balance and activity totals are public; individual transactions are visible to the people who run it.',
  SYSTEM: 'Used by server plugins to create and collect money. Accounts that mint money hold negative balances: that is how a ledger records money created.',
};

const GATE_WHO: Record<string, string> = {
  PERSONAL: 'The account’s owner and anyone they share it with can see its transactions and counterparties.',
  BUSINESS: 'Members of the firm with account access, and the firm’s finance staff, can see its transactions and counterparties.',
  GOVERNMENT: 'The government’s officials with access to this account can see its transactions and counterparties.',
  SYSTEM: 'Server staff can see this account’s transactions.',
};

export async function generateMetadata({ params }: { params: Params }) {
  const { accountId } = await params;
  const id = parseId(accountId);
  const account = id ? await findAccount(id) : null;
  if (!account) return buildMetadata({ title: 'Account not found', description: 'No such account on {server}.', path: `/accounts/${accountId}` });
  const label = accountLabel(account);
  const kind = account.account_type.charAt(0) + account.account_type.slice(1).toLowerCase();
  return entityMetadata({ title: `${label} · ${kind} account #${account.account_id}`, description: `${kind} account #${account.account_id} on {server}: balance, owner and related accounts.`, path: `/accounts/${account.account_id}` }, `${label}, account summary`);
}

/** Spec §7.1: the front door. Public header, metrics and Related rail; history behind the gate. */
export default async function AccountPage({ params, searchParams }: { params: Params; searchParams: SearchParams }) {
  const { accountId } = await params;
  const id = parseId(accountId);
  if (id === null) notFound();
  const [viewer, account, sp] = await Promise.all([getViewer(), findAccount(id), searchParams]);
  if (!account) notFound();

  const canSeeHistory = await canViewAccount(id, viewer);
  const isOwner = !viewer.anon && !!viewer.minecraftUuid && account.owner_uuid === viewer.minecraftUuid;
  if (canSeeHistory && !isOwner) {
    if (hasElevatedRead(viewer)) {
      await auditView(viewer, { path: `/accounts/${id}`, targetType: 'account', targetId: String(id) });
    } else {
      // Reads of GOVERNMENT and BUSINESS history through a relationship (spec §12).
      // It skips other account types and global readers itself, throttles, and never throws.
      await auditRelationshipRead(viewer, { accountId: id, accountType: account.account_type }, `/accounts/${id}`);
    }
  }

  const publicTotals = account.account_type === 'GOVERNMENT' || account.account_type === 'BUSINESS';
  const [context, activity] = await Promise.all([
    getAccountContext(account),
    publicTotals || canSeeHistory ? getActivity([id]) : Promise.resolve(null),
  ]);
  const firm = context.firm;
  const label = accountLabel(account);
  const path = accountHref(id);
  const pay = payCommand({
    accountId: id, accountType: account.account_type, accountName: account.display_name, ownerName: account.owner_name,
    firm: firm ? { id: firm.firm_id, name: firm.display_name } : null,
  });

  const range = parseRange(sp, { defaultPreset: '30d' });
  const state = parseTableState(sp, accountTxnsTable);
  const keep = foreignParams(sp, Object.keys(sp));

  const crumbs = firm
    ? [{ label: firm.display_name, href: firmHref(firm.display_name) }, { label }]
    : [{ label: 'Accounts', href: '/accounts' }, { label }];

  return <>
    <Breadcrumbs section={firm ? '/firms' : '/accounts'} items={firm ? crumbs : crumbs.slice(1)} />
    <PageHeader
      eyebrow={<><EntityBadge type={account.account_type} /><span className="num">#{account.account_id}</span>
        {account.archived ? <Tag>Closed</Tag> : null}{account.requires_authorization ? <Tag>Needs authorisation</Tag> : null}</>}
      avatar={account.account_type === 'PERSONAL' && account.owner_uuid ? <PlayerHead uuid={account.owner_uuid} name={account.owner_name} size={48} /> : undefined}
      title={label}
      subtitle={<Holder account={account} context={context} />}
      meta={<>
        <span className="entity-meta-item">Opened <DateWithYear value={account.created_at} /></span>
        {firm && <span className="entity-meta-item">Firm of {firm.staff_count.toLocaleString('en-US')} staff · {firm.account_count.toLocaleString('en-US')} {firm.account_count === 1 ? 'account' : 'accounts'}</span>}
        <span className="entity-meta-item">{account.account_type.charAt(0) + account.account_type.slice(1).toLowerCase()} account
          <InfoTip label="About this kind of account">{TYPE_EXPLAINER[account.account_type] ?? ''}</InfoTip></span>
      </>}
      actions={<div className="entity-actions">
        <CopyButton value={path} label="Copy link" />
        {pay && <CopyButton value={pay} label="Copy /pay command" className="btn" title={pay} />}
        {canSeeHistory && <ExportMenu hrefs={{
          page: { csv: `${path}/export?${exportQuery(state, 'csv', sp)}`, json: `${path}/export?${exportQuery(state, 'json', sp)}` },
          // TODO(POWER PAR-540): `all` from the export registry's account-transactions dataset.
        }} />}
      </div>}
    />

    {canSeeHistory && <RangePicker value={range.value} max={range.range.to} resetParams={['cursor']} />}

    <Metrics account={account} activity={activity} />

    <div className="entity-layout">
      <div className="entity-main">
        {canSeeHistory
          ? <>
            <Suspense fallback={<HistorySkeleton />}>
              <RangeSections accountId={id} range={range} />
            </Suspense>
            {activity && <Section title="Activity" description="Transactions per day, last 365 days" divider>
              <CoverageNote coverage={activity.coverage} />
              {activity.days.length > 0 ? <ActivityCalendar data={activity.days} label="transactions" /> : <p className="muted">No transactions in the last year.</p>}
            </Section>}
            <Section id="transactions" title="Transactions" divider>
              <Suspense fallback={<SkeletonTable rows={10} cols={5} />}>
                <Transactions account={account} firmId={firm?.firm_id ?? null} state={state} keep={keep} />
              </Suspense>
            </Section>
          </>
          : <Gate account={account} viewer={viewer} returnTo={path} firmName={firm?.display_name ?? null} />}
      </div>
      <aside className="entity-rail" aria-label="Related">
        <Related account={account} context={context} />
        {canSeeHistory && <Suspense fallback={null}><Counterparties accountId={id} range={range} /></Suspense>}
      </aside>
    </div>
  </>;
}

function exportQuery(state: AccountTxnsState, format: 'csv' | 'json', sp: Record<string, string | string[] | undefined>): string {
  const qs = serializeTableState(state, accountTxnsTable);
  return [qs, `format=${format}`, ...Object.entries(rangeToParams(parseRange(sp))).map(([k, v]) => `${k}=${encodeURIComponent(v)}`)].filter(Boolean).join('&');
}

/** Who holds the account. On business pages the firm comes first; the proprietor is a role, never the owner of the funds. */
function Holder({ account, context }: { account: ExplorerAccountRow; context: AccountContext }) {
  const firm = context.firm;
  if (account.account_type === 'BUSINESS') {
    if (!firm) return <>A business account.</>;
    return <>Held by <EntityLink kind="firm" href={firmHref(firm.display_name)} name={firm.display_name} preview={previewHref('firm', firm.firm_id)} />.
      {firm.proprietor_uuid && <> <EntityLink kind="player" href={playerHref(firm.proprietor_name, firm.proprietor_uuid)} name={playerName(firm.proprietor_name, firm.proprietor_uuid)}
        uuid={firm.proprietor_uuid} preview={previewHref('player', firm.proprietor_uuid)} /> is proprietor of {firm.display_name}.</>}</>;
  }
  if (account.account_type === 'PERSONAL') {
    if (!account.owner_uuid) return <>A personal account.</>;
    return <>Personal account of <EntityLink kind="player" href={playerHref(account.owner_name, account.owner_uuid)} name={playerName(account.owner_name, account.owner_uuid)}
      uuid={account.owner_uuid} preview={previewHref('player', account.owner_uuid)} />.</>;
  }
  // TODO(GOV PAR-539): name the account's government unit once explorer_gov_unit lands.
  if (account.account_type === 'GOVERNMENT') return <>A government account.</>;
  return <>A system account used by server plugins.</>;
}

function Metrics({ account, activity }: { account: ExplorerAccountRow; activity: AccountActivity | null }) {
  const asOf = new Date();
  const balance = <MetricCard label="Balance" value={<Money value={account.balance} />} meta={<>As of <TimeUtc value={asOf} /></>} />;
  if (!activity) {
    return <MetricRow label="Account summary">
      {balance}
      <MetricCard label="Opened" value={<DateWithYear value={account.created_at} />} meta="Account history is private" />
    </MetricRow>;
  }
  const { last30, prev30, months } = activity;
  return <MetricRow label="Account summary">
    {balance}
    <MetricCard label="Money in, 30 days" value={<Money value={last30.inflow} compact />} delta={change(last30.inflow, prev30?.inflow)} deltaLabel="vs previous 30 days" deltaTone="neutral" />
    <MetricCard label="Money out, 30 days" value={<Money value={last30.outflow} compact />} delta={change(last30.outflow, prev30?.outflow)} deltaLabel="vs previous 30 days" deltaTone="neutral" />
    <MetricCard label="Transactions, 30 days" value={<Num value={last30.txnCount} />} delta={change(last30.txnCount, prev30?.txnCount)} deltaLabel="vs previous 30 days" deltaTone="neutral"
      spark={months.length > 1 ? months.map((m) => m.txnCount) : undefined}
      info="The sparkline shows transactions per month over the last 12 months, from the rollups (updated every few minutes)." />
  </MetricRow>;
}

function Gate({ account, viewer, returnTo, firmName }: { account: ExplorerAccountRow; viewer: Viewer; returnTo: string; firmName: string | null }) {
  const who = account.account_type === 'BUSINESS' && firmName
    ? `Members of ${firmName} with account access, and its finance staff, can see this account’s transactions and counterparties.`
    : GATE_WHO[account.account_type] ?? '';
  const totals = account.account_type === 'BUSINESS' || account.account_type === 'GOVERNMENT' ? ' The totals above are public for this kind of account.' : '';
  return <AccessGate kind={viewer.anon ? 'login' : 'private'} title="This account’s history is private"
    description={<p>{who}{totals}</p>}
    action={viewer.anon
      ? <LoginButton returnTo={returnTo} />
      : <Link href="/me" className="btn btn-primary">See your own accounts</Link>} />;
}

/** The Related rail: who holds the account and where to go next. Public, so it shows without access too. */
function Related({ account, context }: { account: ExplorerAccountRow; context: AccountContext }) {
  const items: React.ReactNode[] = [];
  const firm = context.firm;
  if (firm) {
    items.push(<li key="firm"><EntityLink kind="firm" href={firmHref(firm.display_name)} name={firm.display_name} preview={previewHref('firm', firm.firm_id)} /><span className="entity-list-aside">Account holder</span></li>);
    if (firm.proprietor_uuid) {
      items.push(<li key="prop"><EntityLink kind="player" href={playerHref(firm.proprietor_name, firm.proprietor_uuid)} name={playerName(firm.proprietor_name, firm.proprietor_uuid)} uuid={firm.proprietor_uuid}
        preview={previewHref('player', firm.proprietor_uuid)} /><span className="entity-list-aside">Proprietor</span></li>);
    }
  } else if (account.account_type === 'PERSONAL' && account.owner_uuid) {
    items.push(<li key="owner"><EntityLink kind="player" href={playerHref(account.owner_name, account.owner_uuid)} name={playerName(account.owner_name, account.owner_uuid)} uuid={account.owner_uuid}
      preview={previewHref('player', account.owner_uuid)} /><span className="entity-list-aside">Owner</span></li>);
    for (const f of context.ownerFirms.slice(0, 5)) {
      items.push(<li key={`f${f.firm_id}`}><EntityLink kind="firm" href={firmHref(f.firm_name)} name={f.firm_name} preview={previewHref('firm', f.firm_id)} />
        <span className="entity-list-aside">{f.proprietor ? 'Proprietor' : f.role_name ?? 'Employee'}</span></li>);
    }
  }
  const others = firm
    ? context.firmAccounts.filter((a) => a.account_id !== account.account_id && !a.archived)
    : context.siblings;
  const othersTitle = firm ? `Other ${firm.display_name} accounts` : account.account_type === 'GOVERNMENT' ? 'Other accounts of this government' : account.account_type === 'PERSONAL' ? 'The owner’s other accounts' : 'Related accounts';
  return <>
    {items.length > 0 && <Section level={3} title="Related"><ul className="entity-list">{items}</ul></Section>}
    {others.length > 0 && <Section level={3} title={othersTitle}>
      <ul className="entity-list">
        {others.slice(0, 8).map((a) => <li key={a.account_id}>
          <EntityLink kind="account" href={accountHref(a.account_id)} name={accountLabel({ account_id: a.account_id, display_name: a.display_name, owner_name: account.owner_name })}
            accountType={a.account_type} id={a.account_id} preview={previewHref('account', a.account_id)} />
          <span className="entity-list-aside"><Money value={a.balance} compact /></span>
        </li>)}
      </ul>
    </Section>}
    <Section level={3} title="Explore">
      <ul className="entity-list">
        <li><Link href="/accounts">All accounts</Link></li>
        <li><Link href="/firms">Firms directory</Link></li>
        <li><Link href="/players">Players directory</Link></li>
      </ul>
    </Section>
  </>;
}

function HistorySkeleton() {
  return <>
    <Section title="Balance"><SkeletonChart height={260} label="Loading balance" /></Section>
    <Section title="Money in and out" divider><SkeletonChart height={240} label="Loading money in and out" /></Section>
  </>;
}

async function RangeSections({ accountId, range }: { accountId: number; range: ParsedRange }) {
  const history = await getRangeHistory([accountId], range.range);
  const cats = history.categories.data.filter((c) => c.inflow !== '0.00' || c.outflow !== '0.00');
  const catRows = cats.map((c) => ({
    label: c.taxKind ? `${c.label} (${c.taxKind})` : c.label,
    in: toNumber(parseMoney(c.inflow)), out: toNumber(parseMoney(c.outflow)), inRaw: c.inflow, outRaw: c.outflow,
  }));
  return <>
    <Section title="Balance" description={`End of day, ${range.label.toLowerCase()}`}>
      <CoverageNote coverage={history.balance.coverage} />
      <BalanceLine data={history.balance.data.map((p) => ({ date: p.day, balance: p.balance }))} />
    </Section>
    <Section title="Money in and out" description={range.label} divider>
      <CoverageNote coverage={history.days.coverage} />
      <InOutChart data={history.days.data.map((d) => ({ day: d.day, cr: d.inflow, dr: d.outflow }))} />
    </Section>
    <Section title="Where the money came from and went" description={`By category, ${range.label.toLowerCase()}`} divider>
      <CoverageNote coverage={history.categories.coverage} />
      <BarChart data={catRows} x="label" horizontal height={Math.max(160, catRows.length * 44)} label="Money in and out by category"
        series={[
          { key: 'in', label: 'In', format: 'money', color: 'var(--pos)', rawKey: 'inRaw' },
          { key: 'out', label: 'Out', format: 'money', color: 'var(--neg)', rawKey: 'outRaw' },
        ]}
        note="Internal is money moved between the owner’s own accounts."
        empty="No money moved in this range." />
    </Section>
  </>;
}

async function Counterparties({ accountId, range }: { accountId: number; range: ParsedRange }) {
  const history = await getRangeHistory([accountId], range.range);
  const rows = history.counterparties.data;
  return <Section level={3} title="Top counterparties" description={`${range.label}, by volume`}>
    <CoverageNote coverage={history.counterparties.coverage} />
    {rows.length === 0
      ? <p className="muted small">No counterparties in this range.</p>
      : <ul className="entity-list">
        {rows.slice(0, 8).map((c) => <li key={c.counterpartyId}>
          <EntityLink kind="account" href={accountHref(c.counterpartyId)} name={accountLabel({ account_id: c.counterpartyId, display_name: c.displayName, owner_name: c.ownerName, owner_uuid: c.ownerUuid })}
            accountType={c.accountType} id={c.counterpartyId} preview={previewHref('account', c.counterpartyId)} />
          <span className="entity-list-aside"><Money value={c.volume} compact /></span>
        </li>)}
      </ul>}
  </Section>;
}

async function Transactions({ account, firmId, state, keep }: { account: ExplorerAccountRow; firmId: number | null; state: AccountTxnsState; keep: [string, string][] }) {
  const page = await getTransactionsPage(account, firmId, state);
  return <AccountTransactions pathname={accountHref(account.account_id)} state={state} page={page} extra={keep}
    searchEntity={searchCounterpartiesAction.bind(null, account.account_id)} />;
}
