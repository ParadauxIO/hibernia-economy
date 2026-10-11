package io.paradaux.treasury.mappers;

import io.paradaux.treasury.model.economy.GovernmentFine;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface GovernmentFineMapper {

    @Insert("INSERT INTO government_fines " +
            "(player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin) " +
            "VALUES (#{playerUuid}, #{debtorAccountId}, #{govAccountId}, #{amount}, #{reason}, #{txnId}, #{issuedBy})")
    @Options(useGeneratedKeys = true, keyProperty = "fineId", keyColumn = "fine_id")
    int insertFine(GovernmentFine fine);

    @Select("SELECT fine_id, player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, " +
            "issued_by_uuid_bin, issued_at, revoked, revoked_by_uuid_bin, revoke_txn_id, revoked_at " +
            "FROM government_fines WHERE fine_id = #{fineId}")
    @Results(id = "fineResultMap", value = {
            @Result(column = "fine_id",             property = "fineId",        id = true),
            @Result(column = "player_uuid_bin",     property = "playerUuid"),
            @Result(column = "debtor_account_id",   property = "debtorAccountId"),
            @Result(column = "gov_account_id",      property = "govAccountId"),
            @Result(column = "amount",              property = "amount"),
            @Result(column = "reason",              property = "reason"),
            @Result(column = "txn_id",              property = "txnId"),
            @Result(column = "issued_by_uuid_bin",  property = "issuedBy"),
            @Result(column = "issued_at",           property = "issuedAt",      javaType = Instant.class),
            @Result(column = "revoked",             property = "revoked"),
            @Result(column = "revoked_by_uuid_bin", property = "revokedBy"),
            @Result(column = "revoke_txn_id",       property = "revokeTxnId"),
            @Result(column = "revoked_at",          property = "revokedAt",     javaType = Instant.class)
    })
    GovernmentFine findFineById(@Param("fineId") long fineId);

    /**
     * The fine recorded against a ledger debit ({@code uq_fines_txn}: at most one), or
     * {@code null}. A locking read, so it sees a fine committed by a concurrent
     * double-submit after this transaction's snapshot was taken. Only call it for a
     * txn that already existed — probing a not-yet-recorded txn_id would gap-lock the
     * index tail and deadlock concurrent fine inserts.
     */
    @Select("SELECT fine_id, player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, " +
            "issued_by_uuid_bin, issued_at, revoked, revoked_by_uuid_bin, revoke_txn_id, revoked_at " +
            "FROM government_fines WHERE txn_id = #{txnId} FOR UPDATE")
    @ResultMap("fineResultMap")
    GovernmentFine findFineByTxnIdForUpdate(@Param("txnId") long txnId);

    @Select("SELECT fine_id, player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, " +
            "issued_by_uuid_bin, issued_at, revoked, revoked_by_uuid_bin, revoke_txn_id, revoked_at " +
            "FROM government_fines WHERE player_uuid_bin = #{playerUuid} ORDER BY issued_at DESC")
    @ResultMap("fineResultMap")
    List<GovernmentFine> findFinesByPlayer(@Param("playerUuid") UUID playerUuid);

    @Select("SELECT fine_id, player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, " +
            "issued_by_uuid_bin, issued_at, revoked, revoked_by_uuid_bin, revoke_txn_id, revoked_at " +
            "FROM government_fines WHERE player_uuid_bin = #{playerUuid} AND revoked = 0 ORDER BY issued_at DESC")
    @ResultMap("fineResultMap")
    List<GovernmentFine> findActiveFinesByPlayer(@Param("playerUuid") UUID playerUuid);

    @Update("UPDATE government_fines SET revoked = 1, revoked_by_uuid_bin = #{revokedBy}, " +
            "revoke_txn_id = #{revokeTxnId}, revoked_at = CURRENT_TIMESTAMP(3) WHERE fine_id = #{fineId}")
    int revokeFine(@Param("fineId") long fineId,
                   @Param("revokedBy") UUID revokedBy,
                   @Param("revokeTxnId") long revokeTxnId);
}
