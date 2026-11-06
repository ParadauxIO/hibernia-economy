import 'server-only';
import type { EntityPreviewData } from '@democracycraft/design-system';
import { requireLinkedViewer } from '@/lib/auth/linked';
import { memo } from '@/lib/cache';
import { accountLabel } from '@/lib/format';
import { formatMoney, sumBy } from '@/lib/money';
import { addDays, utcDay } from '@/lib/rollup/days';
import { findAccount, findFirmById } from '@/lib/auth/access';
import { getAccountContext, getActivity } from '@/lib/services/account';
import { getFirmPublic } from '@/lib/services/firm';
import { getPlayerProfile, resolvePlayer } from '@/lib/services/players';
import { itemLabel } from '@/lib/services/market';
import { countShops, findItemIdentities } from '@/lib/sql/market';
import { itemDays } from '@/lib/services/rollup';
import { summarise } from '@/lib/market/itemSeries';
import { playerName } from '@/lib/entityLinks';

// Hover-card data for EntityLink (DS README: `{ title, subtitle?, rows? }`).
// All cards require a linked account. They still omit private history,
// counterparties, membership and financial details.

export type PreviewKind = 'player' | 'account' | 'firm' | 'item';

const DATE_FMT = new Intl.DateTimeFormat('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' });
const typeName = (t: string) => t.charAt(0) + t.slice(1).toLowerCase();

async function accountPreview(id: number): Promise<EntityPreviewData | null> {
  const account = await findAccount(id);
  if (!account) return null;
  const context = await getAccountContext(account);
  const rows = [{ label: 'Balance', value: formatMoney(account.balance) }];
  if (context.firm) rows.push({ label: 'Held by', value: context.firm.display_name });
  else if (account.account_type === 'PERSONAL') rows.push({ label: 'Owner', value: playerName(account.owner_name, account.owner_uuid) });
  if (account.account_type === 'BUSINESS' || account.account_type === 'GOVERNMENT') {
    const activity = await getActivity([id]);
    rows.push({ label: 'In, 30 days', value: formatMoney(activity.last30.inflow, { compact: true }) });
    rows.push({ label: 'Out, 30 days', value: formatMoney(activity.last30.outflow, { compact: true }) });
  }
  return { title: accountLabel(account), subtitle: `${typeName(account.account_type)} account · #${id}${account.archived ? ' · closed' : ''}`, rows };
}

async function playerPreview(param: string): Promise<EntityPreviewData | null> {
  const resolved = await resolvePlayer(param);
  if (!resolved) return null;
  const profile = await getPlayerProfile(resolved.uuid);
  const name = playerName(resolved.player?.name, resolved.uuid);
  const rows: { label: string; value: string }[] = [];
  const personal = profile.accounts.find((a) => !a.archived);
  if (personal) rows.push({ label: 'Personal account', value: formatMoney(personal.balance) });
  const roles = profile.firms.slice(0, 2).map((f) => `${f.proprietor ? 'Proprietor' : f.role_name ?? 'Employee'}, ${f.firm_name}`);
  if (roles.length) rows.push({ label: profile.firms.length > 1 ? 'Firms' : 'Firm', value: roles.join('; ') + (profile.firms.length > 2 ? ` +${profile.firms.length - 2}` : '') });
  if (profile.shops.total) rows.push({ label: 'Shops', value: profile.shops.total.toLocaleString('en-US') });
  return { title: name, subtitle: resolved.player ? `Player · joined ${DATE_FMT.format(resolved.player.first_seen)}` : 'Player', rows };
}

async function firmPreview(id: number): Promise<EntityPreviewData | null> {
  const [firm, pub] = await Promise.all([findFirmById(id), getFirmPublic(id)]);
  if (!firm || !pub) return null;
  const s = pub.summary;
  const rows = [
    { label: 'Balance', value: formatMoney(sumBy(pub.accounts.filter((a) => !a.archived), (a) => a.balance)) },
    { label: 'Accounts', value: s.account_count.toLocaleString('en-US') },
  ];
  if (s.proprietor_uuid) rows.unshift({ label: 'Proprietor', value: playerName(s.proprietor_name, s.proprietor_uuid) });
  if (s.hq_region) rows.push({ label: 'Headquarters', value: s.hq_region });
  return { title: s.display_name, subtitle: `Firm · ${s.staff_count.toLocaleString('en-US')} staff${s.archived ? ' · disbanded' : ''}`, rows: rows.slice(0, 4) };
}

async function itemPreview(key: string): Promise<EntityPreviewData | null> {
  const today = utcDay();
  const [[identity], days, shops] = await Promise.all([
    findItemIdentities([key]),
    itemDays([key], { from: addDays(today, -6), to: today }),
    countShops({ itemKey: key, material: null, firmId: null, ownerUuid: null, buyable: false, direction: 'buy', inStock: false, search: null }),
  ]);
  const week = summarise(days.data, key);
  if (!identity?.item_name && week.trades === 0 && shops === 0) return null;
  const rows = [{ label: 'Shops selling', value: shops.toLocaleString('en-US') }];
  if (week.avgPrice) rows.unshift({ label: 'Average price, 7 days', value: formatMoney(week.avgPrice) });
  if (week.trades) rows.push({ label: 'Trades, 7 days', value: week.trades.toLocaleString('en-US') });
  return { title: itemLabel(key, identity ?? null), subtitle: 'Item', rows };
}

/** One entity's hover card, cached for five minutes. Null when it doesn't exist. */
export async function getPreview(kind: PreviewKind, id: string): Promise<EntityPreviewData | null> {
  await requireLinkedViewer();
  return memo(`preview:v1:${kind}:${id}`, 5 * 60_000, async () => {
    switch (kind) {
      case 'account': return /^[1-9]\d{0,9}$/.test(id) ? accountPreview(Number(id)) : null;
      case 'firm': return /^[1-9]\d{0,9}$/.test(id) ? firmPreview(Number(id)) : null;
      case 'player': return playerPreview(id);
      case 'item': return /^[A-Za-z0-9_:.#-]{1,64}$/.test(id) ? itemPreview(id) : null;
    }
  });
}
