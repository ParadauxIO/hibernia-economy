package io.paradaux.treasuryapi.tasks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.mappers.ExplorerGroupMapper;
import io.paradaux.treasuryapi.model.SyncedGroup;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupReconciliationTaskTest {
    private final ExplorerGroupMapper mapper = mock(ExplorerGroupMapper.class);
    private final LuckPerms lp = mock(LuckPerms.class, RETURNS_DEEP_STUBS);
    private final QueryOptions options = mock(QueryOptions.class);
    private final UUID id = UUID.randomUUID();
    private final User user = mock(User.class, RETURNS_DEEP_STUBS);
    private GroupReconciliationTask task;

    @BeforeEach
    void setup() {
        TreasuryAPI plugin = mock(TreasuryAPI.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("lp-test"));
        task = new GroupReconciliationTask(plugin, mapper, lp);
        SyncedGroup group = new SyncedGroup();
        group.setGroupId(1);
        group.setLuckpermsNode("group.staff");
        when(mapper.listSyncedGroups()).thenReturn(List.of(group));
        when(mapper.listAccountGroups()).thenReturn(List.of("staff"));
        when(lp.getGroupManager().loadAllGroups()).thenReturn(CompletableFuture.completedFuture(null));
        when(lp.getUserManager().getUniqueUsers()).thenReturn(CompletableFuture.completedFuture(Set.of(id)));
        when(lp.getUserManager().loadUser(id)).thenReturn(CompletableFuture.completedFuture(user));
        when(lp.getContextManager().getStaticQueryOptions()).thenReturn(options);
    }

    @Test
    void normalizesNamesAndNodes() {
        assertEquals("group.vip", GroupReconciliationTask.nodeKey(" GROUP.VIP "));
        assertEquals("group.vip", GroupReconciliationTask.nodeKey("VIP"));
        assertEquals("treasury.staff", GroupReconciliationTask.nodeKey("Treasury.Staff"));
        assertThrows(IllegalArgumentException.class, () -> GroupReconciliationTask.nodeKey(" "));
    }

    @Test
    void membershipUsesResolvedInheritanceNotSyntheticPermission() {
        Group group = mock(Group.class);
        when(group.getName()).thenReturn("staff");
        when(user.getInheritedGroups(options)).thenReturn(List.of(group));
        assertTrue(GroupReconciliationTask.matches(user, "STAFF", options));
        verify(user, never()).getCachedData();
    }

    @Test
    void permissionNodesRespectEffectiveDeny() {
        when(user.getCachedData().getPermissionData(options).checkPermission("treasury.staff"))
                .thenReturn(Tristate.FALSE);
        assertFalse(GroupReconciliationTask.matches(user, "treasury.staff", options));
        when(user.getCachedData().getPermissionData(options).checkPermission("treasury.staff"))
                .thenReturn(Tristate.TRUE);
        assertTrue(GroupReconciliationTask.matches(user, "treasury.staff", options));
    }

    @Test
    void emptyResolvedMembershipRevokesFinalMemberInBothSurfaces() {
        when(mapper.listLuckpermsMemberUuids(1)).thenReturn(List.of(id));
        when(mapper.listAccountGroupMembers("staff")).thenReturn(List.of(id));
        when(user.getInheritedGroups(options)).thenReturn(List.of());
        task.run();
        verify(mapper).removeLuckpermsMember(1, id);
        verify(mapper).removeAccountGroupMember("staff", id);
        verify(mapper, never()).refreshLuckpermsMember(anyInt(), any(), anyString());
    }

    @Test
    void effectiveMemberIsRefreshedInBothSurfaces() {
        Group group = mock(Group.class);
        when(group.getName()).thenReturn("staff");
        when(user.getInheritedGroups(options)).thenReturn(List.of(group));
        task.run();
        verify(mapper).refreshLuckpermsMember(1, id, "group.staff");
        verify(mapper).refreshAccountGroupMember("staff", id);
        verify(user).auditTemporaryNodes();
        verify(lp.getUserManager()).cleanupUser(user);
    }

    @Test
    void failureDoesNotPublishOrPruneSnapshot() {
        when(lp.getUserManager().loadUser(id))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        task.run();
        verify(mapper, never()).refreshLuckpermsMember(anyInt(), any(), anyString());
        verify(mapper, never()).removeLuckpermsMember(anyInt(), any());
        verify(mapper, never()).refreshAccountGroupMember(anyString(), any());
        verify(mapper, never()).removeAccountGroupMember(anyString(), any());
    }

    @Test
    void selfSyncUsesTheSameRelationshipResolution() {
        Group group = mock(Group.class);
        when(group.getName()).thenReturn("staff");
        when(user.getInheritedGroups(options)).thenReturn(List.of(group));
        task.reconcilePlayer(id);
        verify(mapper).refreshLuckpermsMember(1, id, "group.staff");
        verify(mapper).refreshAccountGroupMember("staff", id);
        when(user.getInheritedGroups(options)).thenReturn(List.of());
        task.reconcilePlayer(id);
        verify(mapper).removeLuckpermsMember(1, id);
        verify(mapper).removeAccountGroupMember("staff", id);
    }

    @Test
    void resolvesGroupsOncePerUserAndRefreshesOnNextRun() {
        Group staffGroup = mock(Group.class);
        when(staffGroup.getName()).thenReturn("STAFF");
        when(user.getInheritedGroups(options)).thenReturn(List.of(staffGroup));
        when(mapper.listAccountGroups()).thenReturn(List.of("staff", "admin", "finance"));

        task.run();

        verify(user, times(1)).getInheritedGroups(options);
        verify(mapper).refreshLuckpermsMember(1, id, "group.staff");
        verify(mapper).refreshAccountGroupMember("staff", id);
        verify(mapper, never()).refreshAccountGroupMember(eq("admin"), any());
        verify(user, never()).getCachedData();

        when(user.getInheritedGroups(options)).thenReturn(List.of());
        when(mapper.listLuckpermsMemberUuids(1)).thenReturn(List.of(id));
        when(mapper.listAccountGroupMembers("staff")).thenReturn(List.of(id));
        task.run();

        verify(user, times(2)).getInheritedGroups(options);
        verify(mapper).removeLuckpermsMember(1, id);
        verify(mapper).removeAccountGroupMember("staff", id);
    }

    @Test
    void selfSyncReusesResolutionButDoesNotShareItBetweenPlayers() {
        Group staffGroup = mock(Group.class);
        when(staffGroup.getName()).thenReturn("staff");
        when(user.getInheritedGroups(options)).thenReturn(List.of(staffGroup));
        task.reconcilePlayer(id);
        verify(user, times(1)).getInheritedGroups(options);
        verify(mapper).refreshAccountGroupMember("staff", id);

        UUID otherId = UUID.randomUUID();
        User other = mock(User.class);
        when(lp.getUserManager().loadUser(otherId)).thenReturn(CompletableFuture.completedFuture(other));
        when(other.getInheritedGroups(options)).thenReturn(List.of());
        task.reconcilePlayer(otherId);
        verify(other, times(1)).getInheritedGroups(options);
        verify(mapper).removeAccountGroupMember("staff", otherId);
        verify(mapper, never()).refreshAccountGroupMember("staff", otherId);
    }

    @Test
    void permissionBindingsReusePermissionDataAndKeepEffectiveDenials() {
        SyncedGroup allowed = new SyncedGroup();
        allowed.setGroupId(2);
        allowed.setLuckpermsNode(" Treasury.Read ");
        SyncedGroup denied = new SyncedGroup();
        denied.setGroupId(3);
        denied.setLuckpermsNode("treasury.write");
        when(mapper.listSyncedGroups()).thenReturn(List.of(allowed, denied));
        when(mapper.listAccountGroups()).thenReturn(List.of());
        var cachedData = user.getCachedData();
        var permissions = cachedData.getPermissionData(options);
        when(permissions.checkPermission("treasury.read")).thenReturn(Tristate.TRUE);
        when(permissions.checkPermission("treasury.write")).thenReturn(Tristate.FALSE);
        clearInvocations(user, cachedData);

        task.reconcilePlayer(id);

        verify(cachedData, times(1)).getPermissionData(options);
        verify(user, never()).getInheritedGroups(any());
        verify(mapper).refreshLuckpermsMember(2, id, " Treasury.Read ");
        verify(mapper).removeLuckpermsMember(3, id);
        verify(mapper, never()).refreshLuckpermsMember(eq(3), any(), anyString());
    }

    @Test
    void formerUsersAreStillCheckedWhenNoLongerInLpStorage() {
        when(lp.getUserManager().getUniqueUsers()).thenReturn(CompletableFuture.completedFuture(Set.of()));
        when(mapper.listSyncedPlayers()).thenReturn(List.of(id));
        when(mapper.listLuckpermsMemberUuids(1)).thenReturn(List.of(id));
        task.run();
        verify(lp.getUserManager()).loadUser(id);
        verify(mapper).removeLuckpermsMember(1, id);
    }
}
