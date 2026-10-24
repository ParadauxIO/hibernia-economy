package io.paradaux.treasuryrestapi.mapper;

import io.paradaux.treasuryrestapi.model.ApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ApiKeyMapper {

    /**
     * Single indexed lookup used by the verification pipeline (step 5–7).
     * key_id is the primary key so this is O(1).
     */
    @Select("SELECT key_id, key_type, owner_uuid_bin AS owner_uuid, account_id, firm_id, " +
            "       jwt_id, revoked, issued_at, expires_at " +
            "FROM api_keys WHERE key_id = #{keyId}")
    ApiKey findByKeyId(@Param("keyId") long keyId);

    /**
     * Locking read used by the rotation service to re-confirm revocation status
     * before issuing a new token. Must be called within a transaction.
     */
    @Select("SELECT key_id, key_type, owner_uuid_bin AS owner_uuid, account_id, firm_id, " +
            "       jwt_id, revoked, issued_at, expires_at " +
            "FROM api_keys WHERE key_id = #{keyId} FOR UPDATE")
    ApiKey findByKeyIdForUpdate(@Param("keyId") long keyId);

    /**
     * Atomically rotates the key. The UNIQUE constraint on jwt_id (uq_api_key_jwt_id)
     * prevents two concurrent rotations from both succeeding with the same jti.
     *
     * <p>The {@code WHERE revoked = 0} clause is the SQL-level guard that
     * "revoked stays revoked" — if a future code path bypasses the service-
     * level revocation re-check, the UPDATE is a silent no-op rather than
     * un-revoking the key. Returns the rows-affected count so callers can
     * detect that case and fail fast.
     */
    @Update("UPDATE api_keys " +
            "SET jwt_id = #{jwtId}, issued_at = #{issuedAt}, " +
            "    expires_at = #{expiresAt} " +
            "WHERE key_id = #{keyId} AND revoked = 0")
    int rotateKey(@Param("keyId") long keyId,
                  @Param("jwtId") String jwtId,
                  @Param("issuedAt") LocalDateTime issuedAt,
                  @Param("expiresAt") LocalDateTime expiresAt);

    /** Revoke a key (ADT-14: replaces the explorer's direct UPDATE). Idempotent. */
    @Update("UPDATE api_keys SET revoked = 1 WHERE key_id = #{keyId}")
    int revoke(@Param("keyId") long keyId);
}
