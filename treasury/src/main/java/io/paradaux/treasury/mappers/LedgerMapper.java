package io.paradaux.treasury.mappers;

import io.paradaux.treasury.model.economy.LedgerPosting;
import io.paradaux.treasury.model.economy.LedgerTxn;
import io.paradaux.treasury.model.economy.TransactionEntry;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

@Mapper
public interface LedgerMapper {

    @Select("""
      SELECT txn_id, trade_time, settlement_time, message,
             initiator_uuid_bin, authorizer_uuid_bin, plugin_system, client_dedup_key
        FROM ledger_txns
       WHERE client_dedup_key = #{key}
       LIMIT 1
      """)
    @Results(id="txnMap", value={
            @Result(column="txn_id", property="txnId", id=true),
            @Result(column="trade_time", property="tradeTime", javaType=Instant.class),
            @Result(column="settlement_time", property="settlementTime", javaType=Instant.class),
            @Result(column="message", property="message"),
            @Result(column="initiator_uuid_bin", property="initiatorUuid"),
            @Result(column="authorizer_uuid_bin", property="authorizerUuid"),
            @Result(column="plugin_system", property="pluginSystem"),
            @Result(column="client_dedup_key", property="clientDedupKey")
    })
    LedgerTxn findByDedupKey(@Param("key") byte[] clientKey);

    /**
     * Locking variant of {@link #findByDedupKey} (FOR UPDATE). Treasury runs at the
     * MariaDB-default REPEATABLE READ, so after a concurrent identical-dedup-key insert
     * trips {@code uq_ledger_dedup} a plain re-SELECT would still see the pre-race
     * snapshot and miss the committed row. A locking read forces the latest committed
     * version so the racing transaction id can be returned instead of propagating the
     * duplicate-key error (ADT-73, mirrors AccountMapper's *Locking resolves).
     */
    @Select("""
      SELECT txn_id, trade_time, settlement_time, message,
             initiator_uuid_bin, authorizer_uuid_bin, plugin_system, client_dedup_key
        FROM ledger_txns
       WHERE client_dedup_key = #{key}
       LIMIT 1
       FOR UPDATE
      """)
    @ResultMap("txnMap")
    LedgerTxn findByDedupKeyLocking(@Param("key") byte[] clientKey);

    @Select("""
      SELECT txn_id, trade_time, settlement_time, message,
             initiator_uuid_bin, authorizer_uuid_bin, plugin_system, client_dedup_key
        FROM ledger_txns
       WHERE txn_id = #{txnId}
      """)
    @ResultMap("txnMap")
    LedgerTxn findTxnById(@Param("txnId") long txnId);

    @Insert("""
      INSERT INTO ledger_txns(
          trade_time,
          settlement_time,
          message,
          initiator_uuid_bin,
          authorizer_uuid_bin,
          plugin_system,
          client_dedup_key
      ) VALUES (
          CURRENT_TIMESTAMP,
          CURRENT_TIMESTAMP,
          #{message},
          #{initiatorUuid},
          #{authorizerUuid},
          #{pluginSystem},
          #{clientDedupKey}
      )
      """)
    @Options(useGeneratedKeys = true, keyProperty = "txnId", keyColumn = "txn_id")
    int insertTxnEntity(LedgerTxn txn);

    @Insert("INSERT INTO ledger_postings(txn_id, account_id, amount, memo) VALUES(#{txnId}, #{accountId}, #{amount}, #{memo})")
    int insertPosting(LedgerPosting posting);

    /**
     * Inserts both legs of a transfer in a single multi-row statement (one round-trip).
     * Rows fire the per-row balance trigger in VALUES order, so callers pass them in
     * ascending account_id order to keep balance-row lock acquisition globally ordered.
     */
    @Insert("""
            <script>
            INSERT INTO ledger_postings(txn_id, account_id, amount, memo) VALUES
            <foreach item='p' collection='list' separator=','>
                (#{p.txnId}, #{p.accountId}, #{p.amount}, #{p.memo})
            </foreach>
            </script>
            """)
    int insertPostings(@Param("list") List<LedgerPosting> postings);

    // ---- Paginated transaction history for an account ----

    @Select("""
      SELECT p.posting_id, p.txn_id, p.account_id, p.amount, p.memo,
             t.settlement_time, t.message, t.initiator_uuid_bin,
             t.authorizer_uuid_bin, t.plugin_system
        FROM ledger_postings p
        JOIN ledger_txns t ON p.txn_id = t.txn_id
       WHERE p.account_id = #{accountId}
       -- posting_id (PK, auto-increment) is monotonic with insert time, so DESC is
       -- reverse-chronological and served by idx_postings_account (account_id, posting_id)
       -- with no filesort or join-for-sort. Do not revert to ORDER BY t.settlement_time.
       ORDER BY p.posting_id DESC
       LIMIT #{limit} OFFSET #{offset}
      """)
    @Results(id = "txnEntryMap", value = {
            @Result(column = "posting_id", property = "postingId", id = true),
            @Result(column = "txn_id", property = "txnId"),
            @Result(column = "account_id", property = "accountId"),
            @Result(column = "amount", property = "amount"),
            @Result(column = "memo", property = "memo"),
            @Result(column = "settlement_time", property = "settlementTime", javaType = Instant.class),
            @Result(column = "message", property = "message"),
            @Result(column = "initiator_uuid_bin", property = "initiatorUuid"),
            @Result(column = "authorizer_uuid_bin", property = "authorizerUuid"),
            @Result(column = "plugin_system", property = "pluginSystem")
    })
    List<TransactionEntry> findTransactionsByAccount(@Param("accountId") int accountId,
                                                    @Param("limit") int limit,
                                                    @Param("offset") int offset);

    @Select("""
      SELECT COUNT(*)
        FROM ledger_postings
       WHERE account_id = #{accountId}
      """)
    int countTransactionsByAccount(@Param("accountId") int accountId);

    // ---- Merged paginated history across several accounts ----

    @Select("""
      <script>
      SELECT p.posting_id, p.txn_id, p.account_id, p.amount, p.memo,
             t.settlement_time, t.message, t.initiator_uuid_bin,
             t.authorizer_uuid_bin, t.plugin_system
        FROM ledger_postings p
        JOIN ledger_txns t ON p.txn_id = t.txn_id
       WHERE p.account_id IN
       <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>
       -- posting_id (PK, auto-increment) is globally monotonic, so DESC merges the
       -- accounts in reverse-chronological order served by idx_postings_account.
       ORDER BY p.posting_id DESC
       LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
    @ResultMap("txnEntryMap")
    List<TransactionEntry> findTransactionsByAccounts(@Param("ids") List<Integer> ids,
                                                      @Param("limit") int limit,
                                                      @Param("offset") int offset);

    @Select("""
      <script>
      SELECT COUNT(*)
        FROM ledger_postings
       WHERE account_id IN
       <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>
      </script>
      """)
    int countTransactionsByAccounts(@Param("ids") List<Integer> ids);

    @Select("""
      SELECT p.posting_id, p.txn_id, p.account_id, p.amount, p.memo,
             t.settlement_time, t.message, t.initiator_uuid_bin,
             t.authorizer_uuid_bin, t.plugin_system
        FROM ledger_postings p
        JOIN ledger_txns t ON p.txn_id = t.txn_id
       WHERE p.account_id = #{accountId}
       -- posting_id (PK, auto-increment) is monotonic with insert time, so DESC is
       -- reverse-chronological and served by idx_postings_account (account_id, posting_id)
       -- with no filesort or join-for-sort. Do not revert to ORDER BY t.settlement_time.
       ORDER BY p.posting_id DESC
       LIMIT #{maxRows}
      """)
    @ResultMap("txnEntryMap")
    List<TransactionEntry> findAllTransactionsByAccount(@Param("accountId") int accountId,
                                                       @Param("maxRows") int maxRows);

    // ---- Postings for a single transaction ----

    @Select("""
      SELECT posting_id, txn_id, account_id, amount, memo
        FROM ledger_postings
       WHERE txn_id = #{txnId}
       ORDER BY posting_id
      """)
    @ConstructorArgs({
            @Arg(column = "posting_id", javaType = Long.class, id = true),
            @Arg(column = "txn_id",     javaType = long.class),
            @Arg(column = "account_id", javaType = int.class),
            @Arg(column = "amount",     javaType = java.math.BigDecimal.class),
            @Arg(column = "memo",       javaType = String.class)
    })
    List<LedgerPosting> findPostingsByTxnId(@Param("txnId") long txnId);
}
