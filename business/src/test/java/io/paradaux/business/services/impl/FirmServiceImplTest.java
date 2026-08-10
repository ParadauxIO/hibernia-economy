package io.paradaux.business.services.impl;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.business.mappers.FirmAccountsMapper;
import io.paradaux.business.mappers.FirmMapper;
import io.paradaux.business.mappers.FirmRequestMapper;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmAccount;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.model.config.FirmConfiguration;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmAreaShopService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.TransferRequest;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmServiceImplTest {

    @Mock FirmMapper firms;
    @Mock TreasuryApi treasury;
    @Mock FirmAccountsMapper accounts;
    @Mock FirmRoleMapper roles;
    @Mock FirmRequestMapper requests;
    @Mock FirmStaffService staffService;
    @Mock FirmAccountService firmAccountService;
    @Mock FirmConfiguration firmConfig;
    @Mock FirmAreaShopService areas;

    private FirmServiceImpl svc;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        svc = new FirmServiceImpl(firms, treasury, accounts, roles, requests, () -> staffService, () -> firmAccountService, firmConfig, areas);
        bukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
        // Default to the production limit of 3 (lenient — not every test reaches the check).
        org.mockito.Mockito.lenient().when(firmConfig.hasOwnedFirmLimit()).thenReturn(true);
        org.mockito.Mockito.lenient().when(firmConfig.getOwnedFirmLimit()).thenReturn(3);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private void stubOfflinePlayer(UUID id, String name) {
        OfflinePlayer op = org.mockito.Mockito.mock(OfflinePlayer.class);
        org.mockito.Mockito.lenient().when(op.getName()).thenReturn(name);
        bukkit.when(() -> Bukkit.getOfflinePlayer(id)).thenReturn(op);
    }

    @Test
    void createFirm_assignsTreasuryAccount_andPersistsDefaultRoles() {
        UUID actor = UUID.randomUUID();
        stubOfflinePlayer(actor, "Alice");

        when(firms.getFirmsByNameCount("Acme")).thenReturn(0);
        when(firms.getFirmsOwnedByCount(actor.toString())).thenReturn(0);

        // Simulate generated key after insert.
        doAnswer(inv -> {
            Firm f = inv.getArgument(0);
            f.setFirmId(42);
            return null;
        }).when(firms).createFirm(any());

        Account treasuryAccount = new Account();
        treasuryAccount.setAccountId(7);
        when(treasury.createAccount(eq(AccountType.BUSINESS), eq(actor), eq("Acme Corporate Account")))
                .thenReturn(treasuryAccount);

        Firm result = svc.createFirm("Acme", actor);

        assertThat(result.getFirmId()).isEqualTo(42);
        assertThat(result.getDisplayName()).isEqualTo("Acme");
        assertThat(result.getDefaultAccountId()).isEqualTo(7);
        assertThat(result.getProprietorUuid()).isEqualTo(actor.toString());

        verify(treasury).addMember(7, actor, actor);
        verify(treasury).addAuthorizer(7, actor, actor);
        verify(accounts).insertFirmAccount(42, 7);
        // 5 default roles + 5 default permissions
        verify(roles, times(5)).insertRole(any());
        verify(roles, times(5)).addRolePermission(any());
        verify(firms).updateFirm(result);
    }

    @Test
    void createFirm_archivesOrphanedTreasuryAccountWhenDbWriteFails() {
        UUID actor = UUID.randomUUID();
        when(firms.getFirmsByNameCount("Acme")).thenReturn(0);
        when(firms.getFirmsOwnedByCount(actor.toString())).thenReturn(0);
        doAnswer(inv -> {
            ((Firm) inv.getArgument(0)).setFirmId(42);
            return null;
        }).when(firms).createFirm(any());

        Account treasuryAccount = new Account();
        treasuryAccount.setAccountId(7);
        when(treasury.createAccount(eq(AccountType.BUSINESS), eq(actor), any())).thenReturn(treasuryAccount);

        // A DB write after the Treasury account is created fails → the firm rows
        // roll back, so the account must be compensated (archived) (ADT-11).
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(firms).updateFirm(any());

        assertThatThrownBy(() -> svc.createFirm("Acme", actor))
                .isInstanceOf(RuntimeException.class);
        verify(treasury).archiveAccount(7);
    }

    @Test
    void createFirm_compensationFailure_stillPropagatesOriginalError() {
        UUID actor = UUID.randomUUID();
        when(firms.getFirmsByNameCount("Acme")).thenReturn(0);
        when(firms.getFirmsOwnedByCount(actor.toString())).thenReturn(0);
        doAnswer(inv -> {
            ((Firm) inv.getArgument(0)).setFirmId(42);
            return null;
        }).when(firms).createFirm(any());

        Account treasuryAccount = new Account();
        treasuryAccount.setAccountId(7);
        when(treasury.createAccount(eq(AccountType.BUSINESS), eq(actor), any())).thenReturn(treasuryAccount);

        RuntimeException dbError = new RuntimeException("db down");
        org.mockito.Mockito.doThrow(dbError).when(firms).updateFirm(any());
        // The compensating archive ALSO fails — the original DB error must still
        // surface (the cleanup failure is suppressed/logged) (ADT-11).
        org.mockito.Mockito.doThrow(new RuntimeException("treasury down")).when(treasury).archiveAccount(7);

        assertThatThrownBy(() -> svc.createFirm("Acme", actor)).isSameAs(dbError);
        verify(treasury).archiveAccount(7);
    }

    @Test
    void createFirm_withinCooldown_throws() {
        UUID actor = UUID.randomUUID();
        when(firms.getFirmsByNameCount("Acme")).thenReturn(0);
        when(firmConfig.hasCreateCooldown()).thenReturn(true);
        when(firmConfig.getCreateCooldownSeconds()).thenReturn(300);
        when(firms.secondsSinceLastCreation(actor.toString())).thenReturn(100L);

        assertThatThrownBy(() -> svc.createFirm("Acme", actor))
                .isInstanceOf(ExceedsLimitException.class);
        verify(firms, never()).createFirm(any());
    }

    @Test
    void createFirm_firstFirmEver_passesCooldownGate() {
        UUID actor = UUID.randomUUID();
        stubOfflinePlayer(actor, "Alice");
        when(firms.getFirmsByNameCount("Acme")).thenReturn(0);
        when(firmConfig.hasCreateCooldown()).thenReturn(true);
        // Never created a firm → null elapsed → not blocked.
        when(firms.secondsSinceLastCreation(actor.toString())).thenReturn(null);

        doAnswer(inv -> { ((Firm) inv.getArgument(0)).setFirmId(42); return null; })
                .when(firms).createFirm(any());
        Account acc = new Account();
        acc.setAccountId(7);
        when(treasury.createAccount(eq(AccountType.BUSINESS), eq(actor), eq("Acme Corporate Account")))
                .thenReturn(acc);

        Firm result = svc.createFirm("Acme", actor);

        assertThat(result.getFirmId()).isEqualTo(42);
        verify(firms).createFirm(any());
    }

    @Test
    void createFirm_rejectsLongName() {
        UUID actor = UUID.randomUUID();
        String tooLong = "x".repeat(33); // limit is 32, so 33 chars trips it
        assertThatThrownBy(() -> svc.createFirm(tooLong, actor))
                .isInstanceOf(BadCommandException.class);
        verify(firms, never()).createFirm(any());
    }

    @Test
    void createFirm_rejectsNamesStartingWithDigit() {
        UUID actor = UUID.randomUUID();
        assertThatThrownBy(() -> svc.createFirm("123Foo", actor))
                .isInstanceOf(BadCommandException.class);
        verify(firms, never()).createFirm(any());
    }

    @Test
    void createFirm_rejectsMiniMessageInjection() {
        UUID actor = UUID.randomUUID();
        assertThatThrownBy(() -> svc.createFirm("<click>Hack</click>", actor))
                .isInstanceOf(BadCommandException.class);
        verify(firms, never()).createFirm(any());
    }

    @Test
    void createFirm_rejectsDuplicateName() {
        UUID actor = UUID.randomUUID();
        stubOfflinePlayer(actor, "Alice");

        when(firms.getFirmsByNameCount("Acme")).thenReturn(1);
        assertThatThrownBy(() -> svc.createFirm("Acme", actor))
                .isInstanceOf(ExceedsLimitException.class);
        verify(firms, never()).createFirm(any());
    }

    @Test
    void createFirm_rejectsWhenOwnerAtLimit() {
        UUID actor = UUID.randomUUID();
        stubOfflinePlayer(actor, "Alice");

        when(firms.getFirmsByNameCount("Acme")).thenReturn(0);
        when(firms.getFirmsOwnedByCount(actor.toString())).thenReturn(3);
        assertThatThrownBy(() -> svc.createFirm("Acme", actor))
                .isInstanceOf(ExceedsLimitException.class);
        verify(firms, never()).createFirm(any());
    }

    @Test
    void disbandFirm_sweepsEachAccountsLockedBalance_toProprietor() {
        UUID proprietor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setDisplayName("Acme");
        firm.setProprietorUuid(proprietor.toString());
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(firms.isProprietorByFirmId(1, proprietor.toString())).thenReturn(true);

        Account personal = new Account();
        personal.setAccountId(99);
        when(treasury.resolveOrCreatePersonal(proprietor)).thenReturn(personal);

        FirmAccount fa1 = new FirmAccount(1, 10, null);
        FirmAccount fa2 = new FirmAccount(1, 11, null);
        when(accounts.listAccountsByFirm(1)).thenReturn(List.of(fa1, fa2));

        // This caller wins the atomic archive (1 row flipped), so it drains.
        when(firms.archiveFirm(1)).thenReturn(1);

        svc.disbandFirm("Acme", proprietor);

        // Each firm account is drained via the sweep-locked-balance primitive — never a
        // read-snapshot-then-transfer — so a concurrent credit can't strand a residual
        // in the archived firm account (business/behaviour/0003). The amount is NOT read
        // on the business side; sweepAll reads it under the FOR UPDATE lock inside
        // Treasury, so the business layer must never pre-read a balance to size the move.
        verify(treasury).sweepAll(10, 99, "Firm disbanded", proprietor, "BusinessPlugin");
        verify(treasury).sweepAll(11, 99, "Firm disbanded", proprietor, "BusinessPlugin");
        verify(treasury, never()).getBalanceByAccountId(org.mockito.ArgumentMatchers.anyInt());
        verify(treasury, never()).transfer(any(TransferRequest.class));

        verify(treasury).archiveAccount(10);
        verify(treasury).archiveAccount(11);
        verify(accounts).removeFirmAccount(1, 10);
        verify(accounts).removeFirmAccount(1, 11);
        verify(firms).archiveFirm(1);
    }

    @Test
    void disbandFirm_archivesFirmAndContinuesWhenOneAccountFails() {
        UUID proprietor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setProprietorUuid(proprietor.toString());
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(firms.isProprietorByFirmId(1, proprietor.toString())).thenReturn(true);

        Account personal = new Account();
        personal.setAccountId(99);
        when(treasury.resolveOrCreatePersonal(proprietor)).thenReturn(personal);

        FirmAccount fa1 = new FirmAccount(1, 10, null);
        FirmAccount fa2 = new FirmAccount(1, 11, null);
        when(accounts.listAccountsByFirm(1)).thenReturn(List.of(fa1, fa2));

        // First account fails mid-drain; the firm must still be archived (it is
        // archived before any money moves) and the second account still drained.
        when(treasury.sweepAll(10, 99, "Firm disbanded", proprietor, "BusinessPlugin"))
                .thenThrow(new RuntimeException("treasury down"));
        when(firms.archiveFirm(1)).thenReturn(1);

        svc.disbandFirm("Acme", proprietor);

        verify(firms).archiveFirm(1);
        verify(treasury).archiveAccount(11);
        verify(accounts).removeFirmAccount(1, 11);
        // The failed account is left linked for a later reconciliation.
        verify(treasury, never()).archiveAccount(10);
        verify(accounts, never()).removeFirmAccount(1, 10);
    }

    @Test
    void disbandFirm_lostArchiveRace_doesNotDrain() {
        // Two concurrent disbands both pass the stale in-memory archived check and
        // both snapshot the same positive balances. The atomic conditional archive
        // (WHERE is_archived = 0) lets exactly one win: the loser sees 0 rows
        // affected and must short-circuit WITHOUT moving any money, rather than
        // relying on Treasury's overdraft floor to swallow a second drain.
        UUID proprietor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setDisplayName("Acme");
        firm.setProprietorUuid(proprietor.toString());
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(firms.isProprietorByFirmId(1, proprietor.toString())).thenReturn(true);

        Account personal = new Account();
        personal.setAccountId(99);
        when(treasury.resolveOrCreatePersonal(proprietor)).thenReturn(personal);

        FirmAccount fa1 = new FirmAccount(1, 10, null);
        when(accounts.listAccountsByFirm(1)).thenReturn(List.of(fa1));

        // This caller lost the race: the firm was already flipped to archived by
        // the winner, so the conditional UPDATE touches 0 rows.
        when(firms.archiveFirm(1)).thenReturn(0);

        svc.disbandFirm("Acme", proprietor);

        // No drain of any kind: no sweep, no balance read, no transfer, no account teardown.
        verify(treasury, never()).sweepAll(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(), any(),
                org.mockito.ArgumentMatchers.anyString());
        verify(treasury, never()).getBalanceByAccountId(org.mockito.ArgumentMatchers.anyInt());
        verify(treasury, never()).transfer(any(TransferRequest.class));
        verify(treasury, never()).archiveAccount(org.mockito.ArgumentMatchers.anyInt());
        verify(accounts, never()).removeFirmAccount(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void disbandFirm_unknownFirm_throws() {
        when(firms.getFirmByName("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.disbandFirm("Ghost", UUID.randomUUID()))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void disbandFirm_alreadyArchived_throws() {
        UUID proprietor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setProprietorUuid(proprietor.toString());
        firm.setArchived(true);
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        assertThatThrownBy(() -> svc.disbandFirm("Acme", proprietor))
                .isInstanceOf(BadCommandException.class);
        // Disband must be idempotent: no re-archive, no broadcast-triggering work.
        verify(firms, never()).archiveFirm(org.mockito.ArgumentMatchers.anyInt());
        verify(treasury, never()).archiveAccount(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void disbandFirm_notProprietor_throws() {
        UUID proprietor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setProprietorUuid(proprietor.toString());
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(firms.isProprietorByFirmId(1, proprietor.toString())).thenReturn(false);
        assertThatThrownBy(() -> svc.disbandFirm("Acme", proprietor))
                .isInstanceOf(io.paradaux.hibernia.framework.exceptions.NoPermissionException.class);
    }

    // ---------- admin overrides (PAR-11) ----------

    @Test
    void adminDisbandFirm_bypassesProprietorCheck() {
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setDisplayName("Acme");
        firm.setProprietorUuid(UUID.randomUUID().toString());
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(accounts.listAccountsByFirm(1)).thenReturn(List.of());

        svc.adminDisbandFirm("Acme");

        verify(firms).archiveFirm(1);
        // Proprietorship is never consulted on the admin path.
        verify(firms, never()).isProprietorByFirmId(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void adminDisbandFirm_unknownFirm_throws() {
        when(firms.getFirmByName("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.adminDisbandFirm("Ghost"))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void adminDisbandFirm_alreadyArchived_throws() {
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setArchived(true);
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        assertThatThrownBy(() -> svc.adminDisbandFirm("Acme"))
                .isInstanceOf(BadCommandException.class);
        verify(firms, never()).archiveFirm(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void renameFirm_updatesDisplayName() {
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setDisplayName("Acme");
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(firms.getFirmsByNameCount("Globex")).thenReturn(0);

        Firm result = svc.renameFirm("Acme", "Globex");

        assertThat(result.getDisplayName()).isEqualTo("Globex");
        ArgumentCaptor<Firm> update = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(update.capture());
        assertThat(update.getValue().getFirmId()).isEqualTo(1);
        assertThat(update.getValue().getDisplayName()).isEqualTo("Globex");
    }

    @Test
    void renameFirm_unknownFirm_throws() {
        when(firms.getFirmByName("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.renameFirm("Ghost", "Globex"))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void renameFirm_invalidName_throws() {
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setDisplayName("Acme");
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        // Single character fails the 2–32 firm-name rule.
        assertThatThrownBy(() -> svc.renameFirm("Acme", "x"))
                .isInstanceOf(BadCommandException.class);
        verify(firms, never()).updateFirm(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void renameFirm_duplicateName_throws() {
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setDisplayName("Acme");
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(firms.getFirmsByNameCount("Globex")).thenReturn(1);
        assertThatThrownBy(() -> svc.renameFirm("Acme", "Globex"))
                .isInstanceOf(ExceedsLimitException.class);
    }

    @Test
    void renameFirm_sameNameCaseChange_skipsUniquenessCheck() {
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setDisplayName("Acme");
        when(firms.getFirmByName("Acme")).thenReturn(firm);

        svc.renameFirm("Acme", "ACME");

        // A self-rename must not be rejected as a collision with itself.
        verify(firms, never()).getFirmsByNameCount(org.mockito.ArgumentMatchers.anyString());
        verify(firms).updateFirm(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adminSetHq_updatesRegion() {
        Firm firm = new Firm();
        firm.setFirmId(1);
        when(firms.getFirmByName("Acme")).thenReturn(firm);

        svc.adminSetHq("Acme", "plot-7");

        ArgumentCaptor<Firm> update = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(update.capture());
        assertThat(update.getValue().getFirmId()).isEqualTo(1);
        assertThat(update.getValue().getHqRegion()).isEqualTo("plot-7");
    }

    @Test
    void adminSetDiscord_updatesUrl() {
        Firm firm = new Firm();
        firm.setFirmId(1);
        when(firms.getFirmByName("Acme")).thenReturn(firm);

        svc.adminSetDiscord("Acme", "https://discord.gg/x");

        ArgumentCaptor<Firm> update = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(update.capture());
        assertThat(update.getValue().getDiscordUrl()).isEqualTo("https://discord.gg/x");
    }

    @Test
    void adminSetProprietor_updatesProprietorReassignsAccountsAndAudits() {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setProprietorUuid(oldOwner.toString());
        when(firms.getFirmByName("Acme")).thenReturn(firm);

        svc.adminSetProprietor("Acme", newOwner, admin);

        ArgumentCaptor<Firm> update = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(update.capture());
        assertThat(update.getValue().getProprietorUuid()).isEqualTo(newOwner.toString());
        // A direct proprietor change must also hand the treasury account(s) to the
        // new proprietor, or they'd be locked out of the firm's money (PAR-141).
        verify(firmAccountService).reassignAccountsToNewProprietor(1, newOwner);
        verify(staffService, never()).resignFromFirm(any(), any());
        // The forced handover must be audited (from old, to new, by the admin) — PAR-315.
        verify(requests).recordAdminOverride(eq(1), eq(oldOwner.toString()),
                eq(newOwner.toString()), eq(admin.toString()), any());
        // Any in-flight player transfer is cancelled so it can't later re-hand the firm.
        verify(requests).cancelActiveTransfers(1);
        // Ordering matters: every DB write (cancel, audit) must precede the cross-plugin
        // reassignment IPC, which cannot roll back with the JDBC transaction (ADT-11).
        InOrder order = inOrder(requests, firmAccountService);
        order.verify(requests).cancelActiveTransfers(1);
        order.verify(requests).recordAdminOverride(eq(1), any(), any(), any(), any());
        order.verify(firmAccountService).reassignAccountsToNewProprietor(1, newOwner);
    }

    @Test
    void adminSetProprietor_whenAuditInsertFails_doesNotReassignAccounts() {
        // ADT-11-class regression guard: the audit-row DB insert is ordered BEFORE the
        // un-rollback-able Treasury account reassignment. If recordAdminOverride throws,
        // the reassignment IPC must never have run — otherwise a rolled-back JDBC txn
        // would leave Treasury account ownership diverged from the firm row.
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setProprietorUuid(oldOwner.toString());
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(requests.recordAdminOverride(anyInt(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("audit insert failed"));

        assertThatThrownBy(() -> svc.adminSetProprietor("Acme", newOwner, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);

        verify(firmAccountService, never()).reassignAccountsToNewProprietor(anyInt(), any());
    }

    @Test
    void adminSetProprietor_whenNewProprietorIsEmployee_resignsThemFirst() {
        UUID newOwner = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setProprietorUuid(UUID.randomUUID().toString());
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(staffService.isEmployedBy(1, newOwner)).thenReturn(true);

        svc.adminSetProprietor("Acme", newOwner, UUID.randomUUID());

        // A proprietor cannot also hold an employee slot: resign, then hand over.
        verify(staffService).resignFromFirm("Acme", newOwner);
        verify(firms).updateFirm(any());
        verify(firmAccountService).reassignAccountsToNewProprietor(1, newOwner);
    }

    @Test
    void adminSetHq_unknownFirm_throws() {
        when(firms.getFirmByName("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.adminSetHq("Ghost", "plot"))
                .isInstanceOf(BadCommandException.class);
    }

    // ---------- active-only resolution (PAR-87) ----------

    @Test
    void getFirmByNameOrId_returnsNullForArchivedFirm() {
        Firm archived = new Firm();
        archived.setFirmId(1);
        archived.setArchived(true);
        when(firms.getFirmByName("Acme")).thenReturn(archived);
        // A disbanded firm must not resolve on the command/action path.
        assertThat(svc.getFirmByNameOrId("Acme")).isNull();
    }

    @Test
    void getFirmByNameOrId_returnsActiveFirm() {
        Firm active = new Firm();
        active.setFirmId(1);
        active.setArchived(false);
        when(firms.getFirmByName("Acme")).thenReturn(active);
        assertThat(svc.getFirmByNameOrId("Acme")).isSameAs(active);
    }

    @Test
    void getAnyFirmByNameOrId_returnsArchivedFirm() {
        Firm archived = new Firm();
        archived.setFirmId(7);
        archived.setArchived(true);
        when(firms.getFirmById(7)).thenReturn(archived);
        // Readers still see archived firms (resolved by numeric id here).
        assertThat(svc.getAnyFirmByNameOrId("7")).isSameAs(archived);
    }

    @Test
    void getAnyFirmById_returnsArchivedFirmDirectlyWithoutStringRoundTrip() {
        Firm archived = new Firm();
        archived.setFirmId(7);
        archived.setArchived(true);
        when(firms.getFirmById(7)).thenReturn(archived);
        // Archived-inclusive by-id reader (ADT-96): no int→String→int detour.
        assertThat(svc.getAnyFirmById(7)).isSameAs(archived);
    }

    @Test
    void listAllFirms_clampsPageAndSize() {
        when(firms.listAllFiltered(25, 0, false)).thenReturn(List.of());
        svc.listAllFirms(0, 0); // both invalid → page=1, size=25
        verify(firms).listAllFiltered(25, 0, false);
    }

    @Test
    void listAllFirms_paginates() {
        when(firms.listAllFiltered(10, 20, false)).thenReturn(List.of());
        svc.listAllFirms(3, 10); // offset = (3-1)*10 = 20
        verify(firms).listAllFiltered(10, 20, false);
    }

    @Test
    void listOwnedOrMemberFirms_delegates() {
        UUID p = UUID.randomUUID();
        when(firms.listOwnedOrMemberFirms(p.toString())).thenReturn(List.of(new Firm()));
        assertThat(svc.listOwnedOrMemberFirms(p)).hasSize(1);
    }

    @Test
    void updateFirmHq_updatesHqRegion() {
        UUID actor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(5);
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(staffService.hasPermission(5, actor, RolePermission.ADMIN)).thenReturn(true);
        when(areas.isValidPlot("plaza-1")).thenReturn(true);

        svc.updateFirmHq("Acme", "plaza-1", actor);

        ArgumentCaptor<Firm> cap = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(cap.capture());
        assertThat(cap.getValue().getFirmId()).isEqualTo(5);
        assertThat(cap.getValue().getHqRegion()).isEqualTo("plaza-1");
        assertThat(cap.getValue().getDisplayName()).isNull();
    }

    @Test
    void updateFirmHq_invalidPlot_throws() {
        UUID actor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(5);
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(staffService.hasPermission(5, actor, RolePermission.ADMIN)).thenReturn(true);
        when(areas.isValidPlot("nowhere")).thenReturn(false);

        assertThatThrownBy(() -> svc.updateFirmHq("Acme", "nowhere", actor))
                .isInstanceOf(BadCommandException.class);
        verify(firms, never()).updateFirm(any());
    }

    @Test
    void updateFirmHq_noPermission_throws() {
        Firm firm = new Firm();
        firm.setFirmId(5);
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(staffService.hasPermission(eq(5), any(), eq(RolePermission.ADMIN))).thenReturn(false);
        assertThatThrownBy(() -> svc.updateFirmHq("Acme", "plaza-1", UUID.randomUUID()))
                .isInstanceOf(NoPermissionException.class);
        verify(firms, never()).updateFirm(any());
    }

    @Test
    void updateFirmHq_unknown_throws() {
        when(firms.getFirmByName("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.updateFirmHq("Ghost", "x", UUID.randomUUID()))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void getAnyFirmByNameOrId_numericOverflow_returnsNullInsteadOfThrowing() {
        // An all-digits string too large for an int must not throw (ADT-56).
        assertThat(svc.getAnyFirmByNameOrId("99999999999999999999")).isNull();
        verify(firms, never()).getFirmById(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void updateFirmDiscord_updatesUrl() {
        UUID actor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(8);
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(staffService.hasPermission(8, actor, RolePermission.ADMIN)).thenReturn(true);

        svc.updateFirmDiscord("Acme", "https://discord.gg/abc", actor);

        ArgumentCaptor<Firm> cap = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(cap.capture());
        assertThat(cap.getValue().getFirmId()).isEqualTo(8);
        assertThat(cap.getValue().getDiscordUrl()).isEqualTo("https://discord.gg/abc");
    }

    @Test
    void updateFirmDiscord_noPermission_throws() {
        Firm firm = new Firm();
        firm.setFirmId(8);
        when(firms.getFirmByName("Acme")).thenReturn(firm);
        when(staffService.hasPermission(eq(8), any(), eq(RolePermission.ADMIN))).thenReturn(false);
        assertThatThrownBy(() -> svc.updateFirmDiscord("Acme", "x", UUID.randomUUID()))
                .isInstanceOf(NoPermissionException.class);
        verify(firms, never()).updateFirm(any());
    }

    @Test
    void updateFirmDiscord_unknown_throws() {
        when(firms.getFirmByName("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.updateFirmDiscord("Ghost", "x", UUID.randomUUID()))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void updateProprietor_setsFirmIdAndProprietorUuid() {
        UUID newOwner = UUID.randomUUID();
        svc.updateProprietor(11, newOwner);

        ArgumentCaptor<Firm> cap = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(cap.capture());
        assertThat(cap.getValue().getFirmId()).isEqualTo(11);
        assertThat(cap.getValue().getProprietorUuid()).isEqualTo(newOwner.toString());
    }

    @Test
    void updateDefaultAccount_writesFirmIdAndAccountId() {
        svc.updateDefaultAccount(7, 42);

        ArgumentCaptor<Firm> cap = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(cap.capture());
        assertThat(cap.getValue().getFirmId()).isEqualTo(7);
        assertThat(cap.getValue().getDefaultAccountId()).isEqualTo(42);
    }

    @Test
    void isProprietor_byName_delegates() {
        UUID p = UUID.randomUUID();
        when(firms.isProprietorByFirmName("Acme", p.toString())).thenReturn(true);
        assertThat(svc.isProprietor("Acme", p)).isTrue();
    }

    @Test
    void isProprietor_byId_delegates() {
        UUID p = UUID.randomUUID();
        when(firms.isProprietorByFirmId(3, p.toString())).thenReturn(false);
        assertThat(svc.isProprietor(3, p)).isFalse();
    }

    @Test
    void getFirmByNameOrId_numericInput_looksUpById() {
        Firm f = new Firm();
        when(firms.getFirmById(42)).thenReturn(f);
        assertThat(svc.getFirmByNameOrId("42")).isSameAs(f);
        verify(firms, never()).getFirmByName(any());
    }

    @Test
    void getFirmByNameOrId_textInput_looksUpByName() {
        Firm f = new Firm();
        when(firms.getFirmByName("Acme")).thenReturn(f);
        assertThat(svc.getFirmByNameOrId("Acme")).isSameAs(f);
        verify(firms, never()).getFirmById(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void getFirmByNameOrId_nullInput_looksUpByName() {
        when(firms.getFirmByName(null)).thenReturn(null);
        assertThat(svc.getFirmByNameOrId(null)).isNull();
    }

    @Test
    void listAllActiveFirms_delegates() {
        when(firms.listAllActive()).thenReturn(List.of(new Firm(), new Firm()));
        assertThat(svc.listAllActiveFirms()).hasSize(2);
    }

    // ---------- int-id overloads (structure/0004) ----------
    // Same behaviour as the String overloads, resolving the firm by id
    // (getFirmById / getAnyFirmById) rather than round-tripping through
    // getFirmByNameOrId(String.valueOf(id)).

    @Test
    void disbandFirm_byId_archivesAndDrains() {
        UUID proprietor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(1);
        firm.setDisplayName("Acme");
        firm.setProprietorUuid(proprietor.toString());
        when(firms.getFirmById(1)).thenReturn(firm);
        when(firms.isProprietorByFirmId(1, proprietor.toString())).thenReturn(true);

        Account personal = new Account();
        personal.setAccountId(99);
        when(treasury.resolveOrCreatePersonal(proprietor)).thenReturn(personal);
        when(accounts.listAccountsByFirm(1)).thenReturn(List.of(new FirmAccount(1, 10, null)));
        when(firms.archiveFirm(1)).thenReturn(1);

        svc.disbandFirm(1, proprietor);

        verify(treasury).sweepAll(10, 99, "Firm disbanded", proprietor, "BusinessPlugin");
    }

    @Test
    void updateFirmHq_byId_updatesHqRegion() {
        UUID actor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(5);
        when(firms.getFirmById(5)).thenReturn(firm);
        when(staffService.hasPermission(5, actor, RolePermission.ADMIN)).thenReturn(true);
        when(areas.isValidPlot("plaza-1")).thenReturn(true);

        svc.updateFirmHq(5, "plaza-1", actor);

        ArgumentCaptor<Firm> cap = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(cap.capture());
        assertThat(cap.getValue().getHqRegion()).isEqualTo("plaza-1");
    }

    @Test
    void updateFirmDiscord_byId_updatesUrl() {
        UUID actor = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(8);
        when(firms.getFirmById(8)).thenReturn(firm);
        when(staffService.hasPermission(8, actor, RolePermission.ADMIN)).thenReturn(true);

        svc.updateFirmDiscord(8, "https://discord.gg/abc", actor);

        ArgumentCaptor<Firm> cap = ArgumentCaptor.forClass(Firm.class);
        verify(firms).updateFirm(cap.capture());
        assertThat(cap.getValue().getDiscordUrl()).isEqualTo("https://discord.gg/abc");
    }
}
