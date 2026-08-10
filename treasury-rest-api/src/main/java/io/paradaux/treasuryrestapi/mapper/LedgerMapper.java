package io.paradaux.treasuryrestapi.mapper;

import io.paradaux.treasuryrestapi.model.LedgerPosting;
import io.paradaux.treasuryrestapi.model.LedgerTxn;
import io.paradaux.treasuryrestapi.model.TransactionRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LedgerMapper {

    // ── Inserts ──────────────────────────────────────────────────────────────

    @Options(useGeneratedKeys = true, keyProperty = "txnId", keyColumn = "txn_id")
    @Insert("INSERT INTO ledger_txns (message, settlement_time, initiator_uuid_bin, plugin_system, client_dedup_key) " +
            "VALUES (#{message}, #{settlementTime}, #{initiatorUuidBin}, #{pluginSystem}, UNHEX(#{clientDedupKey}))")
    void insertTxn(LedgerTxn txn);

    @Options(useGeneratedKeys = true, keyProperty = "postingId", keyColumn = "posting_id")
    @Insert("INSERT INTO ledger_postings (txn_id, account_id, amount, memo) " +
            "VALUES (#{txnId}, #{accountId}, #{amount}, #{memo})")
    void insertPosting(LedgerPosting posting);

    // ── Idempotency ──────────────────────────────────────────────────────────

    /**
     * Finds a transaction by the SHA-256 hex of the client-supplied Idempotency-Key header.
     */
    @Select("SELECT txn_id, message, settlement_time, initiator_uuid_bin, plugin_system " +
            "FROM ledger_txns WHERE client_dedup_key = UNHEX(#{dedupKey}) LIMIT 1")
    LedgerTxn findByDedupKey(@Param("dedupKey") String dedupKey);

    /**
     * Returns the credit (positive) posting for a transaction — used to verify
     * idempotency replay body consistency (toAccountId and amount).
     */
    @Select("SELECT posting_id, txn_id, account_id, amount, memo " +
            "FROM ledger_postings WHERE txn_id = #{txnId} AND amount > 0 LIMIT 1")
    LedgerPosting findCreditPostingByTxnId(@Param("txnId") long txnId);

    /**
     * Returns the debit (negative) posting for a transaction — used to verify
     * idempotency replay body consistency on {@code fromAccountId}.
     */
    @Select("SELECT posting_id, txn_id, account_id, amount, memo " +
            "FROM ledger_postings WHERE txn_id = #{txnId} AND amount < 0 LIMIT 1")
    LedgerPosting findDebitPostingByTxnId(@Param("txnId") long txnId);

    // ── Transaction history ──────────────────────────────────────────────────

    /**
     * Paginated transaction history: ledger_postings joined with ledger_txns,
     * ordered newest-first. Maps to the /accounts/{accountId}/transactions endpoint.
     */
    @Select("SELECT lp.posting_id, lp.txn_id, lp.amount, lp.memo, " +
            "       lt.message, lt.settlement_time, lt.initiator_uuid_bin, lt.plugin_system " +
            "FROM ledger_postings lp " +
            "JOIN ledger_txns lt ON lp.txn_id = lt.txn_id " +
            "WHERE lp.account_id = #{accountId} " +
            // posting_id (auto-increment) is monotonic with insert time, so DESC is
            // newest-first and served by idx_postings_account (account_id, ...) ordering
            // — no filesort/join-for-sort. Matches the Treasury plugin's canonical query;
            // do not revert to ORDER BY lt.settlement_time.
            "ORDER BY lp.posting_id DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<TransactionRow> findTransactionsByAccount(@Param("accountId") long accountId,
                                                   @Param("limit") int limit,
                                                   @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM ledger_postings WHERE account_id = #{accountId}")
    long countTransactionsByAccount(@Param("accountId") long accountId);

    // ── Cursor feed ──────────────────────────────────────────────────────────

    /**
     * Forward keyset feed: this account's postings with {@code txn_id} strictly
     * greater than the caller's cursor, oldest-first. Only **settled** rows are
     * returned ({@code settlement_time <= NOW() - lag}); {@code txn_id} is
     * assigned at insert but only becomes visible at commit, so a row with a
     * lower id can commit after a higher one — the lag window lets in-flight
     * transactions settle before they enter the feed, so a {@code txn_id > cursor}
     * poll never skips one. Served by {@code idx_postings_account_txn_amount}.
     */
    @Select("SELECT lp.posting_id, lp.txn_id, lp.amount, lp.memo, " +
            "       lt.message, lt.settlement_time, lt.initiator_uuid_bin, lt.plugin_system " +
            "FROM ledger_postings lp " +
            "JOIN ledger_txns lt ON lp.txn_id = lt.txn_id " +
            "WHERE lp.account_id = #{accountId} " +
            "  AND lp.txn_id > #{sinceTxnId} " +
            "  AND lt.settlement_time <= (NOW() - INTERVAL #{lagSeconds} SECOND) " +
            "ORDER BY lp.txn_id ASC, lp.posting_id ASC " +
            "LIMIT #{limit}")
    List<TransactionRow> findTransactionsSince(@Param("accountId") long accountId,
                                               @Param("sinceTxnId") long sinceTxnId,
                                               @Param("lagSeconds") int lagSeconds,
                                               @Param("limit") int limit);

    // ── Webhook dispatcher tail (global, whole-txn batches) ──────────────────

    /** Next batch of settled {@code txn_id}s after the dispatcher cursor, ascending. */
    @Select("SELECT txn_id FROM ledger_txns " +
            "WHERE txn_id > #{cursor} " +
            "  AND settlement_time <= (NOW() - INTERVAL #{lagSeconds} SECOND) " +
            "ORDER BY txn_id ASC LIMIT #{limit}")
    List<Long> findTxnIdsAfter(@Param("cursor") long cursor,
                               @Param("lagSeconds") int lagSeconds,
                               @Param("limit") int limit);

    /** All postings (both legs) for the given transactions, with txn metadata, for fan-out. */
    @Select("<script>" +
            "SELECT lp.posting_id, lp.txn_id, lp.account_id, lp.amount, lp.memo, " +
            "       lt.message, lt.settlement_time, lt.initiator_uuid_bin, lt.plugin_system " +
            "FROM ledger_postings lp " +
            "JOIN ledger_txns lt ON lp.txn_id = lt.txn_id " +
            "WHERE lp.txn_id IN " +
            "<foreach item='id' collection='txnIds' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY lp.txn_id ASC, lp.posting_id ASC" +
            "</script>")
    List<TransactionRow> findPostingsForTxns(@Param("txnIds") List<Long> txnIds);
}
