package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.FirmDisbandResponse;
import io.paradaux.treasuryrestapi.dto.FirmResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Integration tests for {@link AdminFirmService} (PAR-210) against the embedded
 * MariaDB: disband (balance sweep → archive accounts → soft-delete links → archive
 * firm), rename (name rules + uniqueness), and SERVICE-key gating. Drives the
 * service directly, exactly as {@link TransferToPlayerIT} does.
 */
class AdminFirmServiceIT extends EmbeddedDbIT {

    @Autowired
    private AdminFirmService adminFirmService;

    private static final UUID PROPRIETOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private VerifiedToken serviceToken() {
        return new VerifiedToken(1L, UUID.fromString("99999999-9999-9999-9999-999999999999"), "SERVICE", null, null);
    }

    private VerifiedToken personalToken() {
        return new VerifiedToken(2L, PROPRIETOR, "PERSONAL", 1L, null);
    }

    // ── disband ──────────────────────────────────────────────────────────────────

    @Test
    void disband_sweepsBalances_archivesAccounts_softDeletesLinks_archivesFirm() {
        // Proprietor's personal account (sweep destination)
        insertAccount(1, "PERSONAL", PROPRIETOR, "Prop");
        seedBalance(1, "0.00");
        // Firm + two firm accounts with balances
        insertFirm(10, "Acme", PROPRIETOR, null);
        insertAccount(2, "BUSINESS", null, "Acme Main");
        insertAccount(3, "BUSINESS", null, "Acme Reserve");
        linkFirmAccount(10, 2);
        linkFirmAccount(10, 3);
        seedBalance(2, "500.00");
        seedBalance(3, "250.00");

        FirmDisbandResponse resp = adminFirmService.disband(serviceToken(), 10);

        assertThat(resp.archived()).isTrue();
        assertThat(resp.accounts()).hasSize(2);
        // Balances swept to the proprietor's personal account
        assertThat(sumPostings(2)).isEqualByComparingTo("-500.00");
        assertThat(sumPostings(3)).isEqualByComparingTo("-250.00");
        assertThat(sumPostings(1)).isEqualByComparingTo("750.00");
        // Accounts archived, links soft-deleted, firm archived
        assertThat(isAccountArchived(2)).isTrue();
        assertThat(isAccountArchived(3)).isTrue();
        assertThat(liveFirmAccountLinks(10)).isZero();
        assertThat(isFirmArchived(10)).isTrue();
    }

    @Test
    void disband_zeroBalance_archivesWithoutTransfer() {
        insertFirm(10, "Empty", PROPRIETOR, null);
        insertAccount(2, "BUSINESS", null, "Empty Main");
        linkFirmAccount(10, 2);
        seedBalance(2, "0.00");

        FirmDisbandResponse resp = adminFirmService.disband(serviceToken(), 10);

        assertThat(resp.accounts()).hasSize(1);
        assertThat(resp.accounts().get(0).sweptAmount()).isNull();
        assertThat(rowCount("ledger_txns")).isZero(); // no money moved
        assertThat(isAccountArchived(2)).isTrue();
        assertThat(isFirmArchived(10)).isTrue();
    }

    @Test
    void disband_alreadyArchived_isIdempotentNoOp() {
        insertFirm(10, "Gone", PROPRIETOR, null);
        archiveFirm(10);

        FirmDisbandResponse resp = adminFirmService.disband(serviceToken(), 10);

        assertThat(resp.archived()).isTrue();
        assertThat(resp.accounts()).isEmpty();
    }

    @Test
    void disband_positiveBalanceButNoProprietorPersonalAccount_is422() {
        // Proprietor has NO personal account; firm account holds money.
        insertFirm(10, "Acme", PROPRIETOR, null);
        insertAccount(2, "BUSINESS", null, "Acme Main");
        linkFirmAccount(10, 2);
        seedBalance(2, "100.00");

        ApiException ex = catchThrowableOfType(
                () -> adminFirmService.disband(serviceToken(), 10), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ex.getErrorCode()).isEqualTo("PROPRIETOR_NO_PERSONAL_ACCOUNT");
    }

    @Test
    void disband_requiresServiceKey() {
        insertFirm(10, "Acme", PROPRIETOR, null);
        ApiException ex = catchThrowableOfType(
                () -> adminFirmService.disband(personalToken(), 10), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void disband_unknownFirm_is404() {
        ApiException ex = catchThrowableOfType(
                () -> adminFirmService.disband(serviceToken(), 999), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── rename ───────────────────────────────────────────────────────────────────

    @Test
    void rename_updatesDisplayName() {
        insertFirm(10, "Acme", PROPRIETOR, null);

        FirmResponse resp = adminFirmService.rename(serviceToken(), 10, "Acme Corp");

        assertThat(resp.displayName()).isEqualTo("Acme Corp");
    }

    @Test
    void rename_allowsCaseOnlySelfRename() {
        insertFirm(10, "Acme", PROPRIETOR, null);
        FirmResponse resp = adminFirmService.rename(serviceToken(), 10, "ACME");
        assertThat(resp.displayName()).isEqualTo("ACME");
    }

    @Test
    void rename_rejectsDuplicateNameCaseInsensitively() {
        insertFirm(10, "Acme", PROPRIETOR, null);
        insertFirm(11, "Beta", PROPRIETOR, null);

        ApiException ex = catchThrowableOfType(
                () -> adminFirmService.rename(serviceToken(), 11, "acme"), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("NAME_TAKEN");
    }

    @Test
    void rename_rejectsLeadingDigit() {
        insertFirm(10, "Acme", PROPRIETOR, null);
        assertBadName(10, "1Acme");
    }

    @Test
    void rename_rejectsInvalidCharacters() {
        insertFirm(10, "Acme", PROPRIETOR, null);
        assertBadName(10, "Acme<script>");
    }

    @Test
    void rename_rejectsTooShort() {
        insertFirm(10, "Acme", PROPRIETOR, null);
        assertBadName(10, "A");
    }

    @Test
    void rename_rejectedOnArchivedFirm() {
        insertFirm(10, "Acme", PROPRIETOR, null);
        archiveFirm(10);
        ApiException ex = catchThrowableOfType(
                () -> adminFirmService.rename(serviceToken(), 10, "Acme Corp"), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("FIRM_ARCHIVED");
    }

    @Test
    void rename_requiresServiceKey() {
        insertFirm(10, "Acme", PROPRIETOR, null);
        ApiException ex = catchThrowableOfType(
                () -> adminFirmService.rename(personalToken(), 10, "Acme Corp"), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void assertBadName(int firmId, String name) {
        ApiException ex = catchThrowableOfType(
                () -> adminFirmService.rename(serviceToken(), firmId, name), ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_BODY");
    }
}
