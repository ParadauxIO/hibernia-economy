import { activeGroupMembership } from '@/lib/sql/group-membership';
import 'server-only';
import { type Kysely, type RawBuilder, type SqlBool, sql } from 'kysely';
import { adminGroupMembersTable, type AdminGroupMembersState } from '@/lib/tables/admin';
import { compileTableState, defineColumnMap, likeContains } from '@/lib/sql/tableState';
import { db, type DB, uuidToBin, binToUuid } from '@/lib/db';

/**
 * Explorer group RBAC queries — a pure DAL: each export is a single Kysely query
 * (plus row→type mapping), no orchestration or validation. A player's effective
 * roles come from every group they belong to (lib/sql/role resolveAuthorization);
 * this file is the admin-tool CRUD for groups, their capabilities, their LuckPerms
 * source node, and manual membership. Orchestration (multi-statement writes,
 * identifier resolution) lives in {@code lib/services/group.ts}. LuckPerms-sourced
 * membership is owned by the reconciliation cron (treasury-api-plugin) and is
 * read-only here.
 */

/**
 * An executor for a query — the shared pool ({@link db}) by default, or a
 * {@code Transaction<DB>} when the service runs several statements atomically.
 */
type Executor = Kysely<DB>;

interface GroupRow {
  group_id: number;
  name: string;
  description: string | null;
  luckperms_node: string | null;
  capabilities: string | null;
  sync_sources: string | null;
  member_count: number | string;
  active_count: number | string;
  luckperms_count: number | string;
}

function mapGroupRow(row: GroupRow): GroupSummary {
  return {
    groupId: row.group_id,
    name: row.name,
    description: row.description,
    luckpermsNode: row.luckperms_node,
    capabilities: row.capabilities ? row.capabilities.split(',') : [],
    // `source<TAB>selector` pairs, newline-separated: selectors may contain commas.
    syncSources: row.sync_sources
      ? row.sync_sources.split('\n').map((pair) => {
        const [source = '', selector = ''] = pair.split('\t');
        return { source: source as GroupSyncSource['source'], selector };
      })
      : [],
    memberCount: Number(row.member_count),
    activeMemberCount: Number(row.active_count),
    luckpermsMemberCount: Number(row.luckperms_count),
  };
}

export interface GroupSyncSource {
  source: 'business' | 'realty';
  selector: string;
}

export interface GroupSummary {
  groupId: number;
  name: string;
  description: string | null;
  luckpermsNode: string | null;
  capabilities: string[];
  /** Firm and region sources (explorer_group_sync). */
  syncSources: GroupSyncSource[];
  /** Every membership row, including expired synced ones. */
  memberCount: number;
  /** Memberships that grant roles right now (manual, or synced within the hour). */
  activeMemberCount: number;
  luckpermsMemberCount: number;
}

export interface GroupMember {
  uuid: string;
  name: string | null;
  source: 'manual' | 'luckperms' | 'business' | 'realty';
  addedAt: Date;
  syncActive: boolean;
}

/** One summary row per group, as a derived table `g` the admin table filters and sorts. */
const GROUP_SUMMARY = sql`(
  SELECT g.group_id, g.name, g.description, g.luckperms_node,
         (SELECT GROUP_CONCAT(gc.capability ORDER BY gc.capability)
            FROM explorer_group_capability gc WHERE gc.group_id = g.group_id) AS capabilities,
         (SELECT GROUP_CONCAT(CONCAT(s.source, CHAR(9), s.selector) ORDER BY s.source SEPARATOR '\n')
            FROM explorer_group_sync s WHERE s.group_id = g.group_id) AS sync_sources,
         (SELECT COUNT(*) FROM explorer_group_member gm WHERE gm.group_id = g.group_id) AS member_count,
         (SELECT COUNT(*) FROM explorer_group_member gm WHERE gm.group_id = g.group_id AND ${activeGroupMembership}) AS active_count,
         (SELECT COUNT(*) FROM explorer_group_member gm
            WHERE gm.group_id = g.group_id AND gm.source = 'luckperms') AS luckperms_count
  FROM explorer_group g
) g`;

export async function listGroups(): Promise<GroupSummary[]> {
  const r = await sql<GroupRow>`SELECT g.* FROM ${GROUP_SUMMARY} ORDER BY g.name`.execute(db);
  return r.rows.map(mapGroupRow);
}

/** Groups for the admin table: `where` and `orderBy` compiled against the derived table `g`. */
export async function listGroupsWhere(where: RawBuilder<unknown>, orderBy: RawBuilder<unknown>, limit: number, offset: number): Promise<GroupSummary[]> {
  const r = await sql<GroupRow>`SELECT g.* FROM ${GROUP_SUMMARY} ${where} ${orderBy} LIMIT ${limit} OFFSET ${offset}`.execute(db);
  return r.rows.map(mapGroupRow);
}

export async function countGroupsWhere(where: RawBuilder<unknown>): Promise<number> {
  const r = await sql<{ total: number | string }>`SELECT COUNT(*) AS total FROM ${GROUP_SUMMARY} ${where}`.execute(db);
  return Number(r.rows[0]?.total ?? 0);
}

/** Single group by id, or null. Queries by id rather than scanning listGroups(). */
export async function selectGroupById(groupId: number, executor: Executor = db): Promise<GroupSummary | null> {
  const r = await sql<GroupRow>`SELECT g.* FROM ${GROUP_SUMMARY} WHERE g.group_id = ${groupId}`.execute(executor);
  return r.rows[0] ? mapGroupRow(r.rows[0]) : null;
}

/**
 * Most-recent player UUID for a current name (case-insensitive), or null. The
 * UUID-vs-name branching and validation live in the service.
 */
export async function findPlayerUuidByName(name: string): Promise<string | null> {
  const r = await sql<{ player_uuid_bin: Buffer }>`
    SELECT player_uuid_bin FROM economy_players
    WHERE LOWER(current_name) = LOWER(${name})
    ORDER BY player_uuid_bin LIMIT 1
  `.execute(db);
  return r.rows[0] ? binToUuid(r.rows[0].player_uuid_bin) : null;
}

export async function createGroup(args: {
  name: string;
  description: string | null;
  createdBy: string | null;
}): Promise<number> {
  const r = await sql`
    INSERT INTO explorer_group (name, description, created_by_uuid_bin)
    VALUES (${args.name}, ${args.description}, ${args.createdBy ? uuidToBin(args.createdBy) : null})
  `.execute(db);
  return Number(r.insertId ?? 0);
}

export async function deleteGroup(groupId: number, executor: Executor = db): Promise<void> {
  // capabilities + members cascade via FK ON DELETE CASCADE.
  await sql`DELETE FROM explorer_group WHERE group_id = ${groupId}`.execute(executor);
}

/** Deletes every capability row for a group. */
export async function deleteGroupCapabilities(groupId: number, executor: Executor = db): Promise<void> {
  await sql`DELETE FROM explorer_group_capability WHERE group_id = ${groupId}`.execute(executor);
}

/** Inserts a single capability row for a group. */
export async function insertGroupCapability(
  groupId: number,
  capability: string,
  executor: Executor = db,
): Promise<void> {
  await sql`
    INSERT INTO explorer_group_capability (group_id, capability) VALUES (${groupId}, ${capability})
  `.execute(executor);
}

export async function setLuckpermsNode(groupId: number, node: string | null, executor: Executor = db): Promise<void> {
  await sql`UPDATE explorer_group SET luckperms_node = ${node} WHERE group_id = ${groupId}`.execute(executor);
}

const memberColumns = defineColumnMap({
  table: adminGroupMembersTable,
  filters: { source: { column: sql`gm.source` } },
  sorts: { name: sql`fp.current_name` },
  // A player can have multiple sources, so UUID alone is not a unique order.
  tiebreak: { column: sql`gm.player_uuid_bin, gm.source`, dir: 'asc' },
  search: (q) => {
    const like = likeContains(q);
    return sql<SqlBool>`(fp.current_name LIKE ${like} OR HEX(gm.player_uuid_bin) LIKE ${likeContains(q.replaceAll('-', ''))})`;
  },
});

export async function countGroupMembers(groupId: number, state: AdminGroupMembersState): Promise<number> {
  const c = compileTableState(state, memberColumns);
  const r = await sql<{ total: number | string }>`
    SELECT COUNT(*) AS total FROM explorer_group_member gm
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = gm.player_uuid_bin
    WHERE gm.group_id = ${groupId} ${c.conditions.length ? sql`AND ${sql.join(c.conditions, sql` AND `)}` : sql``}
  `.execute(db);
  return Number(r.rows[0]?.total ?? 0);
}

export async function listGroupMembers(groupId: number, state: AdminGroupMembersState): Promise<GroupMember[]> {
  const c = compileTableState(state, memberColumns);
  const r = await sql<{
    player_uuid_bin: Buffer;
    name: string | null;
    source: 'manual' | 'luckperms' | 'business' | 'realty';
    added_at: Date;
    sync_active: number;
  }>`
    SELECT gm.player_uuid_bin, fp.current_name AS name, gm.source, gm.added_at,
           ${activeGroupMembership} AS sync_active
    FROM explorer_group_member gm
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = gm.player_uuid_bin
    WHERE gm.group_id = ${groupId}
    ${c.conditions.length ? sql`AND ${sql.join(c.conditions, sql` AND `)}` : sql``}
    ${c.orderBy}
    LIMIT ${state.limit} OFFSET ${(state.page - 1) * state.limit}
  `.execute(db);
  return r.rows.map((row) => ({
    uuid: binToUuid(row.player_uuid_bin),
    name: row.name,
    source: row.source,
    addedAt: row.added_at,
    syncActive: Number(row.sync_active) === 1,
  }));
}

export async function addManualMember(groupId: number, playerUuid: string, addedBy: string | null, executor: Executor = db): Promise<void> {
  await sql`
    INSERT IGNORE INTO explorer_group_member (group_id, player_uuid_bin, source, added_by_uuid_bin)
    VALUES (${groupId}, ${uuidToBin(playerUuid)}, 'manual', ${addedBy ? uuidToBin(addedBy) : null})
  `.execute(executor);
}

/** Removes a manual grant only — LuckPerms-sourced rows are owned by the cron. */
export async function removeManualMember(groupId: number, playerUuid: string, executor: Executor = db): Promise<void> {
  await sql`
    DELETE FROM explorer_group_member
    WHERE group_id = ${groupId} AND player_uuid_bin = ${uuidToBin(playerUuid)} AND source = 'manual'
  `.execute(executor);
}

/** Called in the same transaction as a source-node edit. */
export async function clearSyncedMembers(groupId: number, executor: Executor = db): Promise<void> {
  await sql`DELETE FROM explorer_group_member WHERE group_id = ${groupId} AND source = 'luckperms'`.execute(executor);
}

/** Live and total members per source, for the group page's sync summary. */
export async function countGroupMembersBySource(groupId: number): Promise<{ source: GroupMember['source']; total: number; active: number }[]> {
  const r = await sql<{ source: GroupMember['source']; total: number | string; active: number | string | null }>`
    SELECT gm.source, COUNT(*) AS total, SUM(${activeGroupMembership}) AS active
    FROM explorer_group_member gm
    WHERE gm.group_id = ${groupId}
    GROUP BY gm.source
  `.execute(db);
  return r.rows.map((row) => ({ source: row.source, total: Number(row.total), active: Number(row.active ?? 0) }));
}

/** Set (insert or replace) a group's firm or region source. */
export async function upsertGroupSync(groupId: number, source: GroupSyncSource['source'], selector: string, executor: Executor = db): Promise<void> {
  await sql`
    INSERT INTO explorer_group_sync (group_id, source, selector) VALUES (${groupId}, ${source}, ${selector})
    ON DUPLICATE KEY UPDATE selector = VALUES(selector)
  `.execute(executor);
}

export async function deleteGroupSync(groupId: number, source: GroupSyncSource['source'], executor: Executor = db): Promise<void> {
  await sql`DELETE FROM explorer_group_sync WHERE group_id = ${groupId} AND source = ${source}`.execute(executor);
}

/**
 * Drop one firm/region source's synced members, in the same transaction as the
 * source edit. The plugin's compare-on-insert refresh (the selector must match)
 * keeps a reconciliation that started under the old selector from restoring them.
 */
export async function clearSourceMembers(groupId: number, source: GroupSyncSource['source'], executor: Executor = db): Promise<void> {
  await sql`DELETE FROM explorer_group_member WHERE group_id = ${groupId} AND source = ${source}`.execute(executor);
}

/** Indexed point lookup for mutation audit snapshots; never load the whole group. */
export async function isManualGroupMember(groupId: number, uuid: string): Promise<boolean> {
  const r = await sql`SELECT 1 FROM explorer_group_member
    WHERE group_id = ${groupId} AND player_uuid_bin = ${uuidToBin(uuid)} AND source = 'manual' LIMIT 1`.execute(db);
  return r.rows.length > 0;
}
