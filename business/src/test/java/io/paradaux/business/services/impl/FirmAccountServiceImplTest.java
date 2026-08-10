package io.paradaux.business.services.impl;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.business.mappers.FirmAccountsMapper;
import io.paradaux.business.mappers.FirmMapper;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.mappers.FirmStaffMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmAccount;
import io.paradaux.business.model.FirmEmployee;
import io.paradaux.business.model.FirmRolePermission;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmService;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;
import io.paradaux.treasury.model.economy.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmAccountServiceImplTest {

    @Mock TreasuryApi treasury;
    @Mock FirmAccountsMapper firmAccounts;
    @Mock FirmMapper firms;
    @Mock FirmStaffMapper staff;
    @Mock FirmRoleMapper roles;
    @Mock FirmService firmService;

    private FirmAccountServiceImpl svc;

    private final UUID actor = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        svc = new FirmAccountServiceImpl(treasury, firmAccounts, firms, staff, roles, firmService);
    }

    private Firm firm(int id) {
        Firm f = new Firm();
        f.setFirmId(id);
        f.setProprietorUuid(owner.toString());
        f.setDisplayName("Acme");
        return f;
    }

    // ---------- createAccount ----------

    @Test
    void createAccount_unknownFirm_throws() {
        when(firms.getFirmById(1)).thenReturn(null);
        assertThatThrownBy(() -> svc.createAccount(1, "Sub", actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void createAccount_rejectsMiniMessageInName() {
        assertThatThrownBy(() -> svc.createAccount(1, "<click>x</click>", actor))
                .isInstanceOf(BadCommandException.class);
        // Validation runs before the firm lookup.
        org.mockito.Mockito.verifyNoInteractions(firms);
    }

    @Test
    void createAccount_requiresProprietor() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(false);
        assertThatThrownBy(() -> svc.createAccount(1, "Sub", actor))
                .isInstanceOf(NoPermissionException.class);
    }

    @Test
    void createAccount_createsAndSyncs() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);

        Account acc = new Account();
        acc.setAccountId(99);
        when(treasury.createAccount(eq(AccountType.BUSINESS), eq(actor), eq("Sub"))).thenReturn(acc);
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true); // for syncAccountMembers below

        // staff list empty so sync is just owner
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(List.of());
        when(treasury.getMembers(99)).thenReturn(List.of());
        when(treasury.getAuthorizers(99)).thenReturn(List.of());

        Account result = svc.createAccount(1, "Sub", actor);
        assertThat(result).isSameAs(acc);

        verify(firmAccounts).insertFirmAccount(1, 99);
        verify(treasury).addMember(99, owner, owner);
        verify(treasury).addAuthorizer(99, owner, owner);
    }

    // ---------- listAccounts ----------

    @Test
    void listAccounts_resolvesEachAccount() {
        when(firmAccounts.listAccountsByFirm(1)).thenReturn(List.of(
                new FirmAccount(1, 10, null),
                new FirmAccount(1, 11, null)));
        Account a1 = new Account(); a1.setAccountId(10);
        Account a2 = new Account(); a2.setAccountId(11);
        // One batch read instead of one getAccountById per account (ADT-36).
        when(treasury.getAccountsByIds(List.of(10, 11))).thenReturn(Map.of(10, a1, 11, a2));

        assertThat(svc.listAccounts(1)).containsExactly(a1, a2);
    }

    // ---------- setDefaultAccount ----------

    @Test
    void setDefaultAccount_unknownFirm_throws() {
        when(firms.getFirmById(1)).thenReturn(null);
        assertThatThrownBy(() -> svc.setDefaultAccount(1, 10, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void setDefaultAccount_requiresProprietor() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(false);
        assertThatThrownBy(() -> svc.setDefaultAccount(1, 10, actor))
                .isInstanceOf(NoPermissionException.class);
    }

    @Test
    void setDefaultAccount_validatesOwnership() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(false);
        assertThatThrownBy(() -> svc.setDefaultAccount(1, 10, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void setDefaultAccount_persistsViaUpdateFirm() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);

        svc.setDefaultAccount(1, 10, actor);

        ArgumentCaptor<Firm> cap = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(cap.capture());
        assertThat(cap.getValue().getFirmId()).isEqualTo(1);
        assertThat(cap.getValue().getDefaultAccountId()).isEqualTo(10);
    }

    // ---------- archiveAccount ----------

    @Test
    void archiveAccount_blocksDefaultAccount() {
        Firm f = firm(1);
        f.setDefaultAccountId(10);
        when(firms.getFirmById(1)).thenReturn(f);
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);
        assertThatThrownBy(() -> svc.archiveAccount(1, 10, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void archiveAccount_archivesAndUnregisters() {
        Firm f = firm(1);
        f.setDefaultAccountId(99);
        when(firms.getFirmById(1)).thenReturn(f);
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);

        svc.archiveAccount(1, 10, actor);

        verify(treasury).archiveAccount(10);
        verify(firmAccounts).removeFirmAccount(1, 10);
    }

    @Test
    void archiveAccount_unknownFirm_throws() {
        when(firms.getFirmById(1)).thenReturn(null);
        assertThatThrownBy(() -> svc.archiveAccount(1, 10, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void archiveAccount_notProprietor_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(false);
        assertThatThrownBy(() -> svc.archiveAccount(1, 10, actor))
                .isInstanceOf(NoPermissionException.class);
    }

    @Test
    void archiveAccount_unknownAccount_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(false);
        assertThatThrownBy(() -> svc.archiveAccount(1, 10, actor))
                .isInstanceOf(BadCommandException.class);
    }

    // ---------- syncAccountMembers ----------

    @Test
    void syncAccountMembers_unknownFirm_throws() {
        when(firms.getFirmById(1)).thenReturn(null);
        assertThatThrownBy(() -> svc.syncAccountMembers(1, 99))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void syncAccountMembers_unknownAccount_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(false);
        assertThatThrownBy(() -> svc.syncAccountMembers(1, 99))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void syncAccountMembers_addsAdminAsMemberAndAuthorizer_addsFinancialAsMemberOnly() {
        UUID adminUuid = UUID.randomUUID();
        UUID financeUuid = UUID.randomUUID();
        UUID otherUuid = UUID.randomUUID();

        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(List.of(
                emp(adminUuid, "Admin"),
                emp(financeUuid, "Finance"),
                emp(otherUuid, "Default")
        ));
        when(roles.getFirmRolePermissions(1, "Admin")).thenReturn(List.of(
                new FirmRolePermission(1, "Admin", RolePermission.ADMIN)));
        when(roles.getFirmRolePermissions(1, "Finance")).thenReturn(List.of(
                new FirmRolePermission(1, "Finance", RolePermission.FINANCIAL)));
        when(roles.getFirmRolePermissions(1, "Default")).thenReturn(List.of(
                new FirmRolePermission(1, "Default", RolePermission.DEFAULT)));
        when(treasury.getMembers(99)).thenReturn(List.of());
        when(treasury.getAuthorizers(99)).thenReturn(List.of());

        svc.syncAccountMembers(1, 99);

        verify(treasury).addMember(99, owner, owner);
        verify(treasury).addAuthorizer(99, owner, owner);
        verify(treasury).addMember(99, adminUuid, owner);
        verify(treasury).addAuthorizer(99, adminUuid, owner);
        verify(treasury).addMember(99, financeUuid, owner);
        verify(treasury, never()).addAuthorizer(99, financeUuid, owner);
        verify(treasury, never()).addMember(99, otherUuid, owner);
        verify(treasury, never()).addAuthorizer(99, otherUuid, owner);
    }

    @Test
    void syncAccountMembers_removesStaleMembersAndAuthorizers() {
        UUID stale = UUID.randomUUID();

        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(List.of()); // no employees, only owner
        when(treasury.getMembers(99)).thenReturn(List.of(
                new AccountMember(99, owner, owner, Instant.EPOCH),
                new AccountMember(99, stale, owner, Instant.EPOCH)));
        when(treasury.getAuthorizers(99)).thenReturn(List.of(
                new AccountMember(99, owner, owner, Instant.EPOCH),
                new AccountMember(99, stale, owner, Instant.EPOCH)));

        svc.syncAccountMembers(1, 99);

        verify(treasury).removeMember(99, stale);
        verify(treasury).removeAuthorizer(99, stale);
        verify(treasury, never()).removeMember(99, owner);
        verify(treasury, never()).removeAuthorizer(99, owner);
    }

    @Test
    void reassignAccountsToNewProprietor_reassignsOwnerAndResyncsEachAccount() {
        UUID newProprietor = UUID.randomUUID();

        // Simulate the post-updateProprietor state: getFirmById returns the new
        // proprietor, so the resync derives them as owner (member + authorizer).
        Firm f = firm(1);
        f.setProprietorUuid(newProprietor.toString());
        when(firms.getFirmById(1)).thenReturn(f);
        when(firmAccounts.listAccountsByFirm(1)).thenReturn(List.of(
                new FirmAccount(1, 10, null),
                new FirmAccount(1, 11, null)));
        when(firmAccounts.isFirmAccount(eq(1), anyInt())).thenReturn(true);
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(List.of());
        when(treasury.getMembers(anyInt())).thenReturn(List.of());
        when(treasury.getAuthorizers(anyInt())).thenReturn(List.of());

        svc.reassignAccountsToNewProprietor(1, newProprietor);

        // Owner reassigned on every live firm account...
        verify(treasury).reassignOwner(10, newProprietor);
        verify(treasury).reassignOwner(11, newProprietor);
        // ...and the new proprietor synced in as member + authorizer on each.
        verify(treasury).addMember(10, newProprietor, newProprietor);
        verify(treasury).addAuthorizer(10, newProprietor, newProprietor);
        verify(treasury).addMember(11, newProprietor, newProprietor);
        verify(treasury).addAuthorizer(11, newProprietor, newProprietor);
    }

    private FirmEmployee emp(UUID id, String role) {
        return new FirmEmployee(1, id.toString(), role, null, null, null, null, true);
    }

    // ---------- syncAllFirmAccounts ----------

    @Test
    void syncAllFirmAccounts_callsSyncForEach() {
        when(firmAccounts.listAccountsByFirm(1)).thenReturn(List.of(
                new FirmAccount(1, 10, null),
                new FirmAccount(1, 11, null)));
        // syncAccountMembers will require firm + isFirmAccount
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 11)).thenReturn(true);
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(List.of());
        when(treasury.getMembers(anyInt())).thenReturn(List.of());
        when(treasury.getAuthorizers(anyInt())).thenReturn(List.of());

        svc.syncAllFirmAccounts(1);

        verify(treasury).addMember(10, owner, owner);
        verify(treasury).addMember(11, owner, owner);
    }

    // ---------- add/remove member/authorizer ----------

    @Test
    void addMemberToAccount_unknownFirm_throws() {
        when(firms.getFirmById(1)).thenReturn(null);
        assertThatThrownBy(() -> svc.addMemberToAccount(1, 10, UUID.randomUUID(), actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void addMemberToAccount_notProprietor_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(false);
        assertThatThrownBy(() -> svc.addMemberToAccount(1, 10, UUID.randomUUID(), actor))
                .isInstanceOf(NoPermissionException.class);
    }

    @Test
    void addMemberToAccount_succeeds() {
        UUID member = UUID.randomUUID();
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);

        svc.addMemberToAccount(1, 10, member, actor);
        verify(treasury).addMember(10, member, actor);
        // PAR-77: the manual write is followed by a role-derived reconcile.
        verify(treasury).getMembers(10);
        verify(treasury).getAuthorizers(10);
    }

    @Test
    void removeMemberFromAccount_blocksRemovingProprietor() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);
        assertThatThrownBy(() -> svc.removeMemberFromAccount(1, 10, owner, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void removeMemberFromAccount_succeeds() {
        UUID toRemove = UUID.randomUUID();
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);

        svc.removeMemberFromAccount(1, 10, toRemove, actor);
        verify(treasury).removeMember(10, toRemove);
        // PAR-77: the manual write is followed by a role-derived reconcile.
        verify(treasury).getMembers(10);
        verify(treasury).getAuthorizers(10);
    }

    @Test
    void addAuthorizerToAccount_succeeds() {
        UUID auth = UUID.randomUUID();
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);

        svc.addAuthorizerToAccount(1, 10, auth, actor);
        verify(treasury).addAuthorizer(10, auth, actor);
        // PAR-77: the manual write is followed by a role-derived reconcile.
        verify(treasury).getMembers(10);
        verify(treasury).getAuthorizers(10);
    }

    @Test
    void removeAuthorizerFromAccount_blocksRemovingProprietor() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);
        assertThatThrownBy(() -> svc.removeAuthorizerFromAccount(1, 10, owner, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void removeAuthorizerFromAccount_succeeds() {
        UUID toRemove = UUID.randomUUID();
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(firmService.isProprietor(1, actor)).thenReturn(true);
        when(firmAccounts.isFirmAccount(1, 10)).thenReturn(true);

        svc.removeAuthorizerFromAccount(1, 10, toRemove, actor);
        verify(treasury).removeAuthorizer(10, toRemove);
        // PAR-77: the manual write is followed by a role-derived reconcile.
        verify(treasury).getMembers(10);
        verify(treasury).getAuthorizers(10);
    }

    // ---------- getters ----------

    @Test
    void getAccountMembers_validatesAccount() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(false);
        assertThatThrownBy(() -> svc.getAccountMembers(1, 99))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void getAccountMembers_returnsList() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        AccountMember m = new AccountMember(99, owner, owner, Instant.EPOCH);
        when(treasury.getMembers(99)).thenReturn(List.of(m));
        assertThat(svc.getAccountMembers(1, 99)).containsExactly(m);
    }

    @Test
    void getAccountAuthorizers_validatesAccount() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(false);
        assertThatThrownBy(() -> svc.getAccountAuthorizers(1, 99))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void getAccountAuthorizers_returnsList() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        AccountMember m = new AccountMember(99, owner, owner, Instant.EPOCH);
        when(treasury.getAuthorizers(99)).thenReturn(List.of(m));
        assertThat(svc.getAccountAuthorizers(1, 99)).containsExactly(m);
    }

    @Test
    void getFirmIdByAccountId_delegates() {
        when(firmAccounts.getFirmIdByAccountId(99)).thenReturn(7);
        assertThat(svc.getFirmIdByAccountId(99)).isEqualTo(7);
    }
}
