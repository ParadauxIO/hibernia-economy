import type { ReactNode } from 'react';
import Link from 'next/link';
import type { Route } from 'next';
import { Tag, When } from '@democracycraft/design-system';
import { EntityLink, RangePicker } from '@democracycraft/design-system/next';
import { accountLabel, looksLikeUuid, shortenUuid } from '@/lib/format';
import { parseRange, rangeToParams, type ParsedRange } from '@/lib/range';
import type { Coverage } from '@/lib/services/government';
import type { Selection } from '@/lib/services/governmentModel';
import { auditGovRead, govSelection, resolveGovScope, type GovScope } from '@/lib/services/governmentScope';
import { utcDay } from '@/lib/rollup/days';
import { GovGate } from './GovGate';
import { UnitSelect, type UnitOption } from './UnitSelect';

export type SearchParams = Record<string, string | string[] | undefined>;

export interface GovPage {
  scope: GovScope;
  selection: Selection;
  range: ParsedRange;
  /** `&unit=…&range=…` to append to links that stay on the same unit and range. */
  carry: string;
  /** The same params as entries, for tableHref's `extra`. */
  extra: [string, string][];
}

/**
 * Every /gov page starts here: resolve the scope (shared with the layout),
 * pick the unit and range from the URL, and record the read. Returns a gate
 * element instead when the viewer can't use the page.
 */
export async function govPage(searchParams: SearchParams, path: string): Promise<GovPage | { gate: ReactNode }> {
  const access = await resolveGovScope();
  if (!access.ok) return { gate: <GovGate reason={access.reason} /> };
  const { scope } = access;
  if (scope.ids.length === 0) return { gate: <GovGate reason="scope" /> };
  const selection = govSelection(scope, searchParams.unit);
  const range = parseRange(searchParams, { defaultPreset: '30d' });
  await auditGovRead(scope, path, selection.ids);
  const carried = new URLSearchParams({ ...(selection.key !== 'all' ? { unit: selection.key } : {}), ...rangeToParams(range) });
  return { scope, selection, range, carry: `&${carried.toString()}`, extra: [...carried.entries()] };
}

export function isGate(page: GovPage | { gate: ReactNode }): page is { gate: ReactNode } {
  return 'gate' in page;
}

function unitOptions(scope: GovScope): UnitOption[] {
  return [
    { value: 'all', label: 'All units', depth: 0 },
    ...scope.units.map((u) => ({ value: u.slug, label: u.partial ? `${u.name} (partial)` : u.name, depth: u.depth })),
    ...(scope.unassigned.length ? [{ value: 'unassigned', label: 'Unassigned', depth: 0 }] : []),
  ];
}

/** Unit selector and range picker, the controls every /gov page shares. */
export function GovControls({ page, presets }: { page: GovPage; presets?: ('7d' | '30d' | '90d' | 'ytd' | '1y' | 'all')[] }) {
  return <div className="gov-controls">
    <UnitSelect value={page.selection.key} options={unitOptions(page.scope)} />
    <RangePicker value={page.range.value} presets={presets ?? ['7d', '30d', '90d', 'ytd', '1y']} max={utcDay()} resetParams={['cursor', 'vcursor']} allowCompare={false} />
  </div>;
}

/** "Partial view: 3 of 5 accounts" when the viewer can't read all of a unit. */
export function ScopeNote({ page }: { page: GovPage }) {
  const unit = page.selection.unit;
  const parts: ReactNode[] = [];
  if (unit?.partial) {
    parts.push(<span key="partial"><Tag tone="warn">Partial view</Tag> {unit.readableIds.length} of {unit.totalAccounts} accounts. Figures include only the accounts you can read.</span>);
  }
  if (page.selection.ids.length === 0) parts.push(<span key="none">No accounts in this selection.</span>);
  return parts.length ? <p className="gov-scope-note">{parts}</p> : null;
}

/** Rollup freshness, or why a section isn't ready yet. Never a live fallback. */
export function Freshness({ coverage }: { coverage: Coverage }) {
  if (!coverage.covered) {
    return <span className="gov-preparing" role="status">Preparing history{coverage.coveredFrom ? <>: covered back to <When value={`${coverage.coveredFrom}T00:00:00Z`} /></> : '…'}</span>;
  }
  return coverage.updatedAt ? <span className="muted">Updated <When value={coverage.updatedAt} time /></span> : null;
}

/** When the raw ledger window is shorter than the selected range. */
export function RawNote({ range }: { range: { from: string; to: string; capped: boolean } }) {
  return range.capped
    ? <>Last 31 days of the range ({range.from} to {range.to}): read from the ledger, which is limited to 31 days at a time.</>
    : <>Read from the ledger for the selected range.</>;
}

export function AccountRef({ id, name, type }: { id: number; name: string | null; type?: string | null }) {
  return <EntityLink kind="account" href={`/accounts/${id}`} name={name && !looksLikeUuid(name) ? name : accountLabel({ display_name: name, owner_name: null, owner_uuid: null, account_id: id })} accountType={type ?? 'GOVERNMENT'} id={id} />;
}

export function PlayerRef({ uuid, name, fallback = 'Unknown' }: { uuid: string | null; name: string | null; fallback?: string }) {
  if (!name && !uuid) return <span className="muted">{fallback}</span>;
  if (!name || looksLikeUuid(name)) return <span className="mono muted" title={uuid ?? undefined}>{shortenUuid(uuid)}</span>;
  return <EntityLink kind="player" href={`/search?q=${encodeURIComponent(name)}`} name={name} uuid={uuid} />;
}

/** A payer or recipient: the firm when the account is a firm's, otherwise the account and its owner. */
export function PartyRef({ row }: { row: { account_id: number; display_name: string | null; account_type: string; owner_uuid: string | null; owner_name: string | null; firm_name?: string | null } }) {
  if (row.firm_name) {
    return <span className="gov-party">
      <EntityLink kind="firm" href={`/firms/${encodeURIComponent(row.firm_name)}`} name={row.firm_name} />
      <span className="dt-sub"> · <AccountRef id={row.account_id} name={row.display_name} type={row.account_type} /></span>
    </span>;
  }
  if (row.account_type === 'PERSONAL') {
    return <span className="gov-party">
      <PlayerRef uuid={row.owner_uuid} name={row.owner_name} />
      {row.account_id > 0 && <span className="dt-sub">· <Link href={`/accounts/${row.account_id}` as Route} prefetch={false}>account #{row.account_id}</Link></span>}
    </span>;
  }
  return <AccountRef id={row.account_id} name={row.display_name} type={row.account_type} />;
}

export const STATUS_TONE: Record<'ok' | 'late' | 'missed' | 'duplicate' | 'resumed', 'neutral' | 'pos' | 'neg' | 'warn' | 'accent'> = { ok: 'neutral', late: 'warn', missed: 'neg', duplicate: 'warn', resumed: 'accent' };
