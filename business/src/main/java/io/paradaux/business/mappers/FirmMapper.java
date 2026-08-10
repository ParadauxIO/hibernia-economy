package io.paradaux.business.mappers;

import io.paradaux.business.model.Firm;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface FirmMapper {

    @Insert("""
            INSERT INTO firm (display_name, proprietor_uuid_bin)
            VALUES (#{displayName}, uuid_to_bin(#{proprietorUuid}))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "firmId", keyColumn = "firm_id")
    void createFirm(Firm firm);

    @Lang(org.apache.ibatis.scripting.xmltags.XMLLanguageDriver.class)
    @Update("""
            <script>
            UPDATE firm
            <set>
              <if test="displayName != null">display_name = #{displayName},</if>
              <if test="proprietorUuid != null">proprietor_uuid_bin = uuid_to_bin(#{proprietorUuid}),</if>
              <if test="discordUrl != null">discord_url = #{discordUrl},</if>
              <if test="hqRegion != null">hq_region = #{hqRegion},</if>
              <if test="defaultAccountId != null">default_account_id = #{defaultAccountId},</if>
              <if test="archived != null">is_archived = #{archived},</if>
              updated_at = CURRENT_TIMESTAMP
            </set>
            WHERE firm_id = #{firmId}
            </script>
            """)
    void updateFirm(Firm firm);

    /**
     * Archives a firm and explicitly clears its default_account_id. Used by
     * {@code disbandFirm}, where the firm's accounts have just been archived
     * in Treasury and removed from {@code firm_accounts}; leaving the
     * default_account_id set would point at an archived account.
     *
     * <p>The {@code AND is_archived = 0} guard makes the transition atomic:
     * only the first caller to flip the flag sees a non-zero affected-row count,
     * so two concurrent disbands cannot both proceed to drain the same accounts.
     * Returns the number of rows updated (1 for the winner, 0 for a loser or an
     * already-archived firm).
     */
    @Update("""
            UPDATE firm
            SET is_archived = 1,
                default_account_id = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE firm_id = #{firmId}
              AND is_archived = 0
            """)
    int archiveFirm(@Param("firmId") int firmId);

    @Select("""
            SELECT f.firm_id                     AS firmId,
                   f.display_name                AS displayName,
                   bin_to_uuid(f.proprietor_uuid_bin) AS proprietorUuid,
                   f.discord_url                 AS discordUrl,
                   f.hq_region                   AS hqRegion,
                   f.default_account_id          AS defaultAccountId,
                   f.is_archived                 AS archived,
                   f.created_at                  AS createdAt,
                   f.updated_at                  AS updatedAt
            FROM firm f
            WHERE f.proprietor_uuid_bin = uuid_to_bin(#{playerUuid})
               OR EXISTS (
                    SELECT 1
                    FROM firm_employee e
                    WHERE e.firm_id = f.firm_id
                      AND e.player_uuid_bin = uuid_to_bin(#{playerUuid})
                      AND e.is_current = 1
               )
            ORDER BY f.firm_id DESC
            """)
    List<Firm> listOwnedOrMemberFirms(@Param("playerUuid") String playerUuid);

    @Select("""
            SELECT firm_id                              AS firmId,
                   display_name                         AS displayName,
                   bin_to_uuid(proprietor_uuid_bin)     AS proprietorUuid,
                   discord_url                          AS discordUrl,
                   hq_region                            AS hqRegion,
                   default_account_id                   AS defaultAccountId,
                   is_archived                          AS archived,
                   created_at                           AS createdAt,
                   updated_at                           AS updatedAt
            FROM firm
            WHERE (#{includeArchived} = TRUE OR is_archived = 0)
            ORDER BY firm_id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Firm> listAllFiltered(@Param("limit") int limit,
                               @Param("offset") int offset,
                               @Param("includeArchived") boolean includeArchived);

    @Select("""
            SELECT COUNT(*) > 0
            FROM firm
            WHERE display_name = #{firmName}
              AND proprietor_uuid_bin = uuid_to_bin(#{playerUuid})
            """)
    boolean isProprietorByFirmName(@Param("firmName") String firmName,
                                   @Param("playerUuid") String playerUuid);

    @Select("""
            SELECT COUNT(*) > 0
            FROM firm
            WHERE firm_id = #{firmId}
              AND proprietor_uuid_bin = uuid_to_bin(#{playerUuid})
            """)
    boolean isProprietorByFirmId(@Param("firmId") int firmId,
                                 @Param("playerUuid") String playerUuid);

    @Select("""
            SELECT COUNT(*)
            FROM firm
            WHERE proprietor_uuid_bin = uuid_to_bin(#{playerUuid})
              AND is_archived = FALSE
            """)
    int getFirmsOwnedByCount(@Param("playerUuid") String playerUuid);

    @Select("""
            SELECT COUNT(1)
            FROM firm
            WHERE display_name = #{name}
            """)
    int getFirmsByNameCount(@Param("name") String name);

    /**
     * Seconds since this player most recently created a firm, or {@code null} if they
     * never have. Counts archived firms too (created_at survives a disband), so a
     * create/disband cycle can't bypass the creation cooldown. PAR-25.
     */
    @Select("""
            SELECT TIMESTAMPDIFF(SECOND, MAX(created_at), NOW())
            FROM firm
            WHERE proprietor_uuid_bin = uuid_to_bin(#{playerUuid})
            """)
    Long secondsSinceLastCreation(@Param("playerUuid") String playerUuid);

    @Select("""
              SELECT firm_id                         AS firmId,
                     display_name                    AS displayName,
                     bin_to_uuid(proprietor_uuid_bin) AS proprietorUuid,
                     discord_url                     AS discordUrl,
                     hq_region                       AS hqRegion,
                     default_account_id              AS defaultAccountId,
                     is_archived                     AS archived,
                     created_at                      AS createdAt,
                     updated_at                      AS updatedAt
                FROM firm
               WHERE firm_id = #{firmId}
               LIMIT 1
            """)
    Firm getFirmById(@Param("firmId") int firmId);

    @Select("""
              SELECT firm_id                         AS firmId,
                     display_name                    AS displayName,
                     bin_to_uuid(proprietor_uuid_bin) AS proprietorUuid,
                     discord_url                     AS discordUrl,
                     hq_region                       AS hqRegion,
                     default_account_id              AS defaultAccountId,
                     is_archived                     AS archived,
                     created_at                      AS createdAt,
                     updated_at                      AS updatedAt
                FROM firm
               WHERE display_name = #{name}
               LIMIT 1
            """)
    Firm getFirmByName(@Param("name") String name);

    @Select("""
            SELECT firm_id                              AS firmId,
                   display_name                         AS displayName,
                   bin_to_uuid(proprietor_uuid_bin)     AS proprietorUuid,
                   discord_url                          AS discordUrl,
                   hq_region                            AS hqRegion,
                   default_account_id                   AS defaultAccountId,
                   is_archived                          AS archived,
                   created_at                           AS createdAt,
                   updated_at                           AS updatedAt
            FROM firm
            WHERE is_archived = 0
            ORDER BY firm_id
            """)
    List<Firm> listAllActive();

}
