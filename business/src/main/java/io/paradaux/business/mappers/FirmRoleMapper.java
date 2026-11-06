package io.paradaux.business.mappers;

import io.paradaux.business.internal.model.FirmRole;
import io.paradaux.business.internal.model.FirmRolePermission;
import io.paradaux.business.model.RolePermission;
import java.util.List;
import org.apache.ibatis.annotations.*;

/**
 * Roles and per-role permissions on a firm.
 *
 * <p><b>Soft-delete semantics.</b> Deleting a role or revoking a permission
 * sets {@code deleted_at} instead of removing the row. All reads filter on
 * {@code deleted_at IS NULL}. Re-creating a role with the same name (after
 * deletion) uses {@code INSERT … ON DUPLICATE KEY UPDATE} to clear
 * {@code deleted_at} and reuse the existing {@code role_id} so historical
 * permissions and employee assignments tied to that id remain consistent.
 */
public interface FirmRoleMapper {

    /**
     * Insert a role. If a role with the same {@code (firm_id, name)} exists
     * (active or soft-deleted), revives the existing row instead of creating
     * a duplicate — keeps {@code role_id} stable so historical
     * {@code firm_employee.role_id} references stay valid.
     */
    @Insert("""
            INSERT INTO firm_role (firm_id, name, rank_order)
            VALUES (#{firmId}, #{roleName}, #{roleRankOrder})
            ON DUPLICATE KEY UPDATE
                rank_order = VALUES(rank_order),
                deleted_at = NULL
            """)
    int insertRole(FirmRole role);

    /** Soft-delete a role by composite firmId + roleName. */
    @Update("""
            UPDATE firm_role
               SET deleted_at = CURRENT_TIMESTAMP
             WHERE firm_id = #{firmId}
               AND name = #{roleName}
               AND deleted_at IS NULL
            """)
    int deleteRole(@Param("firmId") int firmId, @Param("roleName") String roleName);

    /** Update only the rank order. */
    @Update("""
            UPDATE firm_role
               SET rank_order = #{newRank}
             WHERE firm_id = #{firmId}
               AND name = #{roleName}
               AND deleted_at IS NULL
            """)
    int updateRoleRank(@Param("firmId") int firmId, @Param("roleName") String roleName, @Param("newRank") int newRank);

    /** Rename a role. */
    @Update("""
            UPDATE firm_role
               SET name = #{newName}
             WHERE firm_id = #{firmId}
               AND name = #{oldName}
               AND deleted_at IS NULL
            """)
    int renameRole(@Param("firmId") int firmId, @Param("oldName") String oldName, @Param("newName") String newName);

    /** Find the proprietor role name (lowest rank_order). */
    @Select("""
            SELECT name
              FROM firm_role
             WHERE firm_id = #{firmId}
               AND deleted_at IS NULL
             ORDER BY rank_order ASC
             LIMIT 1
            """)
    String findProprietorRoleName(@Param("firmId") int firmId);

    /** Find the minimum rank_order for a firm. */
    @Select("""
            SELECT MIN(rank_order)
              FROM firm_role
             WHERE firm_id = #{firmId}
               AND deleted_at IS NULL
            """)
    Integer findMinRankOrder(@Param("firmId") int firmId);

    /** List roles for a firm, ordered by rank. */
    @Select("""
            SELECT firm_id    AS firmId,
                   name       AS roleName,
                   rank_order AS roleRankOrder
              FROM firm_role
             WHERE firm_id = #{firmId}
               AND deleted_at IS NULL
             ORDER BY rank_order ASC, name ASC
            """)
    List<FirmRole> listRolesByFirm(@Param("firmId") int firmId);

    /**
     * Add a permission to a role (uses role_id behind the scenes). Reactivates
     * a soft-deleted permission row of the same (role_id, permission) pair.
     */
    @Insert("""
            INSERT INTO firm_role_permission (role_id, permission)
            SELECT r.role_id, #{permission}
              FROM firm_role r
             WHERE r.firm_id = #{firmId}
               AND r.name = #{roleName}
               AND r.deleted_at IS NULL
            ON DUPLICATE KEY UPDATE deleted_at = NULL
            """)
    int addRolePermission(FirmRolePermission frp);

    /** Soft-remove a single permission from a role. */
    @Update("""
            UPDATE firm_role_permission rp
              JOIN firm_role r ON rp.role_id = r.role_id
               SET rp.deleted_at = CURRENT_TIMESTAMP
             WHERE r.firm_id = #{firmId}
               AND r.name = #{roleName}
               AND r.deleted_at IS NULL
               AND rp.permission = #{permission}
               AND rp.deleted_at IS NULL
            """)
    int deleteRolePermission(
            @Param("firmId") int firmId,
            @Param("roleName") String roleName,
            @Param("permission") RolePermission permission);

    /** List all permissions for a role. */
    @Select("""
            SELECT r.firm_id  AS firmId,
                   r.name     AS roleName,
                   rp.permission
              FROM firm_role_permission rp
              JOIN firm_role r ON rp.role_id = r.role_id
             WHERE r.firm_id = #{firmId}
               AND r.name = #{roleName}
               AND r.deleted_at IS NULL
               AND rp.deleted_at IS NULL
             ORDER BY rp.permission ASC
            """)
    List<FirmRolePermission> listPermissionsByRole(@Param("firmId") int firmId, @Param("roleName") String roleName);

    @Select("""
        SELECT rank_order
        FROM firm_role
        WHERE firm_id = #{firmId}
          AND name = #{roleName}
          AND deleted_at IS NULL
        """)
    Integer getRank(@Param("firmId") int firmId, @Param("roleName") String roleName);

    /** Find the next role above, excluding the proprietor role (minimum rank_order). */
    @Select("""
        SELECT name
        FROM firm_role
        WHERE firm_id = #{firmId}
          AND deleted_at IS NULL
          AND rank_order < #{currentRank}
          AND rank_order > (SELECT MIN(rank_order) FROM firm_role
                             WHERE firm_id = #{firmId} AND deleted_at IS NULL)
        ORDER BY rank_order DESC
        LIMIT 1
        """)
    String findRoleAbove(@Param("firmId") int firmId, @Param("currentRank") int currentRank);

    @Select("""
        SELECT name
        FROM firm_role
        WHERE firm_id = #{firmId}
          AND deleted_at IS NULL
          AND rank_order > #{currentRank}
        ORDER BY rank_order ASC
        LIMIT 1
        """)
    String findRoleBelow(@Param("firmId") int firmId, @Param("currentRank") int currentRank);

    @Select("""
        SELECT 1
        FROM firm_role
        WHERE firm_id = #{firmId}
          AND name = #{roleName}
          AND deleted_at IS NULL
        """)
    Integer roleExists(@Param("firmId") int firmId, @Param("roleName") String roleName);

    @Select("""
        SELECT name
        FROM firm_role
        WHERE firm_id = #{firmId}
          AND deleted_at IS NULL
        ORDER BY rank_order DESC
        LIMIT 1
        """)
    String findLowestRole(@Param("firmId") int firmId);

    @Select("""
        SELECT firm_id    AS firmId,
               name       AS roleName,
               rank_order AS roleRankOrder
        FROM firm_role
        WHERE firm_id = #{firmId}
          AND deleted_at IS NULL
        ORDER BY rank_order ASC, name ASC
        """)
    List<FirmRole> getFirmRoles(@Param("firmId") int firmId);

    @Select("""
        SELECT r.firm_id  AS firmId,
               r.name     AS roleName,
               rp.permission
        FROM firm_role_permission rp
        JOIN firm_role r ON rp.role_id = r.role_id
        WHERE r.firm_id = #{firmId}
          AND r.name = #{roleName}
          AND r.deleted_at IS NULL
          AND rp.deleted_at IS NULL
        ORDER BY rp.permission ASC
        """)
    List<FirmRolePermission> getFirmRolePermissions(@Param("firmId") int firmId, @Param("roleName") String roleName);
}
