package io.paradaux.treasury.mappers;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
     * otherwise the {@code UNIQUE(name_lower)} constraint collides. Matched on the
     * indexed {@code name_lower}.
     *
     * <p>Only the name is released: the row is kept and renamed to the same
     * {@code HEX(uuid)} placeholder the V17 backfill seeds (32 chars, unique, never a
     * real IGN), so the previous holder keeps {@code last_login_epoch} and
     * {@code first_seen}. Deleting the row made their next login look like a first
     * login and skipped the balance tax owed for the whole elapsed period. The
     * placeholder self-heals: their next login upserts the real name back.
     * {@code last_seen = last_seen} stops {@code ON UPDATE CURRENT_TIMESTAMP} from
     * bumping a timestamp for a player who was not seen.
     */
    @Update("""
            UPDATE economy_players
               SET current_name = HEX(player_uuid_bin),
                   last_seen    = last_seen
             WHERE name_lower = LOWER(#{name}) AND player_uuid_bin <> #{keepUuid}
            """)
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
