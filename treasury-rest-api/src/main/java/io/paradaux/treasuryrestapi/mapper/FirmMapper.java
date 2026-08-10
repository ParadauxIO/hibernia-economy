package io.paradaux.treasuryrestapi.mapper;

import io.paradaux.treasuryrestapi.model.Firm;
import io.paradaux.treasuryrestapi.model.FirmAccountSummary;
import io.paradaux.treasuryrestapi.model.FirmEmployee;
import io.paradaux.treasuryrestapi.model.FirmRole;
import io.paradaux.treasuryrestapi.model.FirmRolePermissionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface FirmMapper {

    /**
     * Resolves a player's UUID from their in-game name (case-insensitive) via the
     * unified {@code economy_players} directory (PAR-35), or null if no player by
     * that name is known. {@code name_lower} is a generated LOWER(current_name)
     * column with a unique index, so the match is exact and index-served.
     */
    @Select("SELECT player_uuid_bin FROM economy_players WHERE name_lower = LOWER(#{name})")
    UUID findPlayerUuidByName(@Param("name") String name);

    /**
     * Returns the last-known IGN from the {@code economy_players} directory for the
     * given UUID, or null if the player hasn't been seen on the server yet.
     */
    @Select("SELECT current_name FROM economy_players WHERE player_uuid_bin = #{uuid}")
    String findPlayerNameByUuid(@Param("uuid") UUID uuid);

    /**
     * Returns a count > 0 if the given account currently belongs to the
     * given firm. Used to authorise BUSINESS API key access to firm-owned
     * accounts.
     *
     * <p>{@code firm_accounts} is soft-deleted: a removed link keeps the
     * row with {@code removed_at} set. Filter on {@code removed_at IS
     * NULL} so transferring out of a firm-account link revokes API
     * authorisation immediately.
     */
    @Select("SELECT COUNT(*) FROM firm_accounts " +
            "WHERE firm_id = #{firmId} AND account_id = #{accountId} " +
            "  AND removed_at IS NULL")
    int isFirmAccount(@Param("firmId") long firmId, @Param("accountId") long accountId);

    @Select("SELECT firm_id, display_name, proprietor_uuid_bin AS proprietorUuid, " +
            "       discord_url, hq_region, default_account_id, is_archived AS archived, created_at " +
            "FROM firm WHERE firm_id = #{firmId}")
    Firm findFirmById(@Param("firmId") long firmId);

    @Select("SELECT firm_id, display_name, proprietor_uuid_bin AS proprietorUuid, " +
            "       discord_url, hq_region, default_account_id, is_archived AS archived, created_at " +
            "FROM firm WHERE display_name = #{displayName}")
    Firm findFirmByDisplayName(@Param("displayName") String displayName);

    /**
     * Returns all currently employed staff (left_at IS NULL), ordered by role rank then join date.
     * LEFT JOINs economy_players for the IGN — null if the player hasn't been seen on the server yet.
     *
     * <p>{@code firm_role} is soft-deleted; filter the join on
     * {@code fr.deleted_at IS NULL} so an employee whose role was deleted
     * doesn't appear under a stale role name.
     */
    @Select("SELECT fe.player_uuid_bin AS playerUuid, fp.current_name AS playerName, " +
            "       fr.name AS roleName, fe.joined_at AS joinedAt " +
            "FROM firm_employee fe " +
            "JOIN firm_role fr ON fr.role_id = fe.role_id AND fr.deleted_at IS NULL " +
            "LEFT JOIN economy_players fp ON fp.player_uuid_bin = fe.player_uuid_bin " +
            "WHERE fe.firm_id = #{firmId} AND fe.left_at IS NULL " +
            "ORDER BY fr.rank_order, fe.joined_at")
    List<FirmEmployee> listFirmEmployees(@Param("firmId") long firmId);

    @Select("SELECT role_id, name, rank_order, is_proprietor_like AS proprietorLike, is_default AS defaultRole " +
            "FROM firm_role WHERE firm_id = #{firmId} AND deleted_at IS NULL ORDER BY rank_order")
    List<FirmRole> listFirmRoles(@Param("firmId") long firmId);

    /**
     * Returns all permission rows for every role belonging to this firm in one query.
     * The service groups these by roleId and attaches them to the role list.
     *
     * <p>Both {@code firm_role} and {@code firm_role_permission} are
     * soft-deleted; filter both sides.
     */
    @Select("SELECT rp.role_id, rp.permission " +
            "FROM firm_role_permission rp " +
            "JOIN firm_role r ON r.role_id = rp.role_id " +
            "WHERE r.firm_id = #{firmId} " +
            "  AND r.deleted_at IS NULL " +
            "  AND rp.deleted_at IS NULL")
    List<FirmRolePermissionRow> listFirmRolePermissions(@Param("firmId") long firmId);

    /**
     * Sums the balances of all accounts currently belonging to the firm.
     * Returns 0.00 if the firm has no active accounts or none have a balance row yet.
     */
    @Select("SELECT COALESCE(SUM(abm.balance), 0.00) " +
            "FROM firm_accounts fa " +
            "LEFT JOIN account_balances_mat abm ON abm.account_id = fa.account_id " +
            "WHERE fa.firm_id = #{firmId} AND fa.removed_at IS NULL")
    java.math.BigDecimal sumFirmBalance(@Param("firmId") long firmId);

    /**
     * Lists all accounts currently owned by the firm, including their current balance.
     * Results are ordered by account_id for stable pagination if added later.
     */
    @Select("SELECT a.account_id, a.display_name, a.account_type, a.is_archived AS archived, " +
            "       COALESCE(abm.balance, 0.00) AS balance " +
            "FROM firm_accounts fa " +
            "JOIN accounts a ON a.account_id = fa.account_id " +
            "LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id " +
            "WHERE fa.firm_id = #{firmId} AND fa.removed_at IS NULL " +
            "ORDER BY a.account_id")
    List<FirmAccountSummary> listFirmAccounts(@Param("firmId") long firmId);

    /**
     * Full update of all patchable firm fields.
     * The service merges current values before calling this so untouched fields are preserved.
     */
    @Update("UPDATE firm SET display_name = #{displayName}, discord_url = #{discordUrl}, " +
            "               hq_region = #{hqRegion} " +
            "WHERE firm_id = #{firmId}")
    int updateFirm(@Param("firmId") long firmId,
                   @Param("displayName") String displayName,
                   @Param("discordUrl") String discordUrl,
                   @Param("hqRegion") String hqRegion);

    @Update("UPDATE accounts SET display_name = #{displayName} WHERE account_id = #{accountId}")
    int updateAccountDisplayName(@Param("accountId") long accountId, @Param("displayName") String displayName);

    // ── admin firm management (PAR-210) ──────────────────────────────────────────

    /**
     * Soft-deletes a firm↔account link (stamps {@code removed_at}), mirroring the
     * plugin's {@code FirmAccountsMapper.removeFirmAccount}. Only affects a live link.
     */
    @Update("UPDATE firm_accounts SET removed_at = CURRENT_TIMESTAMP " +
            "WHERE firm_id = #{firmId} AND account_id = #{accountId} AND removed_at IS NULL")
    int removeFirmAccount(@Param("firmId") long firmId, @Param("accountId") long accountId);

    /**
     * Archives a firm: {@code is_archived = 1} and clears the now-dangling
     * {@code default_account_id}, mirroring the plugin's {@code FirmMapper.archiveFirm}.
     */
    @Update("UPDATE firm SET is_archived = 1, default_account_id = NULL WHERE firm_id = #{firmId}")
    int archiveFirm(@Param("firmId") long firmId);

    /** Renames a firm (display_name only), mirroring the plugin's rename. */
    @Update("UPDATE firm SET display_name = #{displayName} WHERE firm_id = #{firmId}")
    int renameFirm(@Param("firmId") long firmId, @Param("displayName") String displayName);

    /**
     * Counts firms (other than {@code firmId}) sharing the given display name,
     * case-insensitively — the uniqueness guard for rename. Matches the plugin's
     * case-insensitive collision check and the {@code uq_firm_display_name} constraint.
     */
    @Select("SELECT COUNT(*) FROM firm WHERE LOWER(display_name) = LOWER(#{displayName}) AND firm_id <> #{firmId}")
    int countOtherFirmsByDisplayName(@Param("firmId") long firmId, @Param("displayName") String displayName);
}
