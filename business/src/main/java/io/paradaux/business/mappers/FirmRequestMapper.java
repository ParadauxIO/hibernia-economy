package io.paradaux.business.mappers;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

public interface FirmRequestMapper {

    // ======================== INVITES ========================

    @Insert("""
            INSERT INTO firm_invites (firm_id, player_uuid_bin, invited_by_uuid_bin, status, created_at, expires_at)
            VALUES (#{firmId}, uuid_to_bin(#{playerUuid}), uuid_to_bin(#{invitedBy}), 'PENDING', CURRENT_TIMESTAMP, #{expiresAt})
            """)
    int createInvite(@Param("firmId") int firmId,
                     @Param("playerUuid") String playerUuid,
                     @Param("invitedBy") String invitedBy,
                     @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
        SELECT CASE WHEN EXISTS (
            SELECT 1 FROM firm_invites
             WHERE firm_id = #{firmId}
               AND player_uuid_bin = uuid_to_bin(#{playerUuid})
               AND status = 'PENDING'
               AND expires_at > NOW()
             LIMIT 1
        ) THEN TRUE ELSE FALSE END
        """)
    Boolean hasPendingJobOffer(@Param("firmId") int firmId,
                               @Param("playerUuid") String playerUuid);

    /**
     * Accept an invite iff it is PENDING and not expired.
     * Returns 1 if flipped to ACCEPTED, 0 otherwise.
     */
    @Update("""
            UPDATE firm_invites
               SET status = 'ACCEPTED'
             WHERE firm_id = #{firmId}
               AND player_uuid_bin = uuid_to_bin(#{playerUuid})
               AND status = 'PENDING'
               AND expires_at > NOW()
            """)
    int acceptInvite(@Param("firmId") int firmId,
                     @Param("playerUuid") String playerUuid);

    /**
     * Reject (now "DENY") an invite iff it is PENDING and not expired.
     * Returns 1 if flipped to DENIED, 0 otherwise.
     */
    @Update("""
            UPDATE firm_invites
               SET status = 'DENIED'
             WHERE firm_id = #{firmId}
               AND player_uuid_bin = uuid_to_bin(#{playerUuid})
               AND status = 'PENDING'
               AND expires_at > NOW()
            """)
    int rejectInvite(@Param("firmId") int firmId,
                     @Param("playerUuid") String playerUuid);

    @Update("""
                UPDATE firm_invites
                   SET status = 'DENIED'
                 WHERE firm_id = #{firmId}
                   AND player_uuid_bin = uuid_to_bin(#{playerUuid})
                   AND status = 'PENDING'
                   AND expires_at > NOW()
            """)
    int rescindInvite(@Param("firmId") int firmId,
                      @Param("playerUuid") String playerUuid);

    /**
     * Expire any invites that are not yet ACCEPTED or DENIED
     * and whose expiry has passed.
     */
    @Update("""
            UPDATE firm_invites
               SET status = 'EXPIRED'
             WHERE status NOT IN ('ACCEPTED','DENIED','EXPIRED')
               AND expires_at <= NOW()
            """)
    int expireStaleInvites();

    @Select("""
            SELECT bin_to_uuid(invited_by_uuid_bin) AS invitedBy
              FROM firm_invites
             WHERE firm_id = #{firmId}
               AND player_uuid_bin = uuid_to_bin(#{playerUuid})
               AND status = 'ACCEPTED'
             ORDER BY created_at DESC
             LIMIT 1
            """)
    String getInviter(@Param("firmId") int firmId,
                      @Param("playerUuid") String playerUuid);

    @Select("""
                SELECT bin_to_uuid(invited_by_uuid_bin) AS invitedBy
                  FROM firm_invites
                 WHERE firm_id = #{firmId}
                   AND player_uuid_bin = uuid_to_bin(#{playerUuid})
                   AND status = 'PENDING'
                   AND expires_at > NOW()
                 ORDER BY created_at DESC
                 LIMIT 1
                 FOR UPDATE
            """)
    String lockPendingInviter(@Param("firmId") int firmId,
                              @Param("playerUuid") String playerUuid);

    // ==================== TRANSFER REQUESTS ====================

    /**
     * Create a transfer request:
     * from_uuid_bin is set to current proprietor of the firm.
     */
    @Insert("""
            INSERT INTO firm_transfer_requests (firm_id, from_uuid_bin, to_uuid_bin, token, status, created_at, expires_at)
            SELECT #{firmId},
                   f.proprietor_uuid_bin,
                   uuid_to_bin(#{toUuid}),
                   #{token},
                   'PENDING',
                   CURRENT_TIMESTAMP,
                   #{expiresAt}
              FROM firm f
             WHERE f.firm_id = #{firmId}
            """)
    int createTransferRequest(@Param("firmId") int firmId,
                              @Param("toUuid") String toUuid,
                              @Param("token") String token,
                              @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * Record a forced proprietor override ({@code /firm admin set proprietor}) as
     * a terminal {@code ADMIN_OVERRIDE} row, so a staff/DOC reassignment is
     * auditable in the same handover log as consent transfers (who, from whom, to
     * whom, when). {@code actorUuid} is the acting staff member; {@code token} is
     * an unused sentinel that satisfies the NOT NULL / UNIQUE column contract, and
     * {@code expires_at} is set to now (irrelevant for a completed override).
     */
    @Insert("""
            INSERT INTO firm_transfer_requests
                (firm_id, from_uuid_bin, to_uuid_bin, actor_uuid_bin, token, status, created_at, expires_at)
            VALUES (#{firmId},
                    uuid_to_bin(#{fromUuid}),
                    uuid_to_bin(#{toUuid}),
                    uuid_to_bin(#{actorUuid}),
                    #{token},
                    'ADMIN_OVERRIDE',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP)
            """)
    int recordAdminOverride(@Param("firmId") int firmId,
                            @Param("fromUuid") String fromUuid,
                            @Param("toUuid") String toUuid,
                            @Param("actorUuid") String actorUuid,
                            @Param("token") String token);

    /**
     * Cancel any in-flight (PENDING or CONFIRMED) proprietorship transfer for a firm.
     * Used by the admin proprietor override so a transfer the previous proprietor had
     * in flight can't later be completed and re-hand the firm to a third party. At
     * most one transfer is active per firm (the {@code uq_one_active_transfer}
     * constraint), so this touches at most one row. Returns the number cancelled.
     */
    @Update("""
            UPDATE firm_transfer_requests
               SET status = 'CANCELLED'
             WHERE firm_id = #{firmId}
               AND status IN ('PENDING', 'CONFIRMED')
            """)
    int cancelActiveTransfers(@Param("firmId") int firmId);

    /**
     * Confirm a transfer iff token matches, it is PENDING, and not expired.
     * Returns 1 if flipped to CONFIRMED, 0 otherwise.
     */
    @Update("""
            UPDATE firm_transfer_requests
               SET status = 'CONFIRMED'
             WHERE firm_id = #{firmId}
               AND to_uuid_bin = uuid_to_bin(#{toUuid})
               AND token = #{token}
               AND status = 'PENDING'
               AND expires_at > NOW()
            """)
    int confirmTransfer(@Param("firmId") int firmId,
                        @Param("toUuid") String toUuid,
                        @Param("token") String token);

    /**
     * Reject a transfer (now modelled as CANCELLED) iff token matches, it is PENDING, and not expired.
     * Returns 1 if flipped to CANCELLED, 0 otherwise.
     */
    @Update("""
            UPDATE firm_transfer_requests
               SET status = 'CANCELLED'
             WHERE firm_id = #{firmId}
               AND to_uuid_bin = uuid_to_bin(#{toUuid})
               AND status = 'PENDING'
               AND expires_at > NOW()
            """)
    int rejectTransfer(@Param("firmId") int firmId,
                       @Param("toUuid") String toUuid);

    /**
     * Accept a transfer iff it is CONFIRMED and not expired.
     * Returns 1 if flipped to ACCEPTED, 0 otherwise.
     */
    @Update("""
            UPDATE firm_transfer_requests
               SET status = 'ACCEPTED'
             WHERE firm_id = #{firmId}
               AND to_uuid_bin = uuid_to_bin(#{toUuid})
               AND status = 'CONFIRMED'
               AND expires_at > NOW()
            """)
    int acceptTransfer(@Param("firmId") int firmId,
                       @Param("toUuid") String toUuid);

    // House-keeping methods

    @Update("""
            UPDATE firm_transfer_requests
               SET status = 'EXPIRED'
             WHERE status NOT IN ('ACCEPTED','CANCELLED','EXPIRED')
               AND expires_at <= NOW()
            """)
    int expireStaleTransfers();

}
