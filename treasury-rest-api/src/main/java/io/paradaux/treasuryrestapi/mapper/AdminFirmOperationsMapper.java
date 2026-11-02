package io.paradaux.treasuryrestapi.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

/** Persistence for operator interventions; the service owns authorization and atomicity. */
@Mapper
public interface AdminFirmOperationsMapper {
    @Update("UPDATE firm SET proprietor_uuid_bin = #{owner} WHERE firm_id = #{id}")
    void setProprietor(@Param("id") long id, @Param("owner") UUID owner);

    @Update(
            "UPDATE firm_employee SET left_at = CURRENT_TIMESTAMP, removed_by_uuid_bin = #{actor} WHERE firm_id = #{id} AND player_uuid_bin = #{owner} AND left_at IS NULL")
    void removeEmployee(@Param("id") long id, @Param("owner") UUID owner, @Param("actor") UUID actor);

    @Update(
            "UPDATE firm_transfer_requests SET status = 'CANCELLED' WHERE firm_id = #{id} AND status IN ('PENDING','CONFIRMED')")
    void cancelTransfers(long id);

    @Insert(
            "INSERT INTO firm_transfer_requests (firm_id, from_uuid_bin, to_uuid_bin, actor_uuid_bin, token, status, expires_at) VALUES (#{id}, #{previous}, #{owner}, #{actor}, #{token}, 'ADMIN_OVERRIDE', CURRENT_TIMESTAMP)")
    void recordOverride(
            @Param("id") long id,
            @Param("previous") UUID previous,
            @Param("owner") UUID owner,
            @Param("actor") UUID actor,
            @Param("token") String token);

    @Update(
            "UPDATE api_keys SET revoked = 1 WHERE key_type = 'BUSINESS' AND firm_id = #{id} AND owner_uuid_bin = #{previous}")
    void revokeOutgoingKeys(@Param("id") long id, @Param("previous") UUID previous);

    @Update("UPDATE firm SET name_released = 1 WHERE firm_id = #{id} AND is_archived = 1")
    void releaseName(long id);

    @Select("SELECT name_released FROM firm WHERE firm_id = #{id}")
    boolean nameReleased(long id);

    @Select(
            "SELECT EXISTS (SELECT 1 FROM firm_properties WHERE firm_id = #{id} AND `key` = 'balance-tax.exempt' AND type = 'BOOLEAN' AND value = 'true' AND deleted_at IS NULL)")
    boolean taxExempt(long id);

    @Insert(
            "INSERT INTO firm_properties (firm_id, `key`, value, type) VALUES (#{id}, 'balance-tax.exempt', #{value}, 'BOOLEAN') ON DUPLICATE KEY UPDATE value = VALUES(value), type = 'BOOLEAN', deleted_at = NULL")
    void setTaxExempt(@Param("id") long id, @Param("value") String value);

    @Select(
            "SELECT DISTINCT fe.player_uuid_bin FROM firm_employee fe JOIN firm_role fr ON fr.role_id = fe.role_id AND fr.firm_id = fe.firm_id AND fr.deleted_at IS NULL JOIN firm_role_permission rp ON rp.role_id = fr.role_id AND rp.deleted_at IS NULL WHERE fe.firm_id = #{id} AND fe.left_at IS NULL AND (rp.permission = 'ADMIN' OR (#{members} AND rp.permission = 'FINANCIAL'))")
    List<UUID> staffWithAccess(@Param("id") long id, @Param("members") boolean members);

    // VIEWER grants are independent of Business's member/authorizer reconciliation.
    @Update({
        "<script>UPDATE account_access SET removed_at = CURRENT_TIMESTAMP WHERE account_id = #{id} AND removed_at IS NULL AND level IN ('MEMBER','AUTHORIZER') AND subject_uuid_bin NOT IN <foreach collection='expected' item='uuid' open='(' separator=',' close=')'>#{uuid}</foreach></script>"
    })
    void removeStaleAccess(@Param("id") long id, @Param("expected") List<UUID> expected);

    @Insert(
            "INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin) VALUES (#{id}, #{subject}, #{level}, #{actor}) ON DUPLICATE KEY UPDATE level = VALUES(level), removed_at = NULL, added_by_uuid_bin = VALUES(added_by_uuid_bin)")
    void setAccess(
            @Param("id") long id,
            @Param("subject") UUID subject,
            @Param("level") String level,
            @Param("actor") UUID actor);
}
