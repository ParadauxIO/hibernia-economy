package io.paradaux.treasury.mappers;

import io.paradaux.treasury.model.economy.GovernmentFine;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GovernmentFineMapper {

    @Insert(
            "INSERT INTO government_fines "
                    + "(player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, issued_by_uuid_bin) "
                    + "VALUES (#{value.playerUuid}, #{value.debtorAccountId}, #{value.govAccountId}, #{value.amount}, #{value.reason}, #{value.txnId}, #{value.issuedBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "fine_id")
    int insertFineRow(GeneratedKey<GovernmentFine> row);

    /** Inserts a snapshot and returns its generated ID without mutating it. */
    default long insertFine(GovernmentFine value) {
        GeneratedKey<GovernmentFine> row = new GeneratedKey<>(value);
        insertFineRow(row);
        return row.getId();
    }

    @Select("SELECT fine_id, player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, "
            + "issued_by_uuid_bin, issued_at, revoked, revoked_by_uuid_bin, revoke_txn_id, revoked_at "
            + "FROM government_fines WHERE fine_id = #{fineId}")
    @Results(id = "fineResultMap")
    @ConstructorArgs({
        @Arg(column = "fine_id", javaType = long.class, id = true),
        @Arg(column = "player_uuid_bin", javaType = java.util.UUID.class),
        @Arg(column = "debtor_account_id", javaType = Integer.class),
        @Arg(column = "gov_account_id", javaType = int.class),
        @Arg(column = "amount", javaType = java.math.BigDecimal.class),
        @Arg(column = "reason", javaType = String.class),
        @Arg(column = "txn_id", javaType = long.class),
        @Arg(column = "issued_by_uuid_bin", javaType = java.util.UUID.class),
        @Arg(column = "issued_at", javaType = java.time.Instant.class),
        @Arg(column = "revoked", javaType = boolean.class),
        @Arg(column = "revoked_by_uuid_bin", javaType = java.util.UUID.class),
        @Arg(column = "revoke_txn_id", javaType = Long.class),
        @Arg(column = "revoked_at", javaType = java.time.Instant.class)
    })
    GovernmentFine findFineById(@Param("fineId") long fineId);

    /**
     * The fine recorded against a ledger debit ({@code uq_fines_txn}: at most one), or
     * {@code null}. A locking read, so it sees a fine committed by a concurrent
     * double-submit after this transaction's snapshot was taken. Only call it for a
     * txn that already existed — probing a not-yet-recorded txn_id would gap-lock the
     * index tail and deadlock concurrent fine inserts.
     */
    @Select("SELECT fine_id, player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, "
            + "issued_by_uuid_bin, issued_at, revoked, revoked_by_uuid_bin, revoke_txn_id, revoked_at "
            + "FROM government_fines WHERE txn_id = #{txnId} FOR UPDATE")
    @ResultMap("fineResultMap")
    GovernmentFine findFineByTxnIdForUpdate(@Param("txnId") long txnId);

    @Select("SELECT fine_id, player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, "
            + "issued_by_uuid_bin, issued_at, revoked, revoked_by_uuid_bin, revoke_txn_id, revoked_at "
            + "FROM government_fines WHERE player_uuid_bin = #{playerUuid} ORDER BY issued_at DESC")
    @ResultMap("fineResultMap")
    List<GovernmentFine> findFinesByPlayer(@Param("playerUuid") UUID playerUuid);

    @Select("SELECT fine_id, player_uuid_bin, debtor_account_id, gov_account_id, amount, reason, txn_id, "
            + "issued_by_uuid_bin, issued_at, revoked, revoked_by_uuid_bin, revoke_txn_id, revoked_at "
            + "FROM government_fines WHERE player_uuid_bin = #{playerUuid} AND revoked = 0 ORDER BY issued_at DESC")
    @ResultMap("fineResultMap")
    List<GovernmentFine> findActiveFinesByPlayer(@Param("playerUuid") UUID playerUuid);

    @Update("UPDATE government_fines SET revoked = 1, revoked_by_uuid_bin = #{revokedBy}, "
            + "revoke_txn_id = #{revokeTxnId}, revoked_at = CURRENT_TIMESTAMP(3) WHERE fine_id = #{fineId}")
    int revokeFine(
            @Param("fineId") long fineId, @Param("revokedBy") UUID revokedBy, @Param("revokeTxnId") long revokeTxnId);
}
