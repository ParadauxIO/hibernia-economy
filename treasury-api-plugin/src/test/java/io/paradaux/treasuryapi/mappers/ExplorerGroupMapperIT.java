package io.paradaux.treasuryapi.mappers;

import io.paradaux.treasuryapi.model.SyncedGroup;
import io.paradaux.treasuryapi.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ExplorerGroupMapper} against real MariaDB. The cron only ever
 * touches its own {@code source='luckperms'} rows, so these pin: the synced-group
 * listing filters on a non-null node, the member add is INSERT-IGNORE idempotent,
 * and add/list/remove never disturb {@code source='manual'} grants.
 */
class ExplorerGroupMapperIT extends IntegrationTestBase {

    private ExplorerGroupMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mapper(ExplorerGroupMapper.class);
    }

    private int insertGroup(String name, String node) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO explorer_group (name, luckperms_node) VALUES (?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, node);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) { rs.next(); return rs.getInt(1); }
        }
    }

    private void insertManualMember(int groupId, UUID player) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO explorer_group_member (group_id, player_uuid_bin, source) "
                             + "VALUES (?, uuid_to_bin(?), 'manual')")) {
            ps.setInt(1, groupId);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    @Test
    void listSyncedGroups_returnsOnlyGroupsWithANode() throws Exception {
        int synced = insertGroup("Gov", "group.government");
        insertGroup("ManualOnly", null);

        List<SyncedGroup> groups = mapper.listSyncedGroups();
        assertThat(groups).extracting(SyncedGroup::getGroupId).containsExactly(synced);
        assertThat(groups.get(0).getLuckpermsNode()).isEqualTo("group.government");
    }

    @Test
    void addLuckpermsMember_isIdempotent_andListReadsBackTheUuid() throws Exception {
        int groupId = insertGroup("Gov", "group.government");
        UUID player = UUID.randomUUID();

        mapper.addLuckpermsMember(groupId, player);
        mapper.addLuckpermsMember(groupId, player); // INSERT IGNORE — no duplicate/error

        assertThat(mapper.listLuckpermsMemberUuids(groupId)).containsExactly(player);
    }

    @Test
    void luckpermsOps_neverTouchManualGrants() throws Exception {
        int groupId = insertGroup("Gov", "group.government");
        UUID manual = UUID.randomUUID();
        UUID synced = UUID.randomUUID();
        insertManualMember(groupId, manual);
        mapper.addLuckpermsMember(groupId, synced);

        // listLuckpermsMemberUuids sees only the luckperms row, not the manual one.
        assertThat(mapper.listLuckpermsMemberUuids(groupId)).containsExactly(synced);

        // Removing the luckperms member leaves the manual grant intact.
        mapper.removeLuckpermsMember(groupId, synced);
        assertThat(mapper.listLuckpermsMemberUuids(groupId)).isEmpty();
        assertThat(manualMemberCount(groupId, manual)).isEqualTo(1);
    }

    @Test
    void removeLuckpermsMember_doesNotRemoveAManualRowForTheSameUuid() throws Exception {
        // A player can hold BOTH a manual and a luckperms membership (distinct source);
        // pruning the luckperms one must not delete the manual one.
        int groupId = insertGroup("Gov", "group.government");
        UUID player = UUID.randomUUID();
        insertManualMember(groupId, player);
        mapper.addLuckpermsMember(groupId, player);

        mapper.removeLuckpermsMember(groupId, player);

        assertThat(mapper.listLuckpermsMemberUuids(groupId)).isEmpty();
        assertThat(manualMemberCount(groupId, player)).isEqualTo(1);
    }

    @Test
    void refreshLeasesAndSourceComparisonPreserveManualGrants() throws Exception {
        int group = insertGroup("Staff", "group.staff");
        UUID player = UUID.randomUUID();
        insertManualMember(group, player);
        mapper.refreshLuckpermsMember(group, player, "group.staff");
        mapper.refreshLuckpermsMember(group, player, "group.staff");
        assertThat(mapper.listLuckpermsMemberUuids(group)).containsExactly(player);
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            try (var rs = st.executeQuery("SELECT COUNT(*) FROM explorer_group_member WHERE source = 'luckperms' AND synced_at > NOW() - INTERVAL 1 HOUR")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            st.executeUpdate("UPDATE explorer_group SET luckperms_node = NULL WHERE group_id = " + group);
        }
        mapper.removeLuckpermsMember(group, player);
        mapper.refreshLuckpermsMember(group, player, "group.staff");
        assertThat(mapper.listLuckpermsMemberUuids(group)).isEmpty();
        assertThat(manualMemberCount(group, player)).isEqualTo(1);
    }

    @Test
    void accountGroupSnapshotCanBeRefreshedAndRevoked() {
        UUID player = UUID.randomUUID();
        mapper.refreshAccountGroupMember("staff", player);
        mapper.refreshAccountGroupMember("staff", player);
        assertThat(mapper.listAccountGroupMembers("staff")).containsExactly(player);
        mapper.removeAccountGroupMember("staff", player);
        assertThat(mapper.listAccountGroupMembers("staff")).isEmpty();
    }

    private int manualMemberCount(int groupId, UUID player) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM explorer_group_member "
                             + "WHERE group_id = ? AND player_uuid_bin = uuid_to_bin(?) AND source = 'manual'")) {
            ps.setInt(1, groupId);
            ps.setString(2, player.toString());
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
