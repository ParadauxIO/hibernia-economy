import 'server-only';
import { Kysely, sql } from 'kysely';
import { db, type DB, uuidToBin, binToUuid } from '@/lib/db';

/**
 * Explorer group RBAC queries — a pure DAL: each export is a single Kysely query
 * (plus row→type mapping), no orchestration or validation. A player's effective
 * capabilities are the union of every group they belong to (see findCapabilities);
 * the rest is admin-tool CRUD for groups, their capabilities, their LuckPerms
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
  member_count: number | string;
  luckperms_count: number | string;
}

function mapGroupRow(row: GroupRow): GroupSummary {
  return {
    groupId: row.group_id,
    name: row.name,
    description: row.description,
    luckpermsNode: row.luckperms_node,
    capabilities: row.capabilities ? row.capabilities.split(',') : [],
    memberCount: Number(row.member_count),
    luckpermsMemberCount: Number(row.luckperms_count),
  };
}

export interface GroupSummary {
  groupId: number;
  name: string;
  description: string | null;
  luckpermsNode: string | null;
  capabilities: string[];
  memberCount: number;
  luckpermsMemberCount: number;
}

export interface GroupMember {
  uuid: string;
  name: string | null;
  source: 'manual' | 'luckperms';
  addedAt: Date;
}

/** The capabilities a player has via group membership. */
export async function findCapabilities(playerUuid: string): Promise<string[]> {
  const r = await sql<{ capability: string }>`
    SELECT DISTINCT gc.capability
    FROM explorer_group_member gm
    JOIN explorer_group_capability gc ON gc.group_id = gm.group_id
    WHERE gm.player_uuid_bin = ${uuidToBin(playerUuid)}
  `.execute(db);
  return r.rows.map((row) => row.capability);
}

export async function listGroups(): Promise<GroupSummary[]> {
  const r = await sql<GroupRow>`
    SELECT g.group_id, g.name, g.description, g.luckperms_node,
           (SELECT GROUP_CONCAT(gc.capability ORDER BY gc.capability)
              FROM explorer_group_capability gc WHERE gc.group_id = g.group_id) AS capabilities,
           (SELECT COUNT(*) FROM explorer_group_member gm WHERE gm.group_id = g.group_id) AS member_count,
           (SELECT COUNT(*) FROM explorer_group_member gm
              WHERE gm.group_id = g.group_id AND gm.source = 'luckperms') AS luckperms_count
    FROM explorer_group g
    ORDER BY g.name
  `.execute(db);
  return r.rows.map(mapGroupRow);
}

/** Single group by id, or null. Queries by id rather than scanning listGroups(). */
export async function selectGroupById(groupId: number): Promise<GroupSummary | null> {
  const r = await sql<GroupRow>`
    SELECT g.group_id, g.name, g.description, g.luckperms_node,
           (SELECT GROUP_CONCAT(gc.capability ORDER BY gc.capability)
              FROM explorer_group_capability gc WHERE gc.group_id = g.group_id) AS capabilities,
           (SELECT COUNT(*) FROM explorer_group_member gm WHERE gm.group_id = g.group_id) AS member_count,
           (SELECT COUNT(*) FROM explorer_group_member gm
              WHERE gm.group_id = g.group_id AND gm.source = 'luckperms') AS luckperms_count
    FROM explorer_group g
    WHERE g.group_id = ${groupId}
  `.execute(db);
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
}): Promise<void> {
  await sql`
    INSERT INTO explorer_group (name, description, created_by_uuid_bin)
    VALUES (${args.name}, ${args.description}, ${args.createdBy ? uuidToBin(args.createdBy) : null})
  `.execute(db);
}

export async function deleteGroup(groupId: number): Promise<void> {
  // capabilities + members cascade via FK ON DELETE CASCADE.
  await sql`DELETE FROM explorer_group WHERE group_id = ${groupId}`.execute(db);
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

export async function setLuckpermsNode(groupId: number, node: string | null): Promise<void> {
  await sql`UPDATE explorer_group SET luckperms_node = ${node} WHERE group_id = ${groupId}`.execute(db);
}

export async function listGroupMembers(groupId: number): Promise<GroupMember[]> {
  const r = await sql<{
    player_uuid_bin: Buffer;
    name: string | null;
    source: 'manual' | 'luckperms';
    added_at: Date;
  }>`
    SELECT gm.player_uuid_bin, fp.current_name AS name, gm.source, gm.added_at
    FROM explorer_group_member gm
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = gm.player_uuid_bin
    WHERE gm.group_id = ${groupId}
    ORDER BY gm.source, fp.current_name
  `.execute(db);
  return r.rows.map((row) => ({
    uuid: binToUuid(row.player_uuid_bin),
    name: row.name,
    source: row.source,
    addedAt: row.added_at,
  }));
}

export async function addManualMember(groupId: number, playerUuid: string, addedBy: string | null): Promise<void> {
  await sql`
    INSERT IGNORE INTO explorer_group_member (group_id, player_uuid_bin, source, added_by_uuid_bin)
    VALUES (${groupId}, ${uuidToBin(playerUuid)}, 'manual', ${addedBy ? uuidToBin(addedBy) : null})
  `.execute(db);
}

/** Removes a manual grant only — LuckPerms-sourced rows are owned by the cron. */
export async function removeManualMember(groupId: number, playerUuid: string): Promise<void> {
  await sql`
    DELETE FROM explorer_group_member
    WHERE group_id = ${groupId} AND player_uuid_bin = ${uuidToBin(playerUuid)} AND source = 'manual'
  `.execute(db);
}
