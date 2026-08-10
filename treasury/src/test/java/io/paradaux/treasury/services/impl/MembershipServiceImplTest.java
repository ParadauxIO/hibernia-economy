package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.mappers.GroupMembershipMapper;
import io.paradaux.treasury.mappers.MembershipMapper;
import io.paradaux.treasury.model.economy.AccountMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MembershipServiceImplTest {

    @Mock MembershipMapper membershipMapper;
    @Mock GroupMembershipMapper groupMappers;

    private MembershipServiceImpl svc;

    @BeforeEach
    void setUp() {
        svc = new MembershipServiceImpl(membershipMapper, groupMappers);
    }

    // ---- Direct UUID checks ----

    @Test
    void isMember_directHit() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isMember(1, u)).thenReturn(1);
        assertThat(svc.isMember(1, u)).isTrue();
        // Group lookup short-circuits when LuckPerms is absent (no groups).
        verifyNoInteractions(groupMappers);
    }

    @Test
    void isMember_noDirect_andNoLuckPerms_returnsFalse() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isMember(1, u)).thenReturn(0);
        assertThat(svc.isMember(1, u)).isFalse();
    }

    @Test
    void isAuthorizer_directHit() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isAuthorizer(1, u)).thenReturn(1);
        assertThat(svc.isAuthorizer(1, u)).isTrue();
    }

    @Test
    void isAuthorizer_noDirect_andNoLuckPerms_returnsFalse() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isAuthorizer(1, u)).thenReturn(0);
        assertThat(svc.isAuthorizer(1, u)).isFalse();
    }

    // ---- Spend authorization (per-account member/authorizer gate) ----

    @Test
    void canSpend_trueWhenMember_doesNotCheckAuthorizer() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isMember(1, u)).thenReturn(1);
        assertThat(svc.canSpend(1, u)).isTrue();
        // Member short-circuits — authorizer lookup is never consulted.
        verify(membershipMapper, never()).isAuthorizer(anyInt(), any());
    }

    @Test
    void canSpend_trueWhenAuthorizerOnly() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isMember(1, u)).thenReturn(0);
        when(membershipMapper.isAuthorizer(1, u)).thenReturn(1);
        assertThat(svc.canSpend(1, u)).isTrue();
    }

    @Test
    void canSpend_falseWhenNeither() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isMember(1, u)).thenReturn(0);
        when(membershipMapper.isAuthorizer(1, u)).thenReturn(0);
        assertThat(svc.canSpend(1, u)).isFalse();
    }

    // ---- UUID CRUD ----

    @Test
    void addMember_delegates() {
        UUID m = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        svc.addMember(1, m, by);
        verify(membershipMapper).addMember(1, m, by);
    }

    @Test
    void removeMember_delegates_noCascade() {
        UUID m = UUID.randomUUID();
        svc.removeMember(1, m);
        // One consolidated row — removeMember (level >= MEMBER) covers an authorizer.
        verify(membershipMapper).removeMember(1, m);
        verify(membershipMapper, never()).removeAuthorizer(anyInt(), any());
    }

    @Test
    void getMembers_delegates() {
        when(membershipMapper.getMembers(1)).thenReturn(List.of(new AccountMember()));
        assertThat(svc.getMembers(1)).hasSize(1);
    }

    @Test
    void addAuthorizer_requiresMemberFirst() {
        UUID a = UUID.randomUUID();
        when(membershipMapper.isMember(1, a)).thenReturn(0);
        assertThatThrownBy(() -> svc.addAuthorizer(1, a, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a member");
        verify(membershipMapper, never()).promoteToAuthorizer(anyInt(), any());
    }

    @Test
    void addAuthorizer_memberPresent_promotes() {
        UUID a = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        when(membershipMapper.isMember(1, a)).thenReturn(1);
        svc.addAuthorizer(1, a, by);
        verify(membershipMapper).promoteToAuthorizer(1, a);
    }

    @Test
    void removeAuthorizer_delegates() {
        UUID a = UUID.randomUUID();
        svc.removeAuthorizer(1, a);
        verify(membershipMapper).removeAuthorizer(1, a);
    }

    @Test
    void getAuthorizers_delegates() {
        when(membershipMapper.getAuthorizers(1)).thenReturn(List.of(new AccountMember()));
        assertThat(svc.getAuthorizers(1)).hasSize(1);
    }

    // ---- Group CRUD ----

    @Test
    void addGroupMember_delegates() {
        UUID by = UUID.randomUUID();
        svc.addGroupMember(1, "vip", by);
        verify(groupMappers).addGroupMember(1, "vip", by);
    }

    @Test
    void removeGroupMember_delegates_noCascade() {
        svc.removeGroupMember(1, "vip");
        verify(groupMappers).removeGroupMember(1, "vip");
        verify(groupMappers, never()).removeGroupAuthorizer(anyInt(), any());
    }

    @Test
    void getGroupMembers_delegates() {
        when(groupMappers.getGroupMembers(1)).thenReturn(List.of("vip", "staff"));
        assertThat(svc.getGroupMembers(1)).containsExactly("vip", "staff");
    }

    @Test
    void addGroupAuthorizer_requiresGroupMemberFirst() {
        when(groupMappers.getGroupMembers(1)).thenReturn(List.of("staff"));
        assertThatThrownBy(() -> svc.addGroupAuthorizer(1, "vip", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a group member");
    }

    @Test
    void addGroupAuthorizer_groupPresent_promotes() {
        when(groupMappers.getGroupMembers(1)).thenReturn(List.of("vip"));
        UUID by = UUID.randomUUID();
        svc.addGroupAuthorizer(1, "vip", by);
        verify(groupMappers).promoteGroupToAuthorizer(1, "vip");
    }

    @Test
    void removeGroupAuthorizer_delegates() {
        svc.removeGroupAuthorizer(1, "vip");
        verify(groupMappers).removeGroupAuthorizer(1, "vip");
    }

    @Test
    void getGroupAuthorizers_delegates() {
        when(groupMappers.getGroupAuthorizers(1)).thenReturn(List.of("staff"));
        assertThat(svc.getGroupAuthorizers(1)).containsExactly("staff");
    }

    // ---- Viewer tier (read-only, PAR-237) ----

    @Test
    void isViewer_directHit() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isViewer(1, u)).thenReturn(1);
        assertThat(svc.isViewer(1, u)).isTrue();
        verifyNoInteractions(groupMappers);
    }

    @Test
    void isViewer_noDirect_andNoLuckPerms_returnsFalse() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isViewer(1, u)).thenReturn(0);
        assertThat(svc.isViewer(1, u)).isFalse();
    }

    @Test
    void canView_trueWhenMember_doesNotCheckViewer() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isMember(1, u)).thenReturn(1);
        assertThat(svc.canView(1, u)).isTrue();
        verify(membershipMapper, never()).isViewer(anyInt(), any());
    }

    @Test
    void canView_trueWhenViewerOnly() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isMember(1, u)).thenReturn(0);
        when(membershipMapper.isViewer(1, u)).thenReturn(1);
        assertThat(svc.canView(1, u)).isTrue();
    }

    @Test
    void canView_falseWhenNeither() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isMember(1, u)).thenReturn(0);
        when(membershipMapper.isViewer(1, u)).thenReturn(0);
        assertThat(svc.canView(1, u)).isFalse();
    }

    @Test
    void addViewer_delegates() {
        UUID v = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        svc.addViewer(1, v, by);
        verify(membershipMapper).addViewer(1, v, by);
    }

    @Test
    void removeViewer_delegates_withNoCascade() {
        UUID v = UUID.randomUUID();
        svc.removeViewer(1, v);
        verify(membershipMapper).removeViewer(1, v);
        // A viewer is standalone — no authorizer/member cascade like removeMember.
        verify(membershipMapper, never()).removeAuthorizer(anyInt(), any());
    }

    @Test
    void getViewers_delegates() {
        when(membershipMapper.getViewers(1)).thenReturn(List.of(new AccountMember()));
        assertThat(svc.getViewers(1)).hasSize(1);
    }

    @Test
    void addGroupViewer_delegates() {
        UUID by = UUID.randomUUID();
        svc.addGroupViewer(1, "health-sec", by);
        verify(groupMappers).addGroupViewer(1, "health-sec", by);
    }

    @Test
    void removeGroupViewer_delegates() {
        svc.removeGroupViewer(1, "health-sec");
        verify(groupMappers).removeGroupViewer(1, "health-sec");
    }

    @Test
    void getGroupViewers_delegates() {
        when(groupMappers.getGroupViewers(1)).thenReturn(List.of("health-sec"));
        assertThat(svc.getGroupViewers(1)).containsExactly("health-sec");
    }

    // ---- LuckPerms-aware paths (uses mocked LuckPerms) ----

    @org.junit.jupiter.api.Nested
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    class WithLuckPerms {
        @Mock net.luckperms.api.LuckPerms luckPerms;
        @Mock net.luckperms.api.model.user.UserManager userManager;
        @Mock net.luckperms.api.model.user.User user;
        @Mock net.luckperms.api.query.QueryOptions queryOptions;
        @Mock net.luckperms.api.model.group.Group groupVip;

        @org.junit.jupiter.api.BeforeEach
        void wireLuckPerms() {
            org.mockito.MockitoAnnotations.openMocks(this);
            org.mockito.Mockito.lenient().when(luckPerms.getUserManager()).thenReturn(userManager);
            org.mockito.Mockito.lenient().when(user.getQueryOptions()).thenReturn(queryOptions);
            org.mockito.Mockito.lenient().when(groupVip.getName()).thenReturn("vip");
            svc.setLuckPerms(luckPerms);
        }

        @Test
        void isMember_directMiss_butGroupMatches_returnsTrue() {
            UUID u = UUID.randomUUID();
            org.mockito.Mockito.when(membershipMapper.isMember(1, u)).thenReturn(0);
            org.mockito.Mockito.when(userManager.getUser(u)).thenReturn(user);
            org.mockito.Mockito.when(user.getInheritedGroups(queryOptions)).thenReturn(Set.of(groupVip));
            org.mockito.Mockito.when(groupMappers.isAnyGroupMember(
                    org.mockito.ArgumentMatchers.eq(1),
                    org.mockito.ArgumentMatchers.anySet())).thenReturn(1);

            assertThat(svc.isMember(1, u)).isTrue();
        }

        @Test
        void isMember_unknownUser_returnsFalse() {
            UUID u = UUID.randomUUID();
            org.mockito.Mockito.when(membershipMapper.isMember(1, u)).thenReturn(0);
            org.mockito.Mockito.when(userManager.getUser(u)).thenReturn(null);

            assertThat(svc.isMember(1, u)).isFalse();
        }

        @Test
        void isAuthorizer_directMiss_butGroupAuthorizes_returnsTrue() {
            UUID u = UUID.randomUUID();
            org.mockito.Mockito.when(membershipMapper.isAuthorizer(1, u)).thenReturn(0);
            org.mockito.Mockito.when(userManager.getUser(u)).thenReturn(user);
            org.mockito.Mockito.when(user.getInheritedGroups(queryOptions)).thenReturn(Set.of(groupVip));
            org.mockito.Mockito.when(groupMappers.isAnyGroupAuthorizer(
                    org.mockito.ArgumentMatchers.eq(1),
                    org.mockito.ArgumentMatchers.anySet())).thenReturn(1);

            assertThat(svc.isAuthorizer(1, u)).isTrue();
        }

        @Test
        void isViewer_directMiss_butGroupMatches_returnsTrue() {
            UUID u = UUID.randomUUID();
            org.mockito.Mockito.when(membershipMapper.isViewer(1, u)).thenReturn(0);
            org.mockito.Mockito.when(userManager.getUser(u)).thenReturn(user);
            org.mockito.Mockito.when(user.getInheritedGroups(queryOptions)).thenReturn(Set.of(groupVip));
            org.mockito.Mockito.when(groupMappers.isAnyGroupViewer(
                    org.mockito.ArgumentMatchers.eq(1),
                    org.mockito.ArgumentMatchers.anySet())).thenReturn(1);

            assertThat(svc.isViewer(1, u)).isTrue();
        }

        @Test
        void isMember_groupExistsButNoneAreMembers_returnsFalse() {
            UUID u = UUID.randomUUID();
            org.mockito.Mockito.when(membershipMapper.isMember(1, u)).thenReturn(0);
            org.mockito.Mockito.when(userManager.getUser(u)).thenReturn(user);
            org.mockito.Mockito.when(user.getInheritedGroups(queryOptions)).thenReturn(Set.of(groupVip));
            org.mockito.Mockito.when(groupMappers.isAnyGroupMember(
                    org.mockito.ArgumentMatchers.eq(1),
                    org.mockito.ArgumentMatchers.anySet())).thenReturn(0);

            assertThat(svc.isMember(1, u)).isFalse();
        }
    }
}
