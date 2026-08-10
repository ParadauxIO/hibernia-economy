package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.TransferRequest;
import io.paradaux.treasuryrestapi.dto.TransferResponse;
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
 * Guard-branch coverage for {@link TransferService#executeTransfer} (finding
 * treasury-rest-api/testing/0002): SELF_TRANSFER, archived source/destination,
 * AUTHORIZATION_REQUIRED, and the per-account overdraft floor — plus a happy
 * transfer's ledger conservation. Each guard must reject (no ledger rows written);
 * the happy case must post exactly the two conserving legs. Drives the real service
 * against the embedded MariaDB exactly as the sibling ITs do.
 */
class TransferGuardsIT extends EmbeddedDbIT {

    @Autowired
    private TransferService transferService;

    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INITIATOR = UUID.fromString("99999999-9999-9999-9999-999999999999");

    /** PERSONAL token whose acc claim is the source account. */
    private VerifiedToken token(long accountId) {
        return new VerifiedToken(1L, OWNER, "PERSONAL", accountId, null);
    }

    private ApiException expect(long from, long to, String amount) {
        return catchThrowableOfType(ApiException.class, () ->
                transferService.transfer(token(from), new TransferRequest(null, to, amount, "memo"), null));
    }

    // ── happy path: conservation ──────────────────────────────────────────────────

    @Test
    void happyTransfer_writesTwoConservingLegs() {
        insertAccount(1, "PERSONAL", OWNER, "Src");
        insertAccount(2, "PERSONAL", null, "Dst");
        seedBalance(1, "1000.00");
        seedBalance(2, "0.00");

        TransferResponse r = transferService.transfer(
                token(1), new TransferRequest(null, 2L, "150.00", "pay"), null);

        assertThat(r.fromAccountId()).isEqualTo(1L);
        assertThat(r.toAccountId()).isEqualTo(2L);
        // Exactly one txn, two postings, and they sum to zero (money is conserved).
        assertThat(rowCount("ledger_txns")).isEqualTo(1);
        assertThat(rowCount("ledger_postings")).isEqualTo(2);
        assertThat(sumPostings(1)).isEqualByComparingTo("-150.00");
        assertThat(sumPostings(2)).isEqualByComparingTo("150.00");
        assertThat(sumPostings(1).add(sumPostings(2))).isEqualByComparingTo("0.00");
    }

    // ── SELF_TRANSFER ─────────────────────────────────────────────────────────────

    @Test
    void selfTransfer_rejected_noLedgerWritten() {
        insertAccount(1, "PERSONAL", OWNER, "Src");
        seedBalance(1, "1000.00");

        ApiException ex = expect(1, 1, "10.00");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("SELF_TRANSFER");
        assertThat(rowCount("ledger_txns")).isZero();
        assertThat(rowCount("ledger_postings")).isZero();
    }

    // ── archived accounts ─────────────────────────────────────────────────────────

    @Test
    void archivedSource_404_noLedgerWritten() {
        insertAccount(1, "PERSONAL", OWNER, "Src");
        insertAccount(2, "PERSONAL", null, "Dst");
        seedBalance(1, "1000.00");
        seedBalance(2, "0.00");
        exec("UPDATE accounts SET is_archived = 1 WHERE account_id = 1", ps -> {});

        ApiException ex = expect(1, 2, "10.00");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(rowCount("ledger_txns")).isZero();
    }

    @Test
    void archivedDestination_404_noLedgerWritten() {
        insertAccount(1, "PERSONAL", OWNER, "Src");
        insertAccount(2, "PERSONAL", null, "Dst");
        seedBalance(1, "1000.00");
        seedBalance(2, "0.00");
        exec("UPDATE accounts SET is_archived = 1 WHERE account_id = 2", ps -> {});

        ApiException ex = expect(1, 2, "10.00");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(rowCount("ledger_txns")).isZero();
    }

    // ── AUTHORIZATION_REQUIRED ────────────────────────────────────────────────────

    @Test
    void sourceRequiresAuthorization_403_noLedgerWritten() {
        insertAccount(1, "PERSONAL", OWNER, "Src");
        insertAccount(2, "PERSONAL", null, "Dst");
        seedBalance(1, "1000.00");
        seedBalance(2, "0.00");
        exec("UPDATE accounts SET requires_authorization = 1 WHERE account_id = 1", ps -> {});

        ApiException ex = expect(1, 2, "10.00");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorCode()).isEqualTo("AUTHORIZATION_REQUIRED");
        assertThat(rowCount("ledger_txns")).isZero();
    }

    @Test
    void destinationRequiresAuthorization_403() {
        insertAccount(1, "PERSONAL", OWNER, "Src");
        insertAccount(2, "PERSONAL", null, "Dst");
        seedBalance(1, "1000.00");
        seedBalance(2, "0.00");
        exec("UPDATE accounts SET requires_authorization = 1 WHERE account_id = 2", ps -> {});

        ApiException ex = expect(1, 2, "10.00");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorCode()).isEqualTo("AUTHORIZATION_REQUIRED");
    }

    // ── overdraft floor (per-account) ─────────────────────────────────────────────

    @Test
    void noOverdraft_insufficientFunds_422() {
        // allow_overdraft defaults to 0 → floor 0; 50 balance cannot fund 60.
        insertAccount(1, "PERSONAL", OWNER, "Src");
        insertAccount(2, "PERSONAL", null, "Dst");
        seedBalance(1, "50.00");
        seedBalance(2, "0.00");

        ApiException ex = expect(1, 2, "60.00");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ex.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rowCount("ledger_txns")).isZero();
    }

    @Test
    void creditLimit_allowsOverdraftDownToFloor() {
        // allow_overdraft=1, credit_limit=100 → floor -100. From balance 0, a 100 debit
        // lands exactly on the floor and is allowed. (The embedded schema has no balance
        // trigger, so each transfer reads the seeded balance — assert the boundary directly.)
        insertAccount(1, "PERSONAL", OWNER, "Src");
        insertAccount(2, "PERSONAL", null, "Dst");
        seedBalance(1, "0.00");
        seedBalance(2, "0.00");
        exec("UPDATE accounts SET allow_overdraft = 1, credit_limit = 100.00 WHERE account_id = 1", ps -> {});

        TransferResponse ok = transferService.transfer(
                token(1), new TransferRequest(null, 2L, "100.00", "to floor"), null);
        assertThat(ok.txnId()).isPositive();
        assertThat(sumPostings(1)).isEqualByComparingTo("-100.00");
    }

    @Test
    void creditLimit_rejectsDebitBeyondFloor() {
        // Same floor (-100) but a starting balance of 0 and a 100.01 debit would land at
        // -100.01, one cent below the floor → INSUFFICIENT_FUNDS, nothing written.
        insertAccount(1, "PERSONAL", OWNER, "Src");
        insertAccount(2, "PERSONAL", null, "Dst");
        seedBalance(1, "0.00");
        seedBalance(2, "0.00");
        exec("UPDATE accounts SET allow_overdraft = 1, credit_limit = 100.00 WHERE account_id = 1", ps -> {});

        ApiException ex = expect(1, 2, "100.01");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ex.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rowCount("ledger_txns")).isZero();
    }

    @Test
    void systemAccount_ignoresFloor_faucet() {
        // SYSTEM source is an unlimited faucet even with allow_overdraft=0, balance 0.
        insertAccount(1, "SYSTEM", null, "Faucet");
        insertAccount(2, "PERSONAL", OWNER, "Dst");
        seedBalance(1, "0.00");
        seedBalance(2, "0.00");

        TransferResponse r = transferService.transfer(
                new VerifiedToken(1L, INITIATOR, "GOVERNMENT", 1L, null),
                new TransferRequest(null, 2L, "5000.00", "mint"), null);
        assertThat(sumPostings(1)).isEqualByComparingTo("-5000.00");
        assertThat(sumPostings(2)).isEqualByComparingTo("5000.00");
    }
}
