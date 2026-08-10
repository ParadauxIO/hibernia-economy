package io.paradaux.business.mappers;

import io.paradaux.business.model.FirmAccount;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Firm-↔-Treasury-account links.
 *
 * <p><b>Soft-delete semantics.</b> Removing an account from a firm sets
 * {@code removed_at} instead of deleting the row. Re-adding a previously
 * removed link uses {@code INSERT … ON DUPLICATE KEY UPDATE} to clear
 * {@code removed_at}; reads filter on {@code IS NULL}.
 */
public interface FirmAccountsMapper {

    @Insert("""
        INSERT INTO firm_accounts (firm_id, account_id)
        VALUES (#{firmId}, #{accountId})
        ON DUPLICATE KEY UPDATE removed_at = NULL
        """)
    void insertFirmAccount(@Param("firmId") Integer firmId, @Param("accountId") Integer accountId);

    @Select("""
        SELECT firm_id    AS firmId,
               account_id AS accountId,
               added_at   AS addedAt
        FROM firm_accounts
        WHERE firm_id = #{firmId}
          AND removed_at IS NULL
        ORDER BY added_at DESC
        """)
    List<FirmAccount> listAccountsByFirm(@Param("firmId") Integer firmId);

    @Select("""
        SELECT COUNT(*) > 0
        FROM firm_accounts
        WHERE firm_id = #{firmId}
          AND account_id = #{accountId}
          AND removed_at IS NULL
        """)
    boolean isFirmAccount(@Param("firmId") Integer firmId, @Param("accountId") Integer accountId);

    /** Soft-removes the firm-↔-account link by stamping {@code removed_at}. */
    @Update("""
        UPDATE firm_accounts
           SET removed_at = CURRENT_TIMESTAMP
         WHERE firm_id = #{firmId}
           AND account_id = #{accountId}
           AND removed_at IS NULL
        """)
    int removeFirmAccount(@Param("firmId") Integer firmId, @Param("accountId") Integer accountId);

    @Select("""
        SELECT firm_id    AS firmId,
               account_id AS accountId,
               added_at   AS addedAt
        FROM firm_accounts
        WHERE firm_id = #{firmId}
          AND account_id = #{accountId}
          AND removed_at IS NULL
        """)
    FirmAccount getFirmAccount(@Param("firmId") Integer firmId, @Param("accountId") Integer accountId);

    @Select("""
        SELECT account_id
        FROM firm_accounts
        WHERE firm_id = #{firmId}
          AND removed_at IS NULL
        LIMIT 1
        """)
    Integer getAnyAccountId(@Param("firmId") Integer firmId);

    @Select("""
        SELECT firm_id
        FROM firm_accounts
        WHERE account_id = #{accountId}
          AND removed_at IS NULL
        LIMIT 1
        """)
    Integer getFirmIdByAccountId(@Param("accountId") Integer accountId);

    /**
     * Every live firm-↔-account link across all firms, in one query. Backs the
     * firm balance leaderboard (/firm baltop), which sums each firm's account
     * balances; doing this per-firm would be N+1. {@code added_at} is left unset.
     */
    @Select("""
        SELECT firm_id    AS firmId,
               account_id AS accountId
        FROM firm_accounts
        WHERE removed_at IS NULL
        """)
    List<FirmAccount> listActiveAccountLinks();
}
