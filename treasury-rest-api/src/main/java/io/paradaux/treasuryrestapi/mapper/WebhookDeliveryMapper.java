package io.paradaux.treasuryrestapi.mapper;

import io.paradaux.treasuryrestapi.model.DueDelivery;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WebhookDeliveryMapper {

    /**
     * Enqueues a delivery. {@code INSERT IGNORE} + the UNIQUE (subscription_id,
     * txn_id) makes this idempotent, so a re-scanned transaction never
     * double-delivers.
     */
    @Insert("INSERT IGNORE INTO webhook_delivery (subscription_id, txn_id, account_id) "
            + "VALUES (#{subscriptionId}, #{txnId}, #{accountId})")
    int enqueue(@Param("subscriptionId") long subscriptionId,
                @Param("txnId") long txnId,
                @Param("accountId") long accountId);

    /**
     * Deliveries due now, joined to their (still-active) subscription and the
     * matched posting/transaction for the payload. Inactive/deleted
     * subscriptions drop out of the join and are simply skipped.
     */
    @Select("SELECT d.delivery_id AS deliveryId, d.subscription_id AS subscriptionId, d.txn_id AS txnId, "
            + "       d.account_id AS accountId, d.attempts, "
            + "       s.target_url AS targetUrl, s.secret, "
            + "       lp.posting_id AS postingId, lp.amount, lp.memo, "
            + "       lt.message, lt.settlement_time AS settlementTime, "
            + "       lt.initiator_uuid_bin AS initiatorUuidBin, lt.plugin_system AS pluginSystem "
            + "FROM webhook_delivery d "
            + "JOIN webhook_subscription s ON s.subscription_id = d.subscription_id AND s.active = 1 "
            + "JOIN ledger_postings lp ON lp.txn_id = d.txn_id AND lp.account_id = d.account_id "
            + "JOIN ledger_txns lt ON lt.txn_id = d.txn_id "
            // idx_delivery_due (status, next_attempt_at): the status equality lets the
            // index's next_attempt_at column satisfy the sort (due-order, oldest first);
            // delivery_id breaks ties deterministically.
            + "WHERE d.status = 'PENDING' AND d.next_attempt_at <= NOW() "
            + "ORDER BY d.next_attempt_at ASC, d.delivery_id ASC LIMIT #{limit}")
    List<DueDelivery> findDue(@Param("limit") int limit);

    @Update("UPDATE webhook_delivery SET status = 'DELIVERED', attempts = attempts + 1, "
            + "http_status = #{httpStatus}, last_error = NULL WHERE delivery_id = #{id}")
    int markDelivered(@Param("id") long id, @Param("httpStatus") Integer httpStatus);

    /** Keeps the delivery PENDING and schedules the next attempt {@code delaySeconds} out. */
    @Update("UPDATE webhook_delivery SET attempts = attempts + 1, http_status = #{httpStatus}, "
            + "last_error = #{error}, next_attempt_at = NOW() + INTERVAL #{delaySeconds} SECOND "
            + "WHERE delivery_id = #{id}")
    int markRetry(@Param("id") long id, @Param("httpStatus") Integer httpStatus,
                  @Param("error") String error, @Param("delaySeconds") long delaySeconds);

    @Update("UPDATE webhook_delivery SET status = 'FAILED', attempts = attempts + 1, "
            + "http_status = #{httpStatus}, last_error = #{error} WHERE delivery_id = #{id}")
    int markFailed(@Param("id") long id, @Param("httpStatus") Integer httpStatus, @Param("error") String error);
}
