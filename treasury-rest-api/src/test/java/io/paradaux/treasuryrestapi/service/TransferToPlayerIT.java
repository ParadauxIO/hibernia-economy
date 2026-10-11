package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.PlayerTransferRequest;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * End-to-end integration tests for {@code POST /api/v1/transfers/to-player}, driving
 * the real {@link TransferService} (resolution → mappers → SQL → ledger writes)
 * against the embedded MariaDB. Covers UUID and name resolution plus every
 * resolution/validation failure path. The HTTP/auth shell is an unmodified copy of
 * the {@code /transfers/to-firm} endpoint, so the value under test is the new
 * recipient resolution and its delegation into the shared transfer pipeline.
 */
class TransferToPlayerIT extends EmbeddedDbIT {

    @Autowired
    private TransferService transferService;

    private static final UUID PAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYEE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private VerifiedToken personalToken(long accountId, UUID owner) {
        return new VerifiedToken(1L, owner, "PERSONAL", accountId, null);
    }

    // seedBalance(int, String) and sumPostings(int) are inherited from EmbeddedDbIT.

    // ── happy paths ─────────────────────────────────────────────────────────────

    @Test
    void payByUuid_resolvesToPersonalAccountAndPosts() {
        insertAccount(1, "PERSONAL", PAYER, PAYER.toString());
        insertAccount(2, "PERSONAL", PAYEE, PAYEE.toString());
        seedBalance(1, "1000.00");
        seedBalance(2, "0.00");

        TransferResponse resp = transferService.transferToPlayer(
                personalToken(1, PAYER),
                new PlayerTransferRequest(null, PAYEE.toString(), null, "100.00", "by uuid"),
                null);

        assertThat(resp.fromAccountId()).isEqualTo(1L);
        assertThat(resp.toAccountId()).isEqualTo(2L);
        assertThat(new BigDecimal(resp.amount())).isEqualByComparingTo("100.00");
        assertThat(sumPostings(1)).isEqualByComparingTo("-100.00");
        assertThat(sumPostings(2)).isEqualByComparingTo("100.00");
    }

    @Test
    void payByName_isCaseInsensitiveAndResolvesViaFirmPlayers() {
        insertAccount(1, "PERSONAL", PAYER, PAYER.toString());
        insertAccount(2, "PERSONAL", PAYEE, PAYEE.toString());
        insertPlayer(PAYEE, "Steve");
        seedBalance(1, "1000.00");
        seedBalance(2, "0.00");

        TransferResponse resp = transferService.transferToPlayer(
                personalToken(1, PAYER),
                new PlayerTransferRequest(null, null, "steve", "50.00", "by name"),
                null);

        assertThat(resp.toAccountId()).isEqualTo(2L);
        assertThat(sumPostings(2)).isEqualByComparingTo("50.00");
    }

    // ── validation / resolution failures ────────────────────────────────────────

    @Test
    void bothIdentifiers_rejectedAsInvalidBody() {
        ApiException ex = expectApiException(new PlayerTransferRequest(
                null, PAYEE.toString(), "Steve", "1.00", null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_BODY");
    }

    @Test
    void neitherIdentifier_rejectedAsInvalidBody() {
        ApiException ex = expectApiException(new PlayerTransferRequest(
                null, null, null, "1.00", null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_BODY");
    }

    @Test
    void malformedUuid_rejectedAsInvalidBody() {
        ApiException ex = expectApiException(new PlayerTransferRequest(
                null, "not-a-uuid", null, "1.00", null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_BODY");
    }

    @Test
    void unknownName_404PlayerNotFound() {
        ApiException ex = expectApiException(new PlayerTransferRequest(
                null, null, "Ghost", "1.00", null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("PLAYER_NOT_FOUND");
    }

    @Test
    void playerWithoutPersonalAccount_404AccountNotFound() {
        insertAccount(1, "PERSONAL", PAYER, PAYER.toString());
        seedBalance(1, "1000.00");
        // PAYEE has no accounts row at all.
        ApiException ex = expectApiException(new PlayerTransferRequest(
                null, PAYEE.toString(), null, "1.00", null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void payByName_playerAndGovernmentSameName_409Ambiguous_movesNoMoney() {
        // PAR-144 parity with GET /accounts/by-player: governments run as player alts
        // of the same name, so a bare name that is both must not silently route the
        // payment to the personal account.
        insertAccount(1, "PERSONAL", PAYER, PAYER.toString());
        insertAccount(2, "PERSONAL", PAYEE, "Treasury");
        insertAccount(3, "GOVERNMENT", null, "Treasury");
        insertPlayer(PAYEE, "Treasury");
        seedBalance(1, "1000.00");
        seedBalance(2, "0.00");
        seedBalance(3, "0.00");

        ApiException ex = expectApiException(new PlayerTransferRequest(
                null, null, "treasury", "25.00", null));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("AMBIGUOUS_NAME");
        assertThat(rowCount("ledger_txns")).isZero();
    }

    @Test
    void payByUuid_notBlockedByGovernmentNameCollision() {
        // The guard is bare-name only: a UUID names the player unambiguously.
        insertAccount(1, "PERSONAL", PAYER, PAYER.toString());
        insertAccount(2, "PERSONAL", PAYEE, "Treasury");
        insertAccount(3, "GOVERNMENT", null, "Treasury");
        insertPlayer(PAYEE, "Treasury");
        seedBalance(1, "1000.00");
        seedBalance(2, "0.00");
        seedBalance(3, "0.00");

        TransferResponse resp = transferService.transferToPlayer(
                personalToken(1, PAYER),
                new PlayerTransferRequest(null, PAYEE.toString(), null, "25.00", null),
                null);

        assertThat(resp.toAccountId()).isEqualTo(2L);
    }

    private ApiException expectApiException(PlayerTransferRequest request) {
        Throwable t = catchThrowable(() ->
                transferService.transferToPlayer(personalToken(1, PAYER), request, null));
        assertThat(t).isInstanceOf(ApiException.class);
        return (ApiException) t;
    }
}
