package io.paradaux.business.mappers;

import io.paradaux.business.model.internal.FirmEmployee;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface FirmStaffMapper {

    @Select("""
            SELECT r.name
            FROM firm_employee e
            JOIN firm_role r ON e.role_id = r.role_id
            WHERE e.firm_id = #{firmId}
              AND e.player_uuid_bin = uuid_to_bin(#{playerUuid})
              AND e.is_current = 1
            """)
    String getCurrentRole(@Param("firmId") int firmId, @Param("playerUuid") String playerUuid);

    @Update("""
            UPDATE firm_employee e
            JOIN firm_role r
              ON r.firm_id = e.firm_id
             AND r.name = #{newRole}
               SET e.role_id = r.role_id
             WHERE e.firm_id = #{firmId}
               AND e.player_uuid_bin = uuid_to_bin(#{playerUuid})
               AND e.is_current = 1
            """)
    int updateCurrentRole(
            @Param("firmId") int firmId, @Param("playerUuid") String playerUuid, @Param("newRole") String newRole);

    @Select("""
            SELECT EXISTS(
                SELECT 1
                FROM firm_employee e
                WHERE e.firm_id = #{firmId}
                  AND e.player_uuid_bin = uuid_to_bin(#{playerUuid})
                  AND e.is_current = 1
            )
            """)
    boolean isEmployedBy(@Param("firmId") int firmId, @Param("playerUuid") String playerUuid);

    @Insert("""
            INSERT INTO firm_employee (firm_id, player_uuid_bin, role_id, joined_at, added_by_uuid_bin)
            SELECT #{firmId},
                   uuid_to_bin(#{playerUuid}),
                   r.role_id,
                   CURRENT_TIMESTAMP,
                   uuid_to_bin(#{actorUuid})
              FROM firm_role r
             WHERE r.firm_id = #{firmId}
               AND r.name = #{roleName}
            """)
    int insertEmployment(
            @Param("firmId") int firmId,
            @Param("playerUuid") String playerUuid,
            @Param("roleName") String roleName,
            @Param("actorUuid") String actorUuid);

    @Update("""
                UPDATE firm_employee
                   SET left_at = CURRENT_TIMESTAMP,
                       removed_by_uuid_bin = uuid_to_bin(#{actorUuid})
                 WHERE firm_id = #{firmId}
                   AND player_uuid_bin = uuid_to_bin(#{playerUuid})
                   AND left_at IS NULL
            """)
    int endCurrentEmployment(
            @Param("firmId") int firmId, @Param("playerUuid") String playerUuid, @Param("actorUuid") String actorUuid);

    @Select("""
            SELECT e.firm_id                                AS firmId,
                   bin_to_uuid(e.player_uuid_bin)           AS playerUuid,
                   r.name                                   AS roleName,
                   e.joined_at                              AS joinedAt,
                   e.left_at                                AS leftAt,
                   bin_to_uuid(e.added_by_uuid_bin)         AS addedBy,
                   bin_to_uuid(e.removed_by_uuid_bin)       AS removedBy,
                   e.is_current                             AS current
              FROM firm_employee e
              JOIN firm_role r ON e.role_id = r.role_id
             WHERE e.firm_id = #{firmId}
               AND e.is_current = 1
             ORDER BY e.joined_at ASC
            """)
    List<FirmEmployee> listCurrentEmployeesByFirm(@Param("firmId") int firmId);
}
