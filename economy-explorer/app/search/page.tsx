import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { Button, EmptyState, EntityBadge, Input, Money, PageHeader, Section, Segmented, ToolbarLayout, When } from '@democracycraft/design-system';
import { DataTable, TablePager, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { buildMetadata } from '@/lib/metadata';
import { MIN_QUERY, SEARCH_TABS, normaliseQuery, searchEverything, type SearchGroup, type SearchGroupKind, type SearchTab } from '@/lib/services/search';
import type { ItemSearchResult, SearchResult } from '@/lib/sql/search';

// The full search (spec §9.1): contains matches on account, firm and item names,
// prefix matches on player names, grouped with counts and per-type tabs. It runs
// only when the form is submitted (Enter); the typeahead never comes here.

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Search', description: 'Search accounts, players, firms and items across the {server} economy.', path: '/search' });
}

const RouterLink: LinkComponent<AppHref> = Link;
const PAGE_SIZE = 50;
const PREVIEW = 5;

const LABELS: Record<SearchGroupKind, { title: string; one: string; many: string }> = {
  accounts: { title: 'Accounts', one: 'account', many: 'accounts' },
  firms: { title: 'Firms', one: 'firm', many: 'firms' },
  players: { title: 'Players', one: 'player', many: 'players' },
  items: { title: 'Items', one: 'item', many: 'items' },
};

const NOTES: Record<SearchGroupKind, string> = {
  accounts: 'Business, government and system account names, and account numbers. Personal accounts are listed under their player.',
  firms: 'Active firms.',
  players: 'Player names that start with your search.',
  items: 'Items that have been traded in a shop.',
};

const accountColumns: Column<SearchResult>[] = [
  { key: 'name', header: 'Account', cell: (r) => <><span>{r.label}</span><span className="dt-sub">{` #${r.account_id}`}</span></> },
  { key: 'type', header: 'Type', secondary: true, cell: (r) => (r.account_type ? <EntityBadge type={r.account_type} /> : null) },
  { key: 'balance', header: 'Balance', numeric: true, cell: (r) => <Money value={r.balance} /> },
];

const firmColumns: Column<SearchResult>[] = [
  { key: 'name', header: 'Firm', cell: (r) => r.label },
  { key: 'hq', header: 'Headquarters', secondary: true, cell: (r) => (r.secondary && r.secondary !== '—' ? r.secondary : <span className="muted">—</span>) },
  { key: 'balance', header: 'Main account', numeric: true, cell: (r) => <Money value={r.balance} /> },
];

const playerColumns: Column<SearchResult>[] = [
  { key: 'name', header: 'Player', cell: (r) => r.label },
  { key: 'seen', header: 'First seen', align: 'end', cell: (r) => (r.secondary ? <When value={`${r.secondary}T00:00:00Z`} relative={false} /> : <span className="muted">—</span>) },
];

const itemColumns: Column<ItemSearchResult>[] = [
  { key: 'name', header: 'Item', cell: (r) => <><span>{r.label}</span>{r.secondary && <span className="dt-sub">{` ${r.secondary}`}</span>}</> },
  { key: 'traded', header: 'Last traded', align: 'end', cell: (r) => <When value={r.last_traded_at} /> },
];

function one(v: string | string[] | undefined): string | undefined {
  return Array.isArray(v) ? v[0] : v;
}

function count(n: number, kind: SearchGroupKind): string {
  return `${n.toLocaleString('en-US')} ${n === 1 ? LABELS[kind].one : LABELS[kind].many}`;
}

function GroupTable({ group, q, preview }: { group: SearchGroup<SearchResult> | SearchGroup<ItemSearchResult>; q: string; preview: boolean }) {
  const id = `search-${group.kind}`;
  const label = `${LABELS[group.kind].title} matching “${q}”`;
  switch (group.kind) {
    case 'accounts':
    case 'firms':
    case 'players': {
      const rows = group.rows as SearchResult[];
      const cols = group.kind === 'accounts' ? accountColumns : group.kind === 'firms' ? firmColumns : playerColumns;
      return <DataTable id={id} label={label} columns={cols} rows={preview ? rows.slice(0, PREVIEW) : rows} rowKey={(r) => r.account_id ?? r.firm_id ?? r.player_uuid ?? r.label} rowHref={(r) => r.href ?? `/search?q=${encodeURIComponent(r.label)}`} density="compact" link={RouterLink} />;
    }
    case 'items': {
      const rows = group.rows as ItemSearchResult[];
      return <DataTable id={id} label={label} columns={itemColumns} rows={preview ? rows.slice(0, PREVIEW) : rows} rowKey={(r) => r.item_key} rowHref={(r) => r.href} density="compact" link={RouterLink} />;
    }
  }
}

export default async function SearchPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const q = normaliseQuery(one(sp.q) ?? '');
  const rawTab = one(sp.type) ?? 'all';
  const tab: SearchTab = (SEARCH_TABS as readonly string[]).includes(rawTab) ? (rawTab as SearchTab) : 'all';
  const pageNo = Math.min(Math.max(1, Math.floor(Number(one(sp.page)) || 1)), 100);

  const result = q ? await searchEverything(q, { tab, limit: PAGE_SIZE, offset: (pageNo - 1) * PAGE_SIZE, preview: PREVIEW }) : null;
  const groups = result ? [result.accounts, result.firms, result.players, result.items] : [];
  const total = groups.reduce((n, g) => n + g.total, 0);
  const tabHref = (t: SearchTab, p = 1) => `/search?q=${encodeURIComponent(q)}${t === 'all' ? '' : `&type=${t}`}${p > 1 ? `&page=${p}` : ''}` as const;

  return (
    <>
      <PageHeader title="Search" subtitle={q ? <>Results for “{q}”</> : 'Accounts, firms, players and items across the economy.'} />

      <form action="/search" method="get" role="search" aria-label="Search the economy">
        <ToolbarLayout
          search={<Input type="search" name="q" className="toolbar-search" defaultValue={q} placeholder="Account, firm, player or item, or an account #" aria-label="Search" autoComplete="off" />}
          actions={<Button type="submit" variant="primary">Search</Button>}
        />
      </form>

      {!q && (
        <EmptyState title="Search the economy">
          Account and firm names match anywhere in the name; player names match from the start. Type a number to open that account.
        </EmptyState>
      )}

      {q && q.length < MIN_QUERY && !/^#?\d+$/.test(q) && <EmptyState title="Type at least two characters" />}

      {result && total === 0 && q.length >= MIN_QUERY && (
        <EmptyState title={`Nothing matches “${q}”`}>Player names match from the first letter. Try fewer letters, or an account number.</EmptyState>
      )}

      {result && total > 0 && (
        <>
          <Segmented
            label="Result type"
            value={tab}
            items={SEARCH_TABS.map((t) => ({
              value: t,
              label: t === 'all' ? `All ${total.toLocaleString('en-US')}` : `${LABELS[t].title} ${(result[t].total).toLocaleString('en-US')}`,
              href: tabHref(t),
              disabled: t !== 'all' && result[t].total === 0,
            }))}
          />

          {tab === 'all'
            ? groups.filter((g) => g.total > 0).map((g) => (
                <Section
                  key={g.kind}
                  id={`results-${g.kind}`}
                  title={LABELS[g.kind].title}
                  description={`${count(g.total, g.kind)}. ${NOTES[g.kind]}`}
                  actions={g.total > PREVIEW ? <Link href={tabHref(g.kind)} prefetch={false}>See all {g.total.toLocaleString('en-US')}</Link> : undefined}
                >
                  <GroupTable group={g} q={q} preview />
                </Section>
              ))
            : (
                <Section id={`results-${tab}`} title={LABELS[tab].title} description={`${count(result[tab].total, tab)}. ${NOTES[tab]}`}>
                  {result[tab].rows.length > 0
                    ? <GroupTable group={result[tab]} q={q} preview={false} />
                    : <EmptyState compact title={`No ${LABELS[tab].many} on this page`} />}
                  <TablePager page={pageNo} totalPages={Math.max(1, Math.ceil(result[tab].total / PAGE_SIZE))} hrefFor={(p) => tabHref(tab, p)} link={RouterLink} />
                </Section>
              )}
        </>
      )}
    </>
  );
}
