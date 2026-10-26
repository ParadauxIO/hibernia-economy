package io.paradaux.treasury.mappers;

import io.paradaux.treasury.model.economy.AccountMember;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-account access on the consolidated {@code account_access} table (PAR-249).
 *
 * <p>Access is a single ordered scale carried in one row per (account, subject):
 * {@code VIEWER < MEMBER < AUTHORIZER} (read · read+spend · read+spend+manage).
 * The old member/authorizer/viewer tables collapsed into this — an authorizer was
 * always a member, a member can always view.
 *
 * <p><b>Soft-delete</b> via {@code removed_at}; reads filter {@code … IS NULL}.
 * Grants reactivate a removed row and never downgrade an active higher level.
 *
 * <p>Level comparisons use explicit literal {@code IN}/{@code CASE} lists rather
 * than {@code level >= ?} — MySQL only treats ENUM ordering as ordinal for literal
 * enum values, not bound parameters (where it would compare lexically).
 */
@Mapper
public interface MembershipMapper {

    // ---- Checks ----

    /**
     * Reads the {@code account_read_access_api} view (ADT-13) — the single source
     * of truth for the API/in-game read rule (MEMBER or AUTHORIZER, not removed),
     * shared with {@code treasury-rest-api} so the two can't drift. See
     * {@code V22__account_read_access_views.sql}.
     */
    @Select("""
      SELECT COUNT(*) FROM account_read_access_api
       WHERE account_id = #{accountId} AND subject_uuid_bin = #{member}
      """)
    int isMember(@Param("accountId") int accountId, @Param("member") UUID member);

    @Select("""
      SELECT COUNT(*) FROM account_access
       WHERE account_id = #{accountId} AND subject_uuid_bin = #{authorizer}
         AND level = 'AUTHORIZER' AND removed_at IS NULL
      """)
    int isAuthorizer(@Param("accountId") int accountId, @Param("authorizer") UUID authorizer);

    @Select("""
      SELECT COUNT(*) FROM account_access
       WHERE account_id = #{accountId} AND subject_uuid_bin = #{viewer}
         AND level = 'VIEWER' AND removed_at IS NULL
      """)
    int isViewer(@Param("accountId") int accountId, @Param("viewer") UUID viewer);

    // ---- Grants ----

    /** Grant at least MEMBER; keep an active AUTHORIZER, else (re)set to MEMBER. */
    @Insert("""
      INSERT INTO account_access(account_id, subject_uuid_bin, level, added_by_uuid_bin)
      VALUES(#{accountId}, #{memberUuid}, 'MEMBER', #{addedByUuid})
      ON DUPLICATE KEY UPDATE
          level = CASE WHEN removed_at IS NULL AND level = 'AUTHORIZER' THEN 'AUTHORIZER' ELSE 'MEMBER' END,
          removed_at = NULL,
          added_by_uuid_bin = VALUES(added_by_uuid_bin)
      """)
    int addMember(@Param("accountId") int accountId,
                  @Param("memberUuid") UUID memberUuid,
                  @Param("addedByUuid") UUID addedByUuid);

    /** Grant at least VIEWER; keep an active MEMBER/AUTHORIZER, else (re)set to VIEWER. */
    @Insert("""
      INSERT INTO account_access(account_id, subject_uuid_bin, level, added_by_uuid_bin)
      VALUES(#{accountId}, #{viewerUuid}, 'VIEWER', #{addedByUuid})
      ON DUPLICATE KEY UPDATE
          level = CASE WHEN removed_at IS NULL AND level IN ('MEMBER','AUTHORIZER') THEN level ELSE 'VIEWER' END,
          removed_at = NULL,
          added_by_uuid_bin = VALUES(added_by_uuid_bin)
      """)
    int addViewer(@Param("accountId") int accountId,
                  @Param("viewerUuid") UUID viewerUuid,
                  @Param("addedByUuid") UUID addedByUuid);

    /**
     * Promote an existing access row to AUTHORIZER. The service guarantees the
     * subject is already a member (an active row exists) before calling this.
     */
    @Update("""
      UPDATE account_access SET level = 'AUTHORIZER', removed_at = NULL
       WHERE account_id = #{accountId} AND subject_uuid_bin = #{authorizerUuid}
      """)
    int promoteToAuthorizer(@Param("accountId") int accountId,
                            @Param("authorizerUuid") UUID authorizerUuid);

    // ---- Removals (soft-delete) ----

    /** Revoke member (and authorizer) access; leaves a pure VIEWER untouched. */
    @Update("""
      UPDATE account_access SET removed_at = CURRENT_TIMESTAMP
       WHERE account_id = #{accountId} AND subject_uuid_bin = #{memberUuid}
         AND level IN ('MEMBER','AUTHORIZER') AND removed_at IS NULL
      """)
    int removeMember(@Param("accountId") int accountId,
                     @Param("memberUuid") UUID memberUuid);

    /** Demote an authorizer back to MEMBER (they remain a member). */
    @Update("""
      UPDATE account_access SET level = 'MEMBER'
       WHERE account_id = #{accountId} AND subject_uuid_bin = #{authorizerUuid}
         AND level = 'AUTHORIZER' AND removed_at IS NULL
      """)
    int removeAuthorizer(@Param("accountId") int accountId,
                         @Param("authorizerUuid") UUID authorizerUuid);

    /** Revoke a pure viewer's access; never touches a member/authorizer. */
    @Update("""
      UPDATE account_access SET removed_at = CURRENT_TIMESTAMP
       WHERE account_id = #{accountId} AND subject_uuid_bin = #{viewerUuid}
         AND level = 'VIEWER' AND removed_at IS NULL
      """)
    int removeViewer(@Param("accountId") int accountId,
                     @Param("viewerUuid") UUID viewerUuid);

    // ---- Listing ----

    @Select("""
      SELECT account_id, subject_uuid_bin, added_by_uuid_bin, created_at
        FROM account_access
       WHERE account_id = #{accountId} AND level IN ('MEMBER','AUTHORIZER') AND removed_at IS NULL
       ORDER BY created_at
      """)
    @Results(id = "accessMap", value = {
            @Result(column = "account_id", property = "accountId"),
            @Result(column = "subject_uuid_bin", property = "memberUuid"),
            @Result(column = "added_by_uuid_bin", property = "addedByUuid"),
            @Result(column = "created_at", property = "createdAt", javaType = Instant.class)
    })
    List<AccountMember> getMembers(@Param("accountId") int accountId);

    @Select("""
      SELECT account_id, subject_uuid_bin, added_by_uuid_bin, created_at
        FROM account_access
       WHERE account_id = #{accountId} AND level = 'AUTHORIZER' AND removed_at IS NULL
       ORDER BY created_at
      """)
    @ResultMap("accessMap")
    List<AccountMember> getAuthorizers(@Param("accountId") int accountId);

    @Select("""
      SELECT account_id, subject_uuid_bin, added_by_uuid_bin, created_at
        FROM account_access
       WHERE account_id = #{accountId} AND level = 'VIEWER' AND removed_at IS NULL
       ORDER BY created_at
      """)
    @ResultMap("accessMap")
    List<AccountMember> getViewers(@Param("accountId") int accountId);
}
