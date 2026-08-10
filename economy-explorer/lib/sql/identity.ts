import 'server-only';
import { db, binToUuid, uuidToBin } from '@/lib/db';

export interface ExplorerIdentity {
  keycloakSub: string;
  playerUuid: string;
  minecraftName: string | null;
  linkedAt: Date;
  linkedBy: string | null;
}

/** Mirrors treasury-rest-api's ExplorerIdentityMapper.findBySub. */
export async function findIdentityBySub(sub: string): Promise<ExplorerIdentity | null> {
  const row = await db
    .selectFrom('explorer_identity')
    .selectAll()
    .where('keycloak_sub', '=', sub)
    .executeTakeFirst();
  if (!row) return null;
  return {
    keycloakSub: row.keycloak_sub,
    playerUuid: binToUuid(row.player_uuid_bin),
    minecraftName: row.minecraft_name,
    linkedAt: row.linked_at,
    linkedBy: row.linked_by,
  };
}

/**
 * Mirrors ExplorerIdentityMapper.upsert. Called on first authenticated request
 * once the access token carries a minecraft_uuid claim (interim: populated by
 * in-game link flow; future: by the Minecraft IdP).
 */
export async function upsertIdentity(args: {
  sub: string;
  playerUuid: string;
  minecraftName: string | null;
  linkedBy: string | null;
}): Promise<void> {
  await db
    .insertInto('explorer_identity')
    .values({
      keycloak_sub: args.sub,
      player_uuid_bin: uuidToBin(args.playerUuid),
      minecraft_name: args.minecraftName,
      linked_by: args.linkedBy,
    })
    .onDuplicateKeyUpdate({
      player_uuid_bin: uuidToBin(args.playerUuid),
      minecraft_name: args.minecraftName ?? undefined,
    })
    .execute();
}
