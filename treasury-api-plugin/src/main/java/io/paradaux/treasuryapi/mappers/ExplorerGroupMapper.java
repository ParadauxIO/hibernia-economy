package io.paradaux.treasuryapi.mappers;

import io.paradaux.treasuryapi.model.SyncedGroup;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * Reads/writes explorer group membership for the reconciliation cron. The cron
 * only ever touches its own {@code source = 'luckperms'} rows, so manual grants
 * made in the explorer admin tool are never disturbed. UUID params use the
 * registered {@code UuidBinaryTypeHandler}.
 */
@Mapper
public interface ExplorerGroupMapper {

    @Select("SELECT group_id AS groupId, luckperms_node AS luckpermsNode "
            + "FROM explorer_group WHERE luckperms_node IS NOT NULL")
    List<SyncedGroup> listSyncedGroups();

    @Select("SELECT player_uuid_bin FROM explorer_group_member "
            + "WHERE group_id = #{groupId} AND source = 'luckperms'")
    List<UUID> listLuckpermsMemberUuids(@Param("groupId") int groupId);

    @Insert("INSERT IGNORE INTO explorer_group_member (group_id, player_uuid_bin, source) "
            + "VALUES (#{groupId}, #{uuid}, 'luckperms')")
    void addLuckpermsMember(@Param("groupId") int groupId, @Param("uuid") UUID uuid);

    @Delete("DELETE FROM explorer_group_member "
            + "WHERE group_id = #{groupId} AND player_uuid_bin = #{uuid} AND source = 'luckperms'")
    void removeLuckpermsMember(@Param("groupId") int groupId, @Param("uuid") UUID uuid);

    @Select("SELECT DISTINCT lp_group FROM account_group_access WHERE removed_at IS NULL")
    List<String> listAccountGroups();

    @Select("SELECT player_uuid_bin FROM explorer_group_member WHERE source = 'luckperms' "
            + "UNION SELECT player_uuid_bin FROM explorer_lp_group_member "
            + "UNION SELECT player_uuid_bin FROM economy_players")
    List<UUID> listSyncedPlayers();

    // Compare the source node on INSERT: a web edit cannot republish the old node's grants.
    @Insert("INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at) "
            + "SELECT group_id, #{uuid}, 'luckperms', CURRENT_TIMESTAMP FROM explorer_group "
            + "WHERE group_id = #{groupId} AND luckperms_node = #{node} "
            + "ON DUPLICATE KEY UPDATE synced_at = CURRENT_TIMESTAMP")
    void refreshLuckpermsMember(@Param("groupId") int groupId, @Param("uuid") UUID uuid, @Param("node") String node);

    @Select("SELECT player_uuid_bin FROM explorer_lp_group_member WHERE lp_group = #{group}")
    List<UUID> listAccountGroupMembers(@Param("group") String group);

    @Insert("INSERT INTO explorer_lp_group_member (lp_group, player_uuid_bin, synced_at) "
            + "VALUES (#{group}, #{uuid}, CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE synced_at = CURRENT_TIMESTAMP")
    void refreshAccountGroupMember(@Param("group") String group, @Param("uuid") UUID uuid);

    @Delete("DELETE FROM explorer_lp_group_member WHERE lp_group = #{group} AND player_uuid_bin = #{uuid}")
    void removeAccountGroupMember(@Param("group") String group, @Param("uuid") UUID uuid);

    @Delete("DELETE FROM explorer_lp_group_member WHERE lp_group NOT IN "
            + "(SELECT lp_group FROM account_group_access WHERE removed_at IS NULL)")
    void removeUnusedAccountGroups();
}
