package io.paradaux.treasuryapi.mappers;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.services.KeycloakAdminClient;
import io.paradaux.treasuryapi.services.impl.ExplorerUiServiceImpl;
import io.paradaux.treasuryapi.tasks.GroupReconciliationTask;
import io.paradaux.treasuryapi.testsupport.IntegrationTestBase;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real service/task -> MyBatis -> authoritative Flyway schema. Only Bukkit/LP are mocked. */
class ExplorerAuthorizationIT extends IntegrationTestBase {
    private final UUID player = UUID.randomUUID();
    private ExplorerUiServiceImpl service;
    private ExplorerUiMapper roles;
    private ExplorerGroupMapper groups;
    private GroupReconciliationTask sync;
    private LuckPerms lp;
    private User user;
    private QueryOptions options;

    @BeforeEach
    void setup() throws Exception {
        roles = mapper(ExplorerUiMapper.class);
        groups = mapper(ExplorerGroupMapper.class);
        TreasuryAPI plugin = mock(TreasuryAPI.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("auth-integration"));
        lp = mock(LuckPerms.class, RETURNS_DEEP_STUBS);
        user = mock(User.class, RETURNS_DEEP_STUBS);
        options = mock(QueryOptions.class);
        when(lp.getGroupManager().loadAllGroups()).thenReturn(CompletableFuture.completedFuture(null));
        when(lp.getUserManager().getUniqueUsers()).thenReturn(CompletableFuture.completedFuture(Set.of(player)));
        when(lp.getUserManager().loadUser(player)).thenReturn(CompletableFuture.completedFuture(user));
        when(lp.getContextManager().getStaticQueryOptions()).thenReturn(options);
        sync = new GroupReconciliationTask(plugin, groups, lp);
        service = new ExplorerUiServiceImpl(roles, mock(KeycloakAdminClient.class), plugin, Optional.of(sync));
        execute("INSERT INTO explorer_role_definition (role_key, name) VALUES ('auditor', 'Auditor')");
        execute("INSERT INTO explorer_role_permission VALUES ('auditor', 'transactions.read')");
    }

    @Test
    void customRoleCommandRoundTripAndDeletionCascades() throws Exception {
        assertThat(service.grantRole(player, "AUDITOR", null)).isEqualTo("auditor");
        service.grantRole(player, "auditor", null); // duplicate grants are idempotent
        assertThat(service.listRoles(player)).containsExactly("auditor");
        assertThat(count("SELECT COUNT(*) FROM explorer_role r JOIN explorer_role_permission p ON p.role_key = r.role WHERE p.permission = 'transactions.read'")).isEqualTo(1);
        assertThat(service.revokeRole(player, "AUDITOR")).isTrue();
        assertThat(service.revokeRole(player, "auditor")).isFalse();
        service.grantRole(player, "auditor", null);
        execute("INSERT INTO explorer_group (group_id, name) VALUES (1, 'Auditors')");
        execute("INSERT INTO explorer_group_capability VALUES (1, 'auditor')");
        execute("DELETE FROM explorer_role_definition WHERE role_key = 'auditor'");
        assertThat(service.listRoles(player)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM explorer_group_capability")).isZero();
        assertThatThrownBy(() -> service.grantRole(player, "auditor", null)).isInstanceOf(BadCommandException.class);
        execute("INSERT INTO explorer_role_definition (role_key, name) VALUES ('auditor', 'Recreated')");
        assertThat(service.listRoles(player)).isEmpty();
    }

    @Test
    void databaseRejectsADeletedRoleInsteadOfSilentlyIgnoringTheGrant() throws Exception {
        assertThat(roles.roleExists("auditor")).isTrue();
        // Model deletion between the service's existence check and its INSERT.
        execute("DELETE FROM explorer_role_definition WHERE role_key = 'auditor'");
        assertThatThrownBy(() -> roles.addRole(player, "auditor", null)).isInstanceOf(org.apache.ibatis.exceptions.PersistenceException.class);
        assertThat(service.listRoles(player)).isEmpty();
    }

    @Test
    void scheduledSyncGrantsAndRevokesCustomRolesAndAccountRelationships() throws Exception {
        seedRelationships();
        inheritedStaff();
        sync.run();
        assertThat(effectiveGroupPermissions()).isEqualTo(1);
        assertThat(accountReads()).isEqualTo(1);
        // Only the web read relationship is granted; spending/API-key authority is separate.
        assertThat(count("SELECT COUNT(*) FROM account_read_access_api WHERE subject_uuid_bin = uuid_to_bin(?)", player)).isZero();
        when(user.getInheritedGroups(options)).thenReturn(List.of());
        sync.run();
        assertThat(effectiveGroupPermissions()).isZero();
        assertThat(accountReads()).isZero();
    }

    @Test
    void selfSyncRefreshesExpiredLeasesAndPreservesManualRolesOnLpRevocation() throws Exception {
        seedRelationships();
        inheritedStaff();
        service.selfSync(player);
        execute("UPDATE explorer_group_member SET synced_at = NOW() - INTERVAL 2 HOUR");
        execute("UPDATE explorer_lp_group_member SET synced_at = NOW() - INTERVAL 2 HOUR");
        assertThat(effectiveGroupPermissions()).isZero();
        assertThat(accountReads()).isZero();
        service.selfSync(player);
        assertThat(effectiveGroupPermissions()).isEqualTo(1);
        assertThat(accountReads()).isEqualTo(1);
        execute("INSERT INTO explorer_group_member (group_id, player_uuid_bin, source) VALUES (1, uuid_to_bin(?), 'manual')", player);
        when(user.getInheritedGroups(options)).thenReturn(List.of());
        service.selfSync(player);
        assertThat(effectiveGroupPermissions()).isEqualTo(1);
        assertThat(accountReads()).isZero();
        assertThat(groups.listLuckpermsMemberUuids(1)).isEmpty();
    }

    @Test
    void failedSelfSyncDoesNotPublishEarlierResolvedGrants() throws Exception {
        seedRelationships();
        inheritedStaff();
        execute("INSERT INTO explorer_group (group_id, name, luckperms_node) VALUES (2, 'Broken', 'web.broken')");
        when(user.getCachedData().getPermissionData(options).checkPermission("web.broken")).thenThrow(new IllegalStateException("LP unavailable"));
        assertThatThrownBy(() -> service.selfSync(player)).hasMessageContaining("sync.failed");
        assertThat(count("SELECT COUNT(*) FROM explorer_group_member")).isZero();
        assertThat(accountReads()).isZero();
        verify(lp.getUserManager()).cleanupUser(user);
    }

    @Test
    void permissionNodeDenyRevokesSyncedRole() throws Exception {
        seedRelationships();
        execute("UPDATE explorer_group SET luckperms_node = 'web.audit' WHERE group_id = 1");
        when(user.getCachedData().getPermissionData(options).checkPermission("web.audit")).thenReturn(Tristate.TRUE);
        service.selfSync(player);
        assertThat(effectiveGroupPermissions()).isEqualTo(1);
        when(user.getCachedData().getPermissionData(options).checkPermission("web.audit")).thenReturn(Tristate.FALSE);
        service.selfSync(player);
        assertThat(effectiveGroupPermissions()).isZero();
    }

    private void seedRelationships() throws Exception {
        execute("INSERT INTO explorer_group (group_id, name, luckperms_node) VALUES (1, 'Auditors', 'group.staff')");
        execute("INSERT INTO explorer_group_capability VALUES (1, 'auditor')");
        execute("INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES (900, 'GOVERNMENT', uuid_to_bin(?), 'Department')", UUID.randomUUID());
        execute("INSERT INTO account_group_access (account_id, lp_group, level, added_by_uuid_bin) VALUES (900, 'staff', 'VIEWER', uuid_to_bin(?))", player);
    }
    private void inheritedStaff() {
        Group staff = mock(Group.class);
        when(staff.getName()).thenReturn("staff");
        when(user.getInheritedGroups(options)).thenReturn(List.of(staff));
    }
    private int effectiveGroupPermissions() throws Exception {
        return count("SELECT COUNT(DISTINCT p.permission) FROM explorer_group_member m JOIN explorer_group g USING (group_id) JOIN explorer_group_capability c USING (group_id) JOIN explorer_role_permission p ON p.role_key = c.capability WHERE m.player_uuid_bin = uuid_to_bin(?) AND (m.source = 'manual' OR (g.luckperms_node IS NOT NULL AND m.synced_at > NOW() - INTERVAL 1 HOUR))", player);
    }
    private int accountReads() throws Exception {
        return count("SELECT COUNT(*) FROM account_read_access_web WHERE subject_uuid_bin = uuid_to_bin(?)", player);
    }
    private void execute(String query, Object... args) throws Exception {
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i].toString());
            ps.executeUpdate();
        }
        sqlSession.clearCache(); // DB writes here model another process (Explorer).
    }
    private int count(String query, Object... args) throws Exception {
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i].toString());
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
