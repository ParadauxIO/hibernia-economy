package io.paradaux.treasuryapi.tasks;

import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.mappers.ExplorerGroupMapper;
import io.paradaux.treasuryapi.model.SyncedGroup;
import net.luckperms.api.LuckPerms;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the node-key normalisation that drives the group-inheritance
 * expansion in {@link GroupReconciliationTask} (the LuckPerms-dependent paths need
 * a running LuckPerms and are smoke-tested in-game).
 */
class GroupReconciliationTaskTest {

    @Test
    void nodeKey_groupPrefixed_keptAsGroupKey() {
        assertEquals("group.government", GroupReconciliationTask.nodeKey("group.government"));
    }

    @Test
    void nodeKey_bareName_becomesGroupMembership() {
        assertEquals("group.government", GroupReconciliationTask.nodeKey("government"));
    }

    @Test
    void nodeKey_dottedNode_isAPermission() {
        assertEquals("treasury.group.government",
                GroupReconciliationTask.nodeKey("treasury.group.government"));
    }

    @Test
    void nodeKey_isLowerCased() {
        assertEquals("group.vip", GroupReconciliationTask.nodeKey("GROUP.VIP"));
        assertEquals("group.vip", GroupReconciliationTask.nodeKey("VIP"));
        assertEquals("some.perm.node", GroupReconciliationTask.nodeKey("Some.Perm.Node"));
    }

    // ---- Mass-revoke safety guard (ADT treasury-api-plugin/testing/0004) ----
    // The safety-critical property lives in the private apply(groupId, desired): when
    // the desired (LuckPerms-resolved) membership is EMPTY while the group currently
    // has synced members, apply must treat that as a transient/degraded result and NOT
    // prune — a briefly-stale over-grant is the safe failure mode; a one-tick
    // mass-revoke of a whole group's access is not. We invoke apply directly (the
    // node-resolution above it needs a live LuckPerms provider), which is exactly the
    // decision point the guard protects.

    private static TreasuryAPI pluginWithLogger() {
        TreasuryAPI plugin = mock(TreasuryAPI.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("group-reconciliation-test"));
        return plugin;
    }

    private static GroupReconciliationTask task(ExplorerGroupMapper mapper) {
        // LuckPerms is unused by apply(); a mock satisfies the constructor.
        return new GroupReconciliationTask(pluginWithLogger(), mapper, mock(LuckPerms.class));
    }

    @SuppressWarnings("unchecked")
    private static void invokeApply(GroupReconciliationTask t, int groupId, Set<UUID> desired) throws Exception {
        var m = GroupReconciliationTask.class.getDeclaredMethod("apply", int.class, Set.class);
        m.setAccessible(true);
        m.invoke(t, groupId, desired);
    }

    @Test
    void apply_emptyDesired_whileMembersSynced_doesNotPrune() throws Exception {
        ExplorerGroupMapper mapper = mock(ExplorerGroupMapper.class);
        // Group currently has two synced members; the node now resolves to none.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(mapper.listLuckpermsMemberUuids(1)).thenReturn(List.of(a, b));

        invokeApply(task(mapper), 1, Set.of());

        // The guard fired: NOT ONE member was removed despite desired being empty.
        verify(mapper, never()).removeLuckpermsMember(anyInt(), any(UUID.class));
        verify(mapper, never()).addLuckpermsMember(anyInt(), any(UUID.class));
    }

    @Test
    void apply_emptyDesired_whenGroupAlreadyEmpty_isANoOp() throws Exception {
        // Empty-desired + empty-current is benign: the guard's `!current.isEmpty()`
        // half means it doesn't trip, and there is nothing to add or remove.
        ExplorerGroupMapper mapper = mock(ExplorerGroupMapper.class);
        when(mapper.listLuckpermsMemberUuids(2)).thenReturn(List.of());

        invokeApply(task(mapper), 2, Set.of());

        verify(mapper, never()).removeLuckpermsMember(anyInt(), any(UUID.class));
        verify(mapper, never()).addLuckpermsMember(anyInt(), any(UUID.class));
    }

    @Test
    void apply_nonEmptyDesired_addsMissing_andRemovesStale() throws Exception {
        // Positive contrast: with a non-empty desired set the guard does NOT trip, so
        // real reconciliation runs — a member present in desired but not current is
        // added, and one present in current but not desired is pruned. This proves the
        // guard blocks only the empty-result case, not all reconciliation.
        ExplorerGroupMapper mapper = mock(ExplorerGroupMapper.class);
        UUID keep = UUID.randomUUID();   // in both desired and current
        UUID add = UUID.randomUUID();    // desired, not current -> add
        UUID stale = UUID.randomUUID();  // current, not desired -> remove
        when(mapper.listLuckpermsMemberUuids(3)).thenReturn(List.of(keep, stale));

        invokeApply(task(mapper), 3, Set.of(keep, add));

        verify(mapper).addLuckpermsMember(3, add);
        verify(mapper).removeLuckpermsMember(3, stale);
        verify(mapper, never()).addLuckpermsMember(3, keep);
        verify(mapper, never()).removeLuckpermsMember(3, keep);
    }
}
