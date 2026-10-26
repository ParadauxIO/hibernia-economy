import '@/components/entity/entity.css';
import Link from 'next/link';
import { notFound, permanentRedirect } from 'next/navigation';
import { EntityBadge, MetricCard, MetricRow, Money, Num, PageHeader, PlayerHead, Section, Tag, When } from '@democracycraft/design-system';
import { EntityLink } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { Breadcrumbs } from '@/components/Breadcrumbs';
import { CopyButton } from '@/components/entity/CopyButton';
import { DateWithYear } from '@/components/entity/bits';
import { ShopTable } from '@/components/market/ShopTable';
import { hasPermission } from '@/lib/auth/access';
import { getViewer } from '@/lib/auth/viewer';
import { accountHref, firmHref, playerHref, playerName, previewHref } from '@/lib/entityLinks';
import { accountLabel } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import { entityMetadata } from '@/lib/entityMetadata';
import { serverIdentity } from '@/lib/serverIdentity';
import { getPlayerProfile, resolvePlayer } from '@/lib/services/players';
import type { OwnedAccountRow, PlayerFirmRole } from '@/lib/sql/players';
import { resolveTheme } from '@/lib/theme';

export const dynamic = 'force-dynamic';

type Params = Promise<{ name: string }>;

export async function generateMetadata({ params }: { params: Params }) {
  const { name } = await params;
  const resolved = await resolvePlayer(decodeURIComponent(name));
  const label = resolved ? playerName(resolved.player?.name, resolved.uuid) : decodeURIComponent(name);
  if (!resolved) return buildMetadata({ title: label, description: 'No such player on {server}.', path: `/players/${encodeURIComponent(label)}` });
  return entityMetadata({ title: label, description: `${label} on {server}: accounts, firms and shops.`, path: `/players/${encodeURIComponent(label)}` }, `${label}, player profile`);
}

/**
 * Spec §7.2: who is this? Identity, personal accounts, firm roles and shops.
 * No firm balances and no totals: a firm's money is not its people's (§3 rule 5).
 */
export default async function PlayerPage({ params }: { params: Params }) {
  const { name: raw } = await params;
  const param = decodeURIComponent(raw);
  const [resolved, viewer, theme] = await Promise.all([resolvePlayer(param), getViewer(), resolveTheme()]);
  if (!resolved) notFound();
  const { uuid, player } = resolved;
  // One canonical URL per player: their current name, in its own casing.
  if (player && player.name !== param && !/^[0-9A-F]{32}$/i.test(player.name)) permanentRedirect(playerHref(player.name));

  const profile = await getPlayerProfile(uuid);
  if (!player && profile.accounts.length === 0 && profile.firms.length === 0) notFound();
  const name = playerName(player?.name, uuid);
  const self = !viewer.anon && viewer.minecraftUuid === uuid;
  const staff = hasPermission(viewer, 'player.read.all');
  const path = playerHref(player?.name, uuid);

  return <>
    <Breadcrumbs section="/players" items={[{ label: name }]} />
    <PageHeader
      avatar={<PlayerHead uuid={uuid} name={name} size={56} />}
      eyebrow={<><EntityBadge type="PERSONAL" label="Player" />{self && <Tag tone="accent">You</Tag>}</>}
      title={name}
      subtitle={<RoleSummary firms={profile.firms} known={!!player} />}
      actions={<div className="entity-actions">
        <CopyButton value={path} label="Copy link" />
        {self && <Link href="/me" className="btn">My finances</Link>}
        {staff && <Link href={`/admin/players/${uuid}`} className="btn">Admin view</Link>}
      </div>}
    />

    <MetricRow label="Player summary">
      <MetricCard label="First seen" value={player ? <DateWithYear value={player.first_seen} /> : '—'} />
      <MetricCard label="Last seen" value={player ? <When value={player.last_seen} /> : '—'} />
      <MetricCard label="Firms" value={<Num value={profile.firms.length} />} meta={profile.firms.some((f) => f.proprietor) ? 'Including firms they run' : undefined} />
      <MetricCard label="Shops" value={<Num value={profile.shops.total} />} meta="Their own ChestShop signs" />
    </MetricRow>

    <div className="entity-layout">
      <div className="entity-main">
        <Section title="Personal account" description="A player’s own wallet. Balances are public; history is private.">
          <Accounts accounts={profile.accounts} ownerName={player?.name ?? null} />
        </Section>
        <Section title="Firms" description="Firms they run or work for. A firm’s money belongs to the firm, so it isn’t shown here." divider>
          <Firms firms={profile.firms} />
        </Section>
        <Section title="Shops" description={profile.shops.total > profile.shops.rows.length ? `${profile.shops.rows.length} of ${profile.shops.total.toLocaleString('en-US')}, as last observed` : 'As last observed in game'} divider>
          <ShopTable id="player-shops" label={`${name}’s shops`} shops={profile.shops.rows} mapBase={serverIdentity(theme).map} />
        </Section>
      </div>
      <aside className="entity-rail" aria-label="Related">
        {profile.firms.length > 0 && <Section level={3} title="Works with">
          <ul className="entity-list">
            {profile.firms.slice(0, 6).map((f) => <li key={f.firm_id}>
              <EntityLink kind="firm" href={firmHref(f.firm_name)} name={f.firm_name} preview={previewHref('firm', f.firm_id)} />
              <span className="entity-list-aside">{f.proprietor ? 'Proprietor' : f.role_name ?? 'Employee'}</span>
            </li>)}
          </ul>
        </Section>}
        <Section level={3} title="Explore">
          <ul className="entity-list">
            <li><Link href="/players">All players</Link></li>
            <li><Link href="/firms">Firms directory</Link></li>
            <li><Link href="/market">Market</Link></li>
          </ul>
        </Section>
      </aside>
    </div>
  </>;
}

/** "Proprietor of Riverside Builders · Accountant at Northgate Bank". Roles, never money. */
function RoleSummary({ firms, known }: { firms: PlayerFirmRole[]; known: boolean }) {
  if (firms.length === 0) return <>{known ? 'Not part of any firm.' : 'This player hasn’t been seen since names were recorded.'}</>;
  const shown = firms.slice(0, 3);
  return <>{shown.map((f, i) => <span key={f.firm_id}>{i > 0 && ' · '}{f.proprietor ? 'Proprietor of ' : `${f.role_name ?? 'Employee'} at `}
    <EntityLink kind="firm" href={firmHref(f.firm_name)} name={f.firm_name} preview={previewHref('firm', f.firm_id)} /></span>)}
    {firms.length > shown.length && <> · and {firms.length - shown.length} more</>}</>;
}

function Accounts({ accounts, ownerName }: { accounts: OwnedAccountRow[]; ownerName: string | null }) {
  const columns: Column<OwnedAccountRow>[] = [
    {
      key: 'account', header: 'Account',
      cell: (a) => <>
        <EntityLink kind="account" href={accountHref(a.account_id)} name={accountLabel({ account_id: a.account_id, display_name: a.display_name, owner_name: ownerName })}
          accountType={a.account_type} id={a.account_id} preview={previewHref('account', a.account_id)} />
        {a.archived ? <span className="dt-sub"> · closed</span> : null}
      </>,
    },
    { key: 'opened', header: 'Opened', secondary: true, cell: (a) => <DateWithYear value={a.created_at} /> },
    { key: 'balance', header: 'Balance', numeric: true, cell: (a) => <Money value={a.balance} /> },
  ];
  return <DataTable id="player-accounts" label="Personal accounts" columns={columns} rows={accounts} rowKey={(a) => a.account_id}
    empty={<p className="dt-empty-text">No personal account on record.</p>} />;
}

function Firms({ firms }: { firms: PlayerFirmRole[] }) {
  const columns: Column<PlayerFirmRole>[] = [
    { key: 'firm', header: 'Firm', cell: (f) => <EntityLink kind="firm" href={firmHref(f.firm_name)} name={f.firm_name} preview={previewHref('firm', f.firm_id)} /> },
    { key: 'role', header: 'Role', cell: (f) => f.proprietor ? <>Proprietor{f.role_name ? <span className="dt-sub">{` · ${f.role_name}`}</span> : null}</> : f.role_name ?? 'Employee' },
    { key: 'since', header: 'Since', secondary: true, cell: (f) => f.joined_at ? <DateWithYear value={f.joined_at} /> : <span className="muted">—</span> },
  ];
  return <DataTable id="player-firms" label="Firm roles" columns={columns} rows={firms} rowKey={(f) => f.firm_id}
    empty={<p className="dt-empty-text">Not part of any active firm.</p>} />;
}
