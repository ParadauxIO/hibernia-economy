package io.paradaux.business.mappers;

import io.paradaux.business.internal.model.directdebit.DirectDebitCollection;
import io.paradaux.business.internal.model.directdebit.DirectDebitMandate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Direct-debit mandates and collections (PAR-557). Every time parameter is a UTC
 * wall-clock value from the service's clock; nothing here reads the database NOW().
 */
public interface DirectDebitMapper {

    String MANDATE_COLUMNS = """
            mandate_id AS mandateId, firm_id AS firmId, payee_account_id AS payeeAccountId,
            payer_account_id AS payerAccountId, bin_to_uuid(payer_uuid_bin) AS payerUuid, kind, period, cap,
            amount, reference, status, bin_to_uuid(proposed_by_uuid_bin) AS proposedByUuid,
            proposed_at AS proposedAt, expires_at AS expiresAt, accepted_at AS acceptedAt, ended_at AS endedAt,
            bin_to_uuid(ended_by_uuid_bin) AS endedByUuid, end_reason AS endReason, next_due_at AS nextDueAt,
            missed_periods AS missedPeriods
            """;

    String COLLECTION_COLUMNS = """
            collection_id AS collectionId, mandate_id AS mandateId, period_start AS periodStart,
            recurring_period AS recurringPeriod, amount, status, attempts, txn_id AS txnId,
            bin_to_uuid(initiator_uuid_bin) AS initiatorUuid, memo, failure_reason AS failureReason,
            created_at AS createdAt, updated_at AS updatedAt
            """;

    // ======================== MANDATES ========================

    @Insert("""
            INSERT INTO direct_debit_mandate
                (firm_id, payee_account_id, payer_account_id, payer_uuid_bin, kind, period, cap, amount,
                 reference, status, proposed_by_uuid_bin, proposed_at, expires_at)
            VALUES (#{firmId}, #{payeeAccountId}, #{payerAccountId}, uuid_to_bin(#{payerUuid}), #{kind}, #{period},
                    #{cap}, #{amount}, #{reference}, 'PENDING', uuid_to_bin(#{proposedByUuid}), #{proposedAt},
                    #{expiresAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "mandateId", keyColumn = "mandate_id")
    int insertMandate(DirectDebitMandate mandate);

    @Select("SELECT " + MANDATE_COLUMNS + " FROM direct_debit_mandate WHERE mandate_id = #{id}")
    DirectDebitMandate findMandate(@Param("id") long mandateId);

    /** Serialises every charge, settlement and status change on one mandate. */
    @Select("SELECT " + MANDATE_COLUMNS + " FROM direct_debit_mandate WHERE mandate_id = #{id} FOR UPDATE")
    DirectDebitMandate lockMandate(@Param("id") long mandateId);

    @Select("SELECT " + MANDATE_COLUMNS + """
             FROM direct_debit_mandate
            WHERE payer_uuid_bin = uuid_to_bin(#{payer})
              AND status IN ('PENDING','ACTIVE','SUSPENDED')
            ORDER BY mandate_id DESC
            """)
    List<DirectDebitMandate> listOpenForPayer(@Param("payer") String payerUuid);

    @Select("SELECT " + MANDATE_COLUMNS + """
             FROM direct_debit_mandate
            WHERE firm_id = #{firmId}
              AND status IN ('PENDING','ACTIVE','SUSPENDED')
            ORDER BY mandate_id DESC
            """)
    List<DirectDebitMandate> listOpenForFirm(@Param("firmId") int firmId);

    /** Expire this firm+payer's lapsed proposal so it stops holding the one-pending slot. */
    @Update("""
            UPDATE direct_debit_mandate
               SET status = 'EXPIRED', ended_at = #{now}, end_reason = 'Proposal expired'
             WHERE firm_id = #{firmId}
               AND payer_uuid_bin = uuid_to_bin(#{payer})
               AND status = 'PENDING'
               AND expires_at <= #{now}
            """)
    int expireLapsedProposal(
            @Param("firmId") int firmId, @Param("payer") String payerUuid, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE direct_debit_mandate
               SET status = 'EXPIRED', ended_at = #{now}, end_reason = 'Proposal expired'
             WHERE status = 'PENDING'
               AND expires_at <= #{now}
            """)
    int expireLapsedProposals(@Param("now") LocalDateTime now);

    /** PENDING → ACTIVE iff unexpired. {@code nextDueAt} is null for on-demand mandates. */
    @Update("""
            UPDATE direct_debit_mandate
               SET status = 'ACTIVE', accepted_at = #{now}, next_due_at = #{nextDueAt}
             WHERE mandate_id = #{id}
               AND status = 'PENDING'
               AND expires_at > #{now}
            """)
    int accept(
            @Param("id") long mandateId, @Param("now") LocalDateTime now, @Param("nextDueAt") LocalDateTime nextDueAt);

    /** Moves an open mandate to a terminal status; 0 when it had already ended. */
    @Update("""
            UPDATE direct_debit_mandate
               SET status = #{status}, ended_at = #{now}, ended_by_uuid_bin = uuid_to_bin(#{by}),
                   end_reason = #{reason}, next_due_at = NULL
             WHERE mandate_id = #{id}
               AND status IN ('PENDING','ACTIVE','SUSPENDED')
            """)
    int end(
            @Param("id") long mandateId,
            @Param("status") String status,
            @Param("by") String byUuid,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE direct_debit_mandate
               SET status = 'SUSPENDED', next_due_at = NULL
             WHERE mandate_id = #{id} AND status = 'ACTIVE'
            """)
    int suspend(@Param("id") long mandateId);

    @Update("""
            UPDATE direct_debit_mandate
               SET status = 'ACTIVE', missed_periods = 0, next_due_at = #{nextDueAt}
             WHERE mandate_id = #{id} AND status = 'SUSPENDED'
            """)
    int resume(@Param("id") long mandateId, @Param("nextDueAt") LocalDateTime nextDueAt);

    @Update("""
            UPDATE direct_debit_mandate
               SET next_due_at = #{nextDueAt}, missed_periods = #{missed}
             WHERE mandate_id = #{id}
            """)
    int schedule(@Param("id") long mandateId, @Param("nextDueAt") LocalDateTime nextDueAt, @Param("missed") int missed);

    @Select("""
            SELECT mandate_id FROM direct_debit_mandate
             WHERE status = 'ACTIVE' AND kind = 'RECURRING' AND next_due_at <= #{now}
             ORDER BY next_due_at
             LIMIT #{limit}
            """)
    List<Long> dueRecurring(@Param("now") LocalDateTime now, @Param("limit") int limit);

    // ======================== COLLECTIONS ========================

    @Insert("""
            INSERT INTO direct_debit_collection
                (mandate_id, period_start, recurring_period, amount, status, attempts, initiator_uuid_bin, memo,
                 created_at, updated_at)
            VALUES (#{mandateId}, #{periodStart}, #{recurringPeriod}, #{amount}, 'RESERVED', 1,
                    uuid_to_bin(#{initiatorUuid}), #{memo}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "collectionId", keyColumn = "collection_id")
    int insertCollection(DirectDebitCollection collection);

    @Select("SELECT " + COLLECTION_COLUMNS + " FROM direct_debit_collection WHERE collection_id = #{id}")
    DirectDebitCollection findCollection(@Param("id") long collectionId);

    @Select("SELECT " + COLLECTION_COLUMNS + """
             FROM direct_debit_collection
            WHERE mandate_id = #{mandateId} AND recurring_period = #{periodStart}
            """)
    DirectDebitCollection findRecurringCollection(
            @Param("mandateId") long mandateId, @Param("periodStart") LocalDateTime periodStart);

    /** What already counts against the cap in a period: reserved and collected amounts. */
    @Select("""
            SELECT COALESCE(SUM(amount), 0) FROM direct_debit_collection
             WHERE mandate_id = #{mandateId}
               AND period_start = #{periodStart}
               AND status IN ('RESERVED','COLLECTED')
            """)
    BigDecimal committedInPeriod(@Param("mandateId") long mandateId, @Param("periodStart") LocalDateTime periodStart);

    /** FAILED → RESERVED for another attempt at the same recurring period. */
    @Update("""
            UPDATE direct_debit_collection
               SET status = 'RESERVED', attempts = attempts + 1, failure_reason = NULL, updated_at = #{now}
             WHERE collection_id = #{id} AND status = 'FAILED'
            """)
    int reserveRetry(@Param("id") long collectionId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE direct_debit_collection
               SET status = 'COLLECTED', txn_id = #{txnId}, updated_at = #{now}
             WHERE collection_id = #{id} AND status = 'RESERVED'
            """)
    int markCollected(@Param("id") long collectionId, @Param("txnId") long txnId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE direct_debit_collection
               SET status = 'FAILED', failure_reason = #{reason}, updated_at = #{now}
             WHERE collection_id = #{id} AND status = 'RESERVED'
            """)
    int markFailed(@Param("id") long collectionId, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** Reservations left unsettled (e.g. by a crash between reserving and settling). */
    @Select("""
            SELECT collection_id FROM direct_debit_collection
             WHERE status = 'RESERVED' AND updated_at <= #{before}
             ORDER BY collection_id
             LIMIT #{limit}
            """)
    List<Long> staleReservations(@Param("before") LocalDateTime before, @Param("limit") int limit);

    @Select("SELECT " + COLLECTION_COLUMNS + """
             FROM direct_debit_collection
            WHERE mandate_id = #{mandateId}
            ORDER BY collection_id DESC
            LIMIT #{limit}
            """)
    List<DirectDebitCollection> recentCollections(@Param("mandateId") long mandateId, @Param("limit") int limit);
}
