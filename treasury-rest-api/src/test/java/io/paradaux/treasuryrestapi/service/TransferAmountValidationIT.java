package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.TransferRequest;
import io.paradaux.treasuryrestapi.dto.TransferResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Adversarial coverage for the transfer {@code amount} bound. The headline case is a
 * crafted huge-magnitude / huge-scale {@link BigDecimal} (e.g. {@code "1E1000000000"}):
 * cheap to build, it slips past a naive {@code > 0} check but explodes into a
 * multi-gigabyte BigInteger/String the moment the overdraft subtract or response
 * serialisation rescales it — a single-request OOM DoS. Validation must reject it as a
 * fast 400 with no ledger writes, while a legitimate DECIMAL(19,2)-range amount must
 * still pass through to the funds check.
 */
class TransferAmountValidationIT extends EmbeddedDbIT {

    @Autowired
    private TransferService transferService;

    private static final UUID SRC_OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DST_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private VerifiedToken token() {
        return new VerifiedToken(1L, SRC_OWNER, "PERSONAL", 1L, null);
    }

    private ApiException reject(String amount) {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> transferService.transfer(token(), new TransferRequest(null, 2L, amount, null), null));
        assertThat(ex).as("amount '%s' should be rejected", amount).isNotNull();
        return ex;
    }

    private void assertInvalidAmount(String amount) {
        ApiException ex = reject(amount);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_AMOUNT");
    }

    // ── the OOM vector and its siblings ─────────────────────────────────────────

    @Test
    void hugeScientificMagnitude_rejectedAsInvalidAmount_withNoLedgerWrites() {
        assertInvalidAmount("1E1000000000");
        // The whole point: rejected before any arithmetic — nothing is written.
        assertThat(rowCount("ledger_txns")).isZero();
        assertThat(rowCount("ledger_postings")).isZero();
    }

    @Test
    void hugeNegativeScale_rejected() {
        assertInvalidAmount("9E999999999");
    }

    @Test
    void hugePositiveScale_rejected() {
        assertInvalidAmount("1E-1000000000");
    }

    @Test
    void overlongDigitString_rejectedBeforeParse() {
        assertInvalidAmount("9".repeat(100));
    }

    // ── DECIMAL(19,2) boundary ──────────────────────────────────────────────────

    @Test
    void moreThanTwoDecimalPlaces_rejected() {
        assertInvalidAmount("1.234");
    }

    @Test
    void exceedsMaxIntegerDigits_rejected() {
        // 1e17 = 18 integer digits; DECIMAL(19,2) allows at most 17.
        assertInvalidAmount("100000000000000000.00");
    }

    @Test
    void nonPositiveAndUnparseable_rejected() {
        assertInvalidAmount("0");
        assertInvalidAmount("0.00");
        assertInvalidAmount("-5.00");
        assertInvalidAmount("");
        assertInvalidAmount("abc");
        assertInvalidAmount("NaN");
        assertInvalidAmount("Infinity");
        assertInvalidAmount("0x10");
    }

    // ── legitimate values still flow through ────────────────────────────────────

    @Test
    void maxInRangeAmount_passesValidationAndReachesFundsCheck() {
        insertAccount(1, "PERSONAL", SRC_OWNER, "src");
        insertAccount(2, "PERSONAL", DST_OWNER, "dst");
        seedBalance(1, "100.00");
        seedBalance(2, "0.00");

        // The DECIMAL(19,2) maximum must NOT be an INVALID_AMOUNT — with a small,
        // no-overdraft balance it should fall through to the funds check (422).
        ApiException ex = reject("99999999999999999.99");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ex.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void trailingZerosBeyondTwoDecimals_normalisedAndAccepted() {
        insertAccount(1, "PERSONAL", SRC_OWNER, "src");
        insertAccount(2, "PERSONAL", DST_OWNER, "dst");
        seedBalance(1, "100.00");
        seedBalance(2, "0.00");

        TransferResponse resp = transferService.transfer(
                token(), new TransferRequest(null, 2L, "10.000", "trailing zeros"), null);

        assertThat(new BigDecimal(resp.amount())).isEqualByComparingTo("10.00");
        assertThat(sumPostings(1)).isEqualByComparingTo("-10.00");
        assertThat(sumPostings(2)).isEqualByComparingTo("10.00");
    }
}
