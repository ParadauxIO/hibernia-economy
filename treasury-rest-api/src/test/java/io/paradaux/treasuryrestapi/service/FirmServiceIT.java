package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.FirmAccountSummaryResponse;
import io.paradaux.treasuryrestapi.dto.FirmBalanceResponse;
import io.paradaux.treasuryrestapi.dto.FirmEmployeeResponse;
import io.paradaux.treasuryrestapi.dto.FirmResponse;
import io.paradaux.treasuryrestapi.dto.FirmRoleResponse;
import io.paradaux.treasuryrestapi.dto.FirmUpdateRequest;
import io.paradaux.treasuryrestapi.dto.PublicFirmResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Integration tests for the six previously-uncovered {@link FirmService} read/update
 * methods (finding treasury-rest-api/testing/0006): getPublicFirm, getFirm,
 * getFirmBalance, listEmployees, listRoles, listAccounts, and the successful
 * (persisting) branches of updateFirm / updateAccountDisplayName — validation-reject
 * paths already live in {@code FirmUpdateValidationIT}. Drives the real service +
 * FirmMapper + SQL against the embedded MariaDB.
 */
class FirmServiceIT extends EmbeddedDbIT {

    @Autowired
    private FirmService firmService;

    private static final UUID OWNER = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID EMP = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private VerifiedToken business(long firmId) {
        return new VerifiedToken(1L, OWNER, "BUSINESS", null, firmId);
    }

    // ── getPublicFirm ─────────────────────────────────────────────────────────────

    @Test
    void getPublicFirm_returnsProfile() {
        insertFirm(10, "Acme");
        exec("UPDATE firm SET discord_url = 'https://discord.gg/acme', hq_region = 'Spawn' WHERE firm_id = 10",
                ps -> {});

        PublicFirmResponse r = firmService.getPublicFirm("Acme");
        assertThat(r.firmId()).isEqualTo(10L);
        assertThat(r.displayName()).isEqualTo("Acme");
        assertThat(r.discordUrl()).isEqualTo("https://discord.gg/acme");
        assertThat(r.hqRegion()).isEqualTo("Spawn");
        assertThat(r.archived()).isFalse();
    }

    @Test
    void getPublicFirm_unknown_404() {
        ApiException ex = catchThrowableOfType(ApiException.class, () -> firmService.getPublicFirm("Ghost"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("FIRM_NOT_FOUND");
    }

    // ── getFirm (key-scoped) ──────────────────────────────────────────────────────

    @Test
    void getFirm_returnsScopedFirm() {
        insertFirm(10, "Acme");
        FirmResponse r = firmService.getFirm(business(10));
        assertThat(r.firmId()).isEqualTo(10L);
        assertThat(r.displayName()).isEqualTo("Acme");
    }

    @Test
    void getFirm_personalKey_403() {
        insertFirm(10, "Acme");
        VerifiedToken personal = new VerifiedToken(1L, OWNER, "PERSONAL", 1L, null);
        ApiException ex = catchThrowableOfType(ApiException.class, () -> firmService.getFirm(personal));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── getFirmBalance ────────────────────────────────────────────────────────────

    @Test
    void getFirmBalance_sumsLiveFirmAccounts() {
        insertFirm(10, "Acme");
        insertAccount(1, "BUSINESS", null, "Main");
        insertAccount(2, "BUSINESS", null, "Reserve");
        insertAccount(3, "BUSINESS", null, "Old");
        linkFirmAccount(10, 1);
        linkFirmAccount(10, 2);
        linkFirmAccount(10, 3);
        seedBalance(1, "300.00");
        seedBalance(2, "200.00");
        seedBalance(3, "999.00");
        // Remove account 3's link → it must not count towards the total.
        exec("UPDATE firm_accounts SET removed_at = CURRENT_TIMESTAMP WHERE firm_id = 10 AND account_id = 3",
                ps -> {});

        FirmBalanceResponse r = firmService.getFirmBalance("Acme");
        assertThat(r.firmId()).isEqualTo(10L);
        assertThat(r.totalBalance()).isEqualTo("500.00");
    }

    @Test
    void getFirmBalance_unknown_404() {
        ApiException ex = catchThrowableOfType(ApiException.class, () -> firmService.getFirmBalance("Ghost"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── listEmployees ─────────────────────────────────────────────────────────────

    @Test
    void listEmployees_currentOnly_withRoleAndName() {
        insertFirm(10, "Acme");
        insertFirmRole(100, 10, "Manager", 1, true, false, false);
        insertPlayer(EMP, "Alice");
        insertEmployee(10, EMP, 100, false);
        // A departed employee (left_at set) must not appear.
        UUID gone = UUID.fromString("33333333-3333-3333-3333-333333333333");
        insertEmployee(10, gone, 100, true);

        List<FirmEmployeeResponse> emps = firmService.listEmployees(business(10));
        assertThat(emps).hasSize(1);
        assertThat(emps.get(0).playerUuid()).isEqualTo(EMP.toString());
        assertThat(emps.get(0).playerName()).isEqualTo("Alice");
        assertThat(emps.get(0).roleName()).isEqualTo("Manager");
    }

    // ── listRoles ─────────────────────────────────────────────────────────────────

    @Test
    void listRoles_groupsPermissionsPerRole_skipsDeleted() {
        insertFirm(10, "Acme");
        insertFirmRole(100, 10, "Owner", 0, true, false, false);
        insertFirmRole(101, 10, "Staff", 1, false, true, false);
        insertFirmRole(102, 10, "Ghost", 2, false, false, true); // deleted → excluded
        insertRolePermission(100, "ADMIN", false);
        insertRolePermission(100, "FINANCIAL", false);
        insertRolePermission(101, "DEFAULT", false);
        insertRolePermission(101, "CHESTSHOP", true); // deleted perm → excluded

        List<FirmRoleResponse> roles = firmService.listRoles(business(10));
        assertThat(roles).extracting(FirmRoleResponse::name).containsExactly("Owner", "Staff");
        FirmRoleResponse owner = roles.get(0);
        assertThat(owner.proprietorLike()).isTrue();
        assertThat(owner.permissions()).containsExactlyInAnyOrder("ADMIN", "FINANCIAL");
        FirmRoleResponse staff = roles.get(1);
        assertThat(staff.defaultRole()).isTrue();
        assertThat(staff.permissions()).containsExactly("DEFAULT");
    }

    // ── listAccounts ──────────────────────────────────────────────────────────────

    @Test
    void listAccounts_returnsLiveFirmAccountsWithBalances() {
        insertFirm(10, "Acme");
        insertAccount(1, "BUSINESS", null, "Main");
        insertAccount(2, "BUSINESS", null, "Reserve");
        linkFirmAccount(10, 1);
        linkFirmAccount(10, 2);
        seedBalance(1, "42.00");
        // account 2 has no mat row → COALESCE 0.00

        List<FirmAccountSummaryResponse> accts = firmService.listAccounts(business(10));
        assertThat(accts).extracting(FirmAccountSummaryResponse::accountId).containsExactly(1L, 2L);
        assertThat(accts.get(0).balance()).isEqualTo("42.00");
        assertThat(accts.get(1).balance()).isEqualTo("0.00");
    }

    // ── updateFirm (persisting happy path) ────────────────────────────────────────

    @Test
    void updateFirm_persistsAndReturnsUpdated() {
        insertFirm(10, "Acme");
        FirmResponse r = firmService.updateFirm(business(10),
                new FirmUpdateRequest("https://discord.gg/new", "New HQ"));
        assertThat(r.discordUrl()).isEqualTo("https://discord.gg/new");
        assertThat(r.hqRegion()).isEqualTo("New HQ");
        // Re-read via public firm to confirm it actually persisted.
        PublicFirmResponse pub = firmService.getPublicFirm("Acme");
        assertThat(pub.discordUrl()).isEqualTo("https://discord.gg/new");
        assertThat(pub.hqRegion()).isEqualTo("New HQ");
    }

    @Test
    void updateFirm_emptyStringClearsField() {
        insertFirm(10, "Acme");
        exec("UPDATE firm SET discord_url = 'https://discord.gg/old' WHERE firm_id = 10", ps -> {});
        FirmResponse r = firmService.updateFirm(business(10), new FirmUpdateRequest("", null));
        assertThat(r.discordUrl()).isNull();
        assertThat(firmService.getPublicFirm("Acme").discordUrl()).isNull();
    }

    // ── updateAccountDisplayName (persisting happy path) ──────────────────────────

    @Test
    void updateAccountDisplayName_persistsForFirmAccount() {
        insertFirm(10, "Acme");
        insertAccount(1, "BUSINESS", null, "Old Name");
        linkFirmAccount(10, 1);
        seedBalance(1, "0.00");

        FirmAccountSummaryResponse r = firmService.updateAccountDisplayName(business(10), 1L, "New Name");
        assertThat(r.displayName()).isEqualTo("New Name");
        assertThat(firmService.listAccounts(business(10)).get(0).displayName()).isEqualTo("New Name");
    }

    @Test
    void updateAccountDisplayName_notFirmAccount_403() {
        insertFirm(10, "Acme");
        insertAccount(1, "BUSINESS", null, "Foreign"); // not linked to firm 10
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> firmService.updateAccountDisplayName(business(10), 1L, "X"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorCode()).isEqualTo("FORBIDDEN");
    }
}
