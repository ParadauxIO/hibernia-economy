import '@/components/entity/entity.css';
import { notFound } from 'next/navigation';
import { EntityBadge, InfoTip, PageHeader, Tag } from '@democracycraft/design-system';
import { EntityLink, Tabs } from '@democracycraft/design-system/next';
import { Breadcrumbs } from '@/components/Breadcrumbs';
import { CopyButton } from '@/components/entity/CopyButton';
import { DateWithYear } from '@/components/entity/bits';
import { auditRelationshipRead, auditView } from '@/lib/audit';
import { canViewFirmFinancials, canViewFirmInternals, findFirmByDisplayName, hasElevatedRead, isFirmMember } from '@/lib/auth/access';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { firmHref, playerHref, playerName, previewHref } from '@/lib/entityLinks';
import { getFirmPublic, type FirmPublic } from '@/lib/services/firm';
import type { FirmRow } from '@/lib/sql/firm';

export type FirmTab = 'overview' | 'members' | 'financials';

export interface LoadedFirm {
  firm: FirmRow;
  pub: FirmPublic;
  viewer: Viewer;
  canSeeMembers: boolean;
  canSeeFinancials: boolean;
}

/**
 * The firm by name, its public data, and both access answers. The two checks
 * run in parallel and share their per-request (React cache) lookups (§7.3).
 */
export async function loadFirm(rawName: string, tab: FirmTab): Promise<LoadedFirm> {
  const name = decodeURIComponent(rawName);
  const [firm, viewer] = await Promise.all([findFirmByDisplayName(name), getViewer()]);
  if (!firm) notFound();
  const [pub, canSeeMembers, canSeeFinancials] = await Promise.all([
    getFirmPublic(firm.firm_id),
    canViewFirmInternals(firm.firm_id, viewer),
    canViewFirmFinancials(firm.firm_id, viewer),
  ]);
  if (!pub) notFound();
  // Staff inspecting a gated tab of a firm they aren't part of are audited.
  const gated = (tab === 'members' && canSeeMembers) || (tab === 'financials' && canSeeFinancials);
  if (gated && hasElevatedRead(viewer)) {
    const member = viewer.minecraftUuid ? await isFirmMember(firm.firm_id, viewer.minecraftUuid) : false;
    if (!member) await auditView(viewer, { path: `${firmHref(firm.display_name)}/${tab}`, targetType: 'firm', targetId: String(firm.firm_id) });
  } else if (tab === 'financials' && canSeeFinancials) {
    // Financials read the firm's business accounts through a relationship (spec §12).
    await auditRelationshipRead(viewer, pub.accounts.map((a) => ({ accountId: a.account_id, accountType: a.account_type })), `${firmHref(firm.display_name)}/financials`);
  }
  return { firm, pub, viewer, canSeeMembers, canSeeFinancials };
}

export function FirmHeader({ loaded, tab }: { loaded: LoadedFirm; tab: FirmTab }) {
  const { firm, pub } = loaded;
  const s = pub.summary;
  const base = firmHref(firm.display_name);
  const tabs = [
    { label: 'Overview', href: base, current: tab === 'overview' },
    { label: 'Members', href: `${base}/members`, current: tab === 'members', count: s.staff_count },
    { label: 'Financials', href: `${base}/financials`, current: tab === 'financials' },
  ];
  return <>
    <Breadcrumbs section="/firms" items={tab === 'overview' ? [{ label: firm.display_name }] : [{ label: firm.display_name, href: base }, { label: tab === 'members' ? 'Members' : 'Financials' }]} />
    <PageHeader
      eyebrow={<><EntityBadge type="BUSINESS" label="Firm" /><span className="num">#{firm.firm_id}</span>
        {firm.archived ? <Tag>Disbanded</Tag> : null}{firm.exempt ? <Tag>Tax exempt</Tag> : null}</>}
      title={firm.display_name}
      subtitle={firm.proprietor_uuid
        ? <>Run by <EntityLink kind="player" href={playerHref(firm.proprietor_name, firm.proprietor_uuid)} name={playerName(firm.proprietor_name, firm.proprietor_uuid)} uuid={firm.proprietor_uuid}
          preview={previewHref('player', firm.proprietor_uuid)} />, its proprietor.</>
        : undefined}
      meta={<>
        <span className="entity-meta-item">Founded <DateWithYear value={firm.created_at} /></span>
        {firm.hq_region && <span className="entity-meta-item">HQ {firm.hq_region}</span>}
        <span className="entity-meta-item">{s.staff_count.toLocaleString('en-US')} staff · {s.account_count.toLocaleString('en-US')} {s.account_count === 1 ? 'account' : 'accounts'}
          <InfoTip label="About firm money">A firm’s accounts hold the firm’s money. It is not its proprietor’s: firms can have shareholders, and some hold other players’ deposits.</InfoTip></span>
        {firm.discord_url && /^https:\/\//.test(firm.discord_url) && <a className="entity-meta-item" href={firm.discord_url} target="_blank" rel="noopener noreferrer">Discord <span aria-hidden="true">↗</span></a>}
      </>}
      actions={<div className="entity-actions"><CopyButton value={base} label="Copy link" /></div>}
      tabs={<Tabs label="Firm" items={tabs} />}
    />
  </>;
}

