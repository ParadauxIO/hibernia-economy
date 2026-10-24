package io.paradaux.business.services.impl;

import io.paradaux.business.mappers.*;
import io.paradaux.business.model.*;
import io.paradaux.business.services.FirmService;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.AccountMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FirmMembershipRecoveryTest {
    private final TreasuryApi treasury = mock(TreasuryApi.class);
    private final FirmAccountsMapper links = mock(FirmAccountsMapper.class);
    private final FirmMapper firms = mock(FirmMapper.class);
    private final FirmStaffMapper staff = mock(FirmStaffMapper.class);
    private final FirmRoleMapper roles = mock(FirmRoleMapper.class);
    private final FirmService firmService = mock(FirmService.class);
    private final UUID owner = UUID.randomUUID();
    private final UUID hired = UUID.randomUUID();
    private final UUID fired = UUID.randomUUID();
    private final UUID demoted = UUID.randomUUID();
    private final Set<UUID> members = new HashSet<>();
    private final Set<UUID> authorizers = new HashSet<>();
    private String failedOperation;
    private final AtomicBoolean failOnce = new AtomicBoolean();
    private int writes;
    private Firm firm;

    private FirmAccountServiceImpl service() {
        return new FirmAccountServiceImpl(treasury, links, firms, staff, roles, firmService);
    }

    @BeforeEach
    void setup() {
        firm = new Firm();
        firm.setFirmId(1);
        firm.setProprietorUuid(owner.toString());
        when(firms.getFirmById(1)).thenReturn(firm);
        when(links.isFirmAccount(1, 99)).thenReturn(true);
        when(links.listActiveAccountLinks()).thenReturn(List.of(link(99)));
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(List.of(employee(hired, "Admin"), employee(demoted, "Finance")));
        when(roles.getFirmRolePermissions(1, "Admin")).thenReturn(List.of(new FirmRolePermission(1, "Admin", RolePermission.ADMIN)));
        when(roles.getFirmRolePermissions(1, "Finance")).thenReturn(List.of(new FirmRolePermission(1, "Finance", RolePermission.FINANCIAL)));
        members.addAll(Set.of(owner, fired, demoted));
        authorizers.addAll(members);
        when(treasury.getMembers(99)).thenAnswer(call -> rows(members));
        when(treasury.getAuthorizers(99)).thenAnswer(call -> rows(authorizers));
        // Model Treasury's committed state, including removeMember's authorizer
        // cascade. Each injected error occurs AFTER a write, as with a lost reply.
        doAnswer(call -> {
            members.add(call.getArgument(1));
            afterWrite("addMember");
            return null;
        }).when(treasury).addMember(eq(99), any(), any());
        doAnswer(call -> {
            UUID id = call.getArgument(1);
            members.remove(id);
            authorizers.remove(id);
            afterWrite("removeMember");
            return null;
        }).when(treasury).removeMember(eq(99), any());
        doAnswer(call -> {
            UUID id = call.getArgument(1);
            assertThat(members).contains(id);
            authorizers.add(id);
            afterWrite("addAuthorizer");
            return null;
        }).when(treasury).addAuthorizer(eq(99), any(), any());
        doAnswer(call -> {
            authorizers.remove(call.getArgument(1));
            afterWrite("removeAuthorizer");
            return null;
        }).when(treasury).removeAuthorizer(eq(99), any());
    }

    private void afterWrite(String operation) {
        writes++;
        if (operation.equals(failedOperation) && failOnce.getAndSet(false)) {
            throw new IllegalStateException("Treasury unavailable after " + operation);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"addMember", "removeMember", "addAuthorizer", "removeAuthorizer"})
    void restartRecoversEveryPartialWriteAndRepeatedPassDoesNotMutate(String operation) {
        failedOperation = operation;
        failOnce.set(true);
        assertThatThrownBy(() -> service().syncAccountMembers(1, 99)).isInstanceOf(IllegalStateException.class);

        // New service: no in-memory failure marker survives the restart.
        FirmAccountServiceImpl restarted = service();
        restarted.reconcileLinkedAccounts();
        assertThat(members).containsExactlyInAnyOrder(owner, hired, demoted);
        assertThat(authorizers).containsExactlyInAnyOrder(owner, hired);
        int completedWrites = writes;
        restarted.reconcileLinkedAccounts();
        assertThat(writes).isEqualTo(completedWrites);
    }

    @Test
    void retryReadsCurrentRolesInsteadOfReplayingFailedGrants() {
        failedOperation = "addAuthorizer";
        failOnce.set(true);
        assertThatThrownBy(() -> service().syncAccountMembers(1, 99)).isInstanceOf(IllegalStateException.class);
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(List.of());
        service().reconcileLinkedAccounts();
        assertThat(members).containsExactly(owner);
        assertThat(authorizers).containsExactly(owner);
    }

    @Test
    void persistentFailureDoesNotStarveOtherAccountsAndIsRetriedNextPass() {
        when(links.listActiveAccountLinks()).thenReturn(List.of(link(98), link(99)));
        when(links.isFirmAccount(1, 98)).thenReturn(true);
        when(treasury.getMembers(98)).thenThrow(new IllegalStateException("offline"));
        FirmAccountServiceImpl svc = service();
        svc.reconcileLinkedAccounts();
        svc.reconcileLinkedAccounts();
        verify(treasury, times(2)).getMembers(98);
        assertThat(members).containsExactlyInAnyOrder(owner, hired, demoted);
        assertThat(authorizers).containsExactlyInAnyOrder(owner, hired);
    }

    @Test
    void removedLinkIsNotRecreatedFromWorklistSnapshot() {
        when(links.isFirmAccount(1, 99)).thenReturn(false);
        service().reconcileLinkedAccounts();
        verifyNoInteractions(treasury);
    }

    @Test
    void archivedFirmIsSkipped() {
        firm.setArchived(true);
        service().reconcileLinkedAccounts();
        verifyNoInteractions(treasury);
    }

    @Test
    void missingFirmIsSkipped() {
        when(firms.getFirmById(1)).thenReturn(null);
        service().reconcileLinkedAccounts();
        verifyNoInteractions(treasury);
    }

    private List<AccountMember> rows(Set<UUID> ids) {
        return ids.stream().map(id -> new AccountMember(99, id, owner, Instant.EPOCH)).toList();
    }

    private FirmAccount link(int accountId) {
        return new FirmAccount(1, accountId, null);
    }

    private FirmEmployee employee(UUID id, String role) {
        FirmEmployee employee = new FirmEmployee();
        employee.setPlayerUuid(id.toString());
        employee.setRoleName(role);
        return employee;
    }
}
