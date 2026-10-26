package io.paradaux.treasury.mappers;

import io.paradaux.treasury.model.economy.ExplorerAuditEntry;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * Writer for the shared {@code explorer_audit} access log (economy-flyway V3).
 * The web explorer reads this table via its own Kysely DAL; this mapper lets the
 * Treasury plugin append in-game audit-command records to the same trail.
 */
@Mapper
public interface ExplorerAuditMapper {

    @Insert("""
            INSERT INTO explorer_audit
                (actor_sub, actor_uuid_bin, actor_name, actor_role, method, path,
                 target_type, target_id, outcome, source_ip)
            VALUES
                (#{actorSub}, #{actorUuid}, #{actorName}, #{actorRole}, #{method}, #{path},
                 #{targetType}, #{targetId}, #{outcome}, #{sourceIp})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "auditId", keyColumn = "audit_id")
    int insert(ExplorerAuditEntry entry);

    @Select("""
            SELECT audit_id, at, actor_sub, actor_uuid_bin, actor_name, actor_role, method, path,
                   target_type, target_id, outcome, source_ip
              FROM explorer_audit
             WHERE target_type = #{targetType} AND target_id = #{targetId}
             ORDER BY at DESC, audit_id DESC
            """)
    @Results(id = "explorerAuditMap", value = {
            @Result(column = "audit_id", property = "auditId", id = true),
            @Result(column = "at", property = "at", javaType = Instant.class),
            @Result(column = "actor_sub", property = "actorSub"),
            @Result(column = "actor_uuid_bin", property = "actorUuid"),
            @Result(column = "actor_name", property = "actorName"),
            @Result(column = "actor_role", property = "actorRole"),
            @Result(column = "method", property = "method"),
            @Result(column = "path", property = "path"),
            @Result(column = "target_type", property = "targetType"),
            @Result(column = "target_id", property = "targetId"),
            @Result(column = "outcome", property = "outcome"),
            @Result(column = "source_ip", property = "sourceIp")
    })
    List<ExplorerAuditEntry> findByTarget(@Param("targetType") String targetType,
                                          @Param("targetId") String targetId);
}
