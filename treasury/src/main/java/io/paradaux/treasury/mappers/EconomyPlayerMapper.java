package io.paradaux.treasury.mappers;

import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * The unified player directory ({@code economy_players}): UUID ↔ current name and
 * last-login epoch. Treasury owns the writer; reads back a deterministic
 * name → UUID mapping that doesn't depend on Bukkit usercache state (PAR-35,
 * the foundation for PAR-150/PAR-149). UUIDs marshal to {@code BINARY(16)} via
 * the global {@code UuidBinaryTypeHandler}.
 */
@Mapper
public interface EconomyPlayerMapper {

    /** Ensure a UUID row exists without resolving conflicts through another player's name. */
    @Insert("""
            INSERT INTO economy_players (player_uuid_bin, current_name)
            VALUES (#{playerUuid}, HEX(#{playerUuid}))
            ON DUPLICATE KEY UPDATE player_uuid_bin = player_uuid_bin
            """)
    void ensurePlayer(@Param("playerUuid") UUID playerUuid);

    /** Update only the specified UUID; the name index rejects another player's name. */
    @Update("""
            UPDATE economy_players
               SET current_name = #{currentName}, last_login_epoch = #{epochSeconds},
                   last_seen = CURRENT_TIMESTAMP
             WHERE player_uuid_bin = #{playerUuid}
            """)
    void updateLogin(
            @Param("playerUuid") UUID playerUuid,
            @Param("currentName") String currentName,
            @Param("epochSeconds") long epochSeconds);

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

    /**
     * Resolves a current name (case-insensitive) to a UUID, or {@code null} if unknown.
     * A {@code HEX(uuid)} placeholder row (name unknown/released) is not a name and
     * never resolves.
     */
    @Select("""
            SELECT player_uuid_bin FROM economy_players
             WHERE name_lower = LOWER(#{name}) AND current_name <> HEX(player_uuid_bin)
            """)
    UUID findUuidByName(@Param("name") String name);

    /**
     * The player's last-known name, or {@code null} if the player isn't in the
     * directory or only holds a {@code HEX(uuid)} placeholder (name unknown/released).
     */
    @Select("""
            SELECT current_name FROM economy_players
             WHERE player_uuid_bin = #{playerUuid} AND current_name <> HEX(player_uuid_bin)
            """)
    String findNameByUuid(@Param("playerUuid") UUID playerUuid);
}
