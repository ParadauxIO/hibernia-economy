package io.paradaux.treasuryapi.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.treasuryapi.model.GroupSource;
import io.paradaux.treasuryapi.testsupport.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DerivedGroupMapperIT extends IntegrationTestBase {
    @Test
    void staleBindingCannotRemoveMembershipPublishedByTheCurrentBinding() throws Exception {
        var mapper = mapper(DerivedGroupMapper.class);
        var previous = new GroupSource(1, "business", "7");
        var current = new GroupSource(1, "business", "8");
        UUID oldOwner = UUID.randomUUID(), newOwner = UUID.randomUUID();
        execute("INSERT INTO explorer_group (group_id, name) VALUES (1, 'Retargeted')");
        execute("INSERT INTO explorer_group_sync VALUES (1, 'business', '7')");
        mapper.refresh(previous, oldOwner);
        // Another publisher completes after this pass resolved the previous selector.
        execute("UPDATE explorer_group_sync SET selector = '8' WHERE group_id = 1 AND source = 'business'");
        mapper.refresh(current, newOwner);
        mapper.remove(previous, newOwner);
        assertThat(mapper.listMembers(current)).containsExactlyInAnyOrder(oldOwner, newOwner);
        // The current publisher must still be able to remove an old-selector row.
        mapper.remove(current, oldOwner);
        assertThat(mapper.listMembers(current)).containsExactly(newOwner);
    }

    @Test
    void bindingsFenceStaleWritesAndSourcesRemainIndependent() throws Exception {
        var mapper = mapper(DerivedGroupMapper.class);
        var lp = mapper(ExplorerGroupMapper.class);
        UUID player = UUID.randomUUID();
        var business = new GroupSource(1, "business", "7");
        var realty = new GroupSource(1, "realty", "world/plot");
        execute("INSERT INTO explorer_group (group_id, name) VALUES (1, 'Combined')");
        execute("INSERT INTO explorer_group_sync VALUES (1, 'business', '7'), (1, 'realty', 'world/plot')");
        execute("INSERT INTO explorer_group_member (group_id, player_uuid_bin) VALUES (1, uuid_to_bin('" + player
                + "'))");
        lp.addLuckpermsMember(1, player);
        assertThat(mapper.listSources()).containsExactlyInAnyOrder(business, realty);
        mapper.refresh(business, player);
        mapper.refresh(business, player);
        mapper.refresh(realty, player);
        assertThat(mapper.listMembers(business)).containsExactly(player);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM explorer_group_member WHERE synced_at > NOW() - INTERVAL 1 HOUR AND sync_selector IS NOT NULL"))
                .isEqualTo(2);
        mapper.remove(business, player);
        assertThat(mapper.listMembers(business)).isEmpty();
        assertThat(mapper.listMembers(realty)).containsExactly(player);
        assertThat(lp.listLuckpermsMemberUuids(1)).containsExactly(player);
        assertThat(count("SELECT COUNT(*) FROM explorer_group_member WHERE source = 'manual'"))
                .isEqualTo(1);
        execute("UPDATE explorer_group_sync SET selector = '8' WHERE source = 'business'");
        mapper.refresh(business, player);
        assertThat(mapper.listMembers(business)).isEmpty();
        execute("DELETE FROM explorer_group_sync WHERE source = 'realty'");
        for (var binding : mapper.listUnconfigured()) mapper.removeUnconfigured(binding);
        assertThat(mapper.listMembers(realty)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM explorer_group_member")).isEqualTo(2);
    }

    private void execute(String sql) throws Exception {
        try (var c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private int count(String sql) throws Exception {
        try (var c = dataSource.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        }
    }
}
