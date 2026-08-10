package io.paradaux.treasury.mappers;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/**
 * The unified player directory ({@code economy_players}): UUID ↔ current name and
 * last-login epoch. Treasury owns the writer; reads back a deterministic
 * name → UUID mapping that doesn't depend on Bukkit usercache state (PAR-35,
 * the foundation for PAR-150/PAR-149). UUIDs marshal to {@code BINARY(16)} via
 * the global {@code UuidBinaryTypeHandler}.
 */
@Mapper
public interface EconomyPlayerMapper {

    /** Upserts the player's current name + last-login epoch, bumping {@code last_seen}. */
    @Insert("""
            INSERT INTO economy_players (player_uuid_bin, current_name, last_login_epoch)
            VALUES (#{playerUuid}, #{currentName}, #{epochSeconds})
            ON DUPLICATE KEY UPDATE current_name     = VALUES(current_name),
                                    last_login_epoch = VALUES(last_login_epoch),
                                    last_seen        = CURRENT_TIMESTAMP
            """)
    void upsertLogin(@Param("playerUuid") UUID playerUuid,
                     @Param("currentName") String currentName,
                     @Param("epochSeconds") long epochSeconds);

    /**
     * Releases a name claimed by a <em>different</em> player. A Minecraft name
     * belongs to exactly one player at a time (Java via Mojang; Bedrock via the
     * unique {@code .}-prefixed Floodgate name), so on a name change/reuse the
     * previous holder's row must give it up before the new owner is upserted —
     * otherwise the {@code UNIQUE(name_lower)} constraint collides. The old holder
     * is re-added on their next login. Matched on the indexed {@code name_lower}.
     */
    @Delete("DELETE FROM economy_players WHERE name_lower = LOWER(#{name}) AND player_uuid_bin <> #{keepUuid}")
    void releaseNameFromOthers(@Param("name") String name, @Param("keepUuid") UUID keepUuid);

    /** The player's last-login epoch (seconds), or {@code null} if never recorded. */
    @Select("SELECT last_login_epoch FROM economy_players WHERE player_uuid_bin = #{playerUuid}")
    Long findLastLogin(@Param("playerUuid") UUID playerUuid);

    /**
     * Locking variant of {@link #findLastLogin}: takes a row lock so the
     * read-previous-then-write-new sequence in {@code recordLogin} is serialised at
     * the database level — correct across multiple writer processes, not just within
     * one JVM monitor (ADT-9).
     */
    @Select("SELECT last_login_epoch FROM economy_players WHERE player_uuid_bin = #{playerUuid} FOR UPDATE")
    Long findLastLoginForUpdate(@Param("playerUuid") UUID playerUuid);

    /** Resolves a current name (case-insensitive) to a UUID, or {@code null} if unknown. */
    @Select("SELECT player_uuid_bin FROM economy_players WHERE name_lower = LOWER(#{name})")
    UUID findUuidByName(@Param("name") String name);

    /** The player's last-known name, or {@code null} if the player isn't in the directory. */
    @Select("SELECT current_name FROM economy_players WHERE player_uuid_bin = #{playerUuid}")
    String findNameByUuid(@Param("playerUuid") UUID playerUuid);
}
