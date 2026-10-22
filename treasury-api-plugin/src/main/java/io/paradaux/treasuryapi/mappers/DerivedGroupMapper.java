package io.paradaux.treasuryapi.mappers;

import io.paradaux.treasuryapi.model.GroupSource;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DerivedGroupMapper {
    @Select("SELECT group_id, source, selector FROM explorer_group_sync")
    List<GroupSource> listSources();

    @Select("SELECT player_uuid_bin FROM explorer_group_member WHERE group_id = #{groupId} AND source = #{source}")
    List<UUID> listMembers(GroupSource binding);

    @Insert("INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at, sync_selector) "
            + "SELECT group_id, #{uuid}, source, CURRENT_TIMESTAMP, selector FROM explorer_group_sync "
            + "WHERE group_id = #{binding.groupId} AND source = #{binding.source} AND BINARY selector = BINARY #{binding.selector} "
            + "ON DUPLICATE KEY UPDATE synced_at = CURRENT_TIMESTAMP, sync_selector = VALUES(sync_selector)")
    void refresh(@Param("binding") GroupSource binding, @Param("uuid") UUID uuid);

    @Delete("DELETE FROM explorer_group_member WHERE group_id = #{binding.groupId} AND source = #{binding.source} "
            + "AND player_uuid_bin = #{uuid} AND source IN ('business', 'realty') "
            + "AND EXISTS (SELECT 1 FROM explorer_group_sync s WHERE s.group_id = #{binding.groupId} "
            + "AND s.source = #{binding.source} AND BINARY s.selector = BINARY #{binding.selector})")
    void remove(@Param("binding") GroupSource binding, @Param("uuid") UUID uuid);

    @Delete("DELETE m FROM explorer_group_member m LEFT JOIN explorer_group_sync s "
            + "ON s.group_id = m.group_id AND s.source = m.source "
            + "WHERE m.source IN ('business', 'realty') AND s.group_id IS NULL")
    void removeUnconfigured();
}
