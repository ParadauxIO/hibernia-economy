package io.paradaux.treasuryrestapi.mapper;

import io.paradaux.treasuryrestapi.model.Account;
import io.paradaux.treasuryrestapi.model.AccountBalance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.UUID;

@Mapper
public interface AccountMapper {

    @Select("SELECT account_id, account_type, is_archived AS archived, requires_authorization, allow_overdraft, credit_limit " +
            "FROM accounts WHERE account_id = #{accountId}")
    Account findById(@Param("accountId") long accountId);

    /**
     * Resolves a player's PERSONAL account id from their owner UUID, or null if
     * they have no non-archived personal account. UUID ↔ BINARY(16) is handled
     * by the registered {@code UuidTypeHandler}. Used to route /transfers/to-player.
     */
    @Select("SELECT account_id FROM accounts " +
            "WHERE account_type = 'PERSONAL' AND owner_uuid_bin = #{ownerUuid} AND is_archived = 0 LIMIT 1")
    Long findPersonalAccountIdByOwner(@Param("ownerUuid") UUID ownerUuid);

    /**
     * Whether a non-archived GOVERNMENT account with this display name exists.
     * Used to detect player↔GOVERNMENT name collisions on bare-name resolution
     * (PAR-144), mirroring the in-game guard.
     */
    @Select("SELECT COUNT(*) > 0 FROM accounts " +
            "WHERE account_type = 'GOVERNMENT' AND display_name = #{name} AND is_archived = 0")
    boolean existsGovernmentAccountByName(@Param("name") String name);

    /**
     * Non-locking balance read via the account_balances view (COALESCE to 0.00).
     * Returns null if no account with that ID exists.
     */
    @Select("SELECT account_id, balance FROM account_balances WHERE account_id = #{accountId}")
    AccountBalance findBalance(@Param("accountId") long accountId);

    /**
     * Pessimistic lock on the balance row. Must be called within a transaction.
     * Used before the overdraft check and balance update in the transfer flow.
     */
    @Select("SELECT account_id, balance, version " +
            "FROM account_balances_mat WHERE account_id = #{accountId} FOR UPDATE")
    AccountBalance findBalanceForUpdate(@Param("accountId") long accountId);

    /**
     * Marks an account archived (read-only). Used by the admin firm-disband flow
     * after sweeping the balance, mirroring the in-game plugin's account archival.
     * Idempotent: re-archiving an archived account is a no-op.
     */
    @Update("UPDATE accounts SET is_archived = 1 WHERE account_id = #{accountId}")
    int archiveAccount(@Param("accountId") long accountId);

    /** Admin: un-archive (re-activate) an account. */
    @Update("UPDATE accounts SET is_archived = 0 WHERE account_id = #{accountId}")
    int unarchiveAccount(@Param("accountId") long accountId);

    /** Admin: rename any account. */
    @Update("UPDATE accounts SET display_name = #{displayName} WHERE account_id = #{accountId}")
    int updateDisplayName(@Param("accountId") long accountId, @Param("displayName") String displayName);

    /** Admin: reassign an account's owner (kept separate from updateAccount so general
     *  updates can't move ownership — mirrors PAR-140's dedicated updateOwner). */
    @Update("UPDATE accounts SET owner_uuid_bin = #{ownerUuid} WHERE account_id = #{accountId}")
    int updateOwner(@Param("accountId") long accountId, @Param("ownerUuid") UUID ownerUuid);

    /** The account's type (PERSONAL|BUSINESS|GOVERNMENT|SYSTEM), or null if absent. */
    @Select("SELECT account_type FROM accounts WHERE account_id = #{accountId}")
    String findAccountType(@Param("accountId") long accountId);

    /** Admin summary of an account's mutable attributes (for the admin write responses). */
    @Select("SELECT account_id, account_type, display_name, is_archived AS archived, " +
            "       owner_uuid_bin AS owner_uuid " +
            "FROM accounts WHERE account_id = #{accountId}")
    io.paradaux.treasuryrestapi.model.AccountAdminSummary findAdminSummary(@Param("accountId") long accountId);

}
