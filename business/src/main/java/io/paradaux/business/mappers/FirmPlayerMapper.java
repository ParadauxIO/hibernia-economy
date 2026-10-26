package io.paradaux.business.mappers;

import io.paradaux.business.model.FirmPlayer;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Read-only access to the player-directory cache. The directory is the
 * {@code economy_players} table, which is owned and written exclusively by
 * Treasury's login listener (PAR-35); Business only reads it to resolve
 * names ↔ UUIDs. Business {@code depend: Treasury}, so the writer is always
 * present and the cache is populated on every join.
 */
public interface FirmPlayerMapper {

    @Select("""
        SELECT bin_to_uuid(player_uuid_bin) AS playerUuid,
               current_name                 AS currentName,
               name_lower                   AS nameLower,
               first_seen                   AS firstSeen,
               last_seen                    AS lastSeen
        FROM economy_players
        WHERE player_uuid_bin = uuid_to_bin(#{playerUuid})
        """)
    FirmPlayer getByUuid(@Param("playerUuid") String playerUuid);

    @Select("""
        SELECT bin_to_uuid(player_uuid_bin) AS playerUuid,
               current_name                 AS currentName,
               name_lower                   AS nameLower,
               first_seen                   AS firstSeen,
               last_seen                    AS lastSeen
        FROM economy_players
        WHERE name_lower = LOWER(#{name})
        """)
    FirmPlayer getByName(@Param("name") String name);

    @Select("""
        SELECT bin_to_uuid(player_uuid_bin) AS playerUuid,
               current_name                 AS currentName,
               name_lower                   AS nameLower,
               first_seen                   AS firstSeen,
               last_seen                    AS lastSeen
        FROM economy_players
        WHERE name_lower LIKE CONCAT(LOWER(#{prefix}), '%')
        ORDER BY last_seen DESC
        LIMIT #{limit}
        """)
    List<FirmPlayer> searchByPrefix(@Param("prefix") String prefix,
                                    @Param("limit") int limit);

}
