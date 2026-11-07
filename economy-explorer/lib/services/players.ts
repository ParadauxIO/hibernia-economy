import 'server-only';
import { cache } from 'react';
import { memo } from '@/lib/cache';
import {
  countPlayers, findPlayerByName, findPlayerByUuid, listOwnedAccounts, listPlayerFirmRoles,
  listPlayerShops, listPlayers, type DirectoryPlayerRow, type OwnedAccountRow, type PlayerFirmRole, type PlayerRow, type PlayerShopRow,
} from '@/lib/sql/players';
import { playersTable, type PlayersTableState } from '@/lib/tables/players';
import { isQuickActive, quickFilters, serializeTableState } from '@/lib/tables/core';

// Player profiles and the player directory (spec §7.2). A profile answers "who
// is this?": it lists the player's PERSONAL accounts and firm roles, never a
// firm's balance and never a total across accounts (§3 rule 5, §14 Q1).

const FIVE_MINUTES = 5 * 60_000;
const UUID_RE = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;

/** A canonical, hyphenated lower-case UUID, or null. */
export function normaliseUuid(value: string): string | null {
  if (!UUID_RE.test(value)) return null;
  const h = value.replace(/-/g, '').toLowerCase();
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export interface ResolvedPlayer {
  uuid: string;
  /** Null when the directory has no row for this UUID (never logged in since names were recorded). */
  player: PlayerRow | null;
}

/**
 * `/players/[name]`'s parameter: a name (any case) or a UUID. Deduped per
 * request so the page, its metadata and its OpenGraph image share one lookup.
 */
export const resolvePlayer = cache(async (param: string): Promise<ResolvedPlayer | null> => {
  const uuid = normaliseUuid(param);
  if (uuid) return { uuid, player: await findPlayerByUuid(uuid) };
  if (!/^\.?[A-Za-z0-9_]{1,32}$/.test(param)) return null;
  const player = await findPlayerByName(param);
  return player ? { uuid: player.uuid, player } : null;
});

export interface PlayerProfile {
  accounts: OwnedAccountRow[];
  firms: PlayerFirmRole[];
  shops: { rows: PlayerShopRow[]; total: number };
}

/** Public profile data, cached for five minutes. */
export function getPlayerProfile(uuid: string): Promise<PlayerProfile> {
  return memo(`player-profile:v1:${uuid}`, FIVE_MINUTES, async () => {
    const [accounts, firms, shops] = await Promise.all([
      listOwnedAccounts(uuid, 'PERSONAL', { includeArchived: true, limit: 20 }),
      listPlayerFirmRoles(uuid),
      listPlayerShops(uuid, 25),
    ]);
    return { accounts, firms, shops };
  });
}

/** Only link-reachable views are cached: the default browse and the quick filters. */
function cacheable(state: PlayersTableState): boolean {
  if (state.q !== '') return false;
  if (state.filters.length === 0) return true;
  return quickFilters(playersTable).some((q) => q.filters.length === state.filters.length && isQuickActive(state, q));
}

export async function getPlayersDirectory(state: PlayersTableState): Promise<{ rows: DirectoryPlayerRow[]; total: number }> {
  const offset = (state.page - 1) * state.limit;
  const fetch = async () => {
    const [rows, total] = await Promise.all([listPlayers(state, state.limit, offset), countPlayers(state)]);
    return { rows, total };
  };
  return cacheable(state) ? memo(`players-dir:v1:${serializeTableState(state, playersTable)}`, 60_000, fetch) : fetch();
}

