package io.paradaux.treasuryrestapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.paradaux.treasuryrestapi.dto.TransferResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.model.AccountAdminSummary;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Integration tests for the SERVICE-scoped admin tools tranche (PAR-217): arbitrary
 * transfer, account rename/owner/archive/unarchive, firm details. Drives the
 * services directly against the embedded MariaDB.
 */
class AdminToolsIT extends EmbeddedDbIT {

    @Autowired
    private TransferService transfers;

    @Autowired
    private AdminAccountService accounts;

    @Autowired
    private AdminFirmService firms;

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private VerifiedToken service() {
        return new VerifiedToken(1L, A, "SERVICE", null, null);
    }

    private VerifiedToken personal() {
        return new VerifiedToken(2L, A, "PERSONAL", 1L, null);
    }

    // ── admin transfer ───────────────────────────────────────────────────────────

    @Test
    void adminTransfer_movesMoneyBetweenArbitraryAccounts() {
        insertAccount(1, "BUSINESS", A, "Src");
        insertAccount(2, "BUSINESS", B, "Dst");
        seedBalance(1, "500.00");
        seedBalance(2, "0.00");

        TransferResponse r = transfers.adminTransfer(service(), null, 1L, 2L, "200.00", "admin move", null);

        assertThat(r.fromAccountId()).isEqualTo(1L);
        assertThat(r.toAccountId()).isEqualTo(2L);
        assertThat(r.memo()).isEqualTo("admin move");
        assertThat(sumPostings(1)).isEqualByComparingTo("-200.00");
        assertThat(sumPostings(2)).isEqualByComparingTo("200.00");
    }

    @Test
    void adminTransfer_requiresServiceKey() {
        ApiException ex = catchThrowableOfType(
                () -> transfers.adminTransfer(personal(), null, 1L, 2L, "10.00", "x", null), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── account rename / owner / archive ──────────────────────────────────────────

    @Test
    void renameAccount_updatesDisplayName() {
        insertAccount(1, "BUSINESS", A, "Old");
        AccountAdminSummary s = accounts.rename(service(), 1L, "New Name");
        assertThat(s.getDisplayName()).isEqualTo("New Name");
    }

    @Test
    void changeOwner_business_byName_resolvesViaFirmPlayers() {
        insertAccount(1, "BUSINESS", A, "Firm Acct");
        insertPlayer(B, "Bob");
        AccountAdminSummary s = accounts.changeOwner(service(), 1L, "Bob");
        assertThat(s.getOwnerUuid()).isEqualTo(B);
    }

    @Test
    void changeOwner_personalAccount_blockedWhenTargetAlreadyHasOne() {
        insertAccount(1, "PERSONAL", A, "A personal"); // the account being moved
        insertAccount(2, "PERSONAL", B, "B personal"); // B already has a personal account
        ApiException ex =
                catchThrowableOfType(() -> accounts.changeOwner(service(), 1L, B.toString()), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("PERSONAL_ACCOUNT_EXISTS");
    }

    @Test
    void archive_then_unarchive() {
        insertAccount(1, "BUSINESS", A, "Acct");
        assertThat(accounts.archive(service(), 1L).isArchived()).isTrue();
        assertThat(isAccountArchived(1)).isTrue();
        assertThat(accounts.unarchive(service(), 1L).isArchived()).isFalse();
        assertThat(isAccountArchived(1)).isFalse();
    }

    @Test
    void accountOps_requireServiceKey() {
        insertAccount(1, "BUSINESS", A, "Acct");
        ApiException ex = catchThrowableOfType(() -> accounts.rename(personal(), 1L, "x"), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountNotFound_is404() {
        ApiException ex = catchThrowableOfType(() -> accounts.archive(service(), 999L), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── firm details ──────────────────────────────────────────────────────────────

    @Test
    void updateFirmDetails_setsDiscordAndHq() {
        insertFirm(10, "Acme", A, null);
        var r = firms.updateDetails(service(), 10L, "https://discord.gg/xx", "Spawn");
        assertThat(r.discordUrl()).isEqualTo("https://discord.gg/xx");
        assertThat(r.hqRegion()).isEqualTo("Spawn");
    }

    @Test
    void updateFirmDetails_requiresServiceKey() {
        insertFirm(10, "Acme", A, null);
        ApiException ex =
                catchThrowableOfType(() -> firms.updateDetails(personal(), 10L, "x", "y"), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
