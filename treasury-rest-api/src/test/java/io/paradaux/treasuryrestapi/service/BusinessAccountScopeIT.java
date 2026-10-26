package io.paradaux.treasuryrestapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.paradaux.treasuryrestapi.dto.FirmUpdateRequest;
import io.paradaux.treasuryrestapi.dto.TransferRequest;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** PAR-448: a business subaccount credential must never inherit sibling-account authority. */
class BusinessAccountScopeIT extends EmbeddedDbIT {
    @Autowired
    private TransferService transfers;

    @Autowired
    private TransactionService transactions;

    @Autowired
    private FirmService firms;

    @Autowired
    private AuthService auth;

    @Autowired
    private io.paradaux.treasuryrestapi.security.JwtTokenVerifier verifier;

    private final VerifiedToken scoped = new VerifiedToken(1L, UUID.randomUUID(), "BUSINESS", 2L, 10L);
    private final VerifiedToken wholeFirm = new VerifiedToken(2L, scoped.ownerUuid(), "BUSINESS", null, 10L);

    @BeforeEach
    void seedFirm() {
        insertAccount(1, "BUSINESS", null, "Main");
        insertAccount(2, "BUSINESS", null, "Reserve");
        insertAccount(3, "PERSONAL", null, "Recipient");
        insertFirm(10, "Acme", scoped.ownerUuid(), 1);
        linkFirmAccount(10, 1);
        linkFirmAccount(10, 2);
        seedBalance(1, "100.00");
        seedBalance(2, "100.00");
        seedBalance(3, "0.00");
    }

    @Test
    void restRotationRetainsPersistedAccountScope() {
        insertApiKey(
                1,
                "BUSINESS",
                scoped.ownerUuid(),
                "old-jti",
                false,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(30));
        exec("UPDATE api_keys SET firm_id = 10, account_id = 2 WHERE key_id = 1", ps -> {});
        var rotated = verifier.verify(auth.rotateToken(scoped).token());
        assertThat(rotated.accountId()).isEqualTo(2L);
        assertThat(rotated.firmId()).isEqualTo(10L);
        forbidden(() -> transfers.transfer(rotated, new TransferRequest(1L, 3L, "12.00", "denied"), null));
    }

    @Test
    void subaccountCanTransferAndReadItsHistory() {
        var result = transfers.transfer(scoped, new TransferRequest(2L, 3L, "12.00", "scoped"), null);
        assertThat(result.fromAccountId()).isEqualTo(2L);
        assertThat(matBalance(1)).isEqualByComparingTo("100.00");
        assertThat(matBalance(2)).isEqualByComparingTo("88.00");
        assertThat(matBalance(3)).isEqualByComparingTo("12.00");
        assertThat(transactions.getTransactions(scoped, 2, 1, 10).items()).hasSize(1);
        assertThat(transactions.getTransactionFeed(scoped, 2, 0, 10).accountId())
                .isEqualTo(2L);
    }

    @Test
    void siblingAccessIsDeniedWithoutMovingMoney() {
        forbidden(() -> transfers.transfer(scoped, new TransferRequest(1L, 3L, "12.00", "denied"), null));
        forbidden(() -> transactions.getTransactions(scoped, 1, 1, 10));
        forbidden(() -> transactions.getTransactionFeed(scoped, 1, 0, 10));
        forbidden(() -> firms.updateAccountDisplayName(scoped, 1, "Changed"));
        assertThat(rowCount("ledger_txns")).isZero();
        assertThat(matBalance(1)).isEqualByComparingTo("100.00");
        assertThat(firms.listAccounts(wholeFirm))
                .extracting(a -> a.displayName())
                .contains("Main");
    }

    @Test
    void listingAndManagementStayWithinAccountScope() {
        assertThat(firms.listAccounts(scoped)).extracting(a -> a.accountId()).containsExactly(2L);
        assertThat(firms.updateAccountDisplayName(scoped, 2, "Savings").displayName())
                .isEqualTo("Savings");
        forbidden(() -> firms.updateFirm(scoped, new FirmUpdateRequest("https://example.com", null)));
        forbidden(() -> firms.listEmployees(scoped));
        forbidden(() -> firms.listRoles(scoped));
        assertThat(firms.getFirm(scoped).displayName()).isEqualTo("Acme");
    }

    @Test
    void removedLinkRevokesAccountAccess() {
        exec("UPDATE firm_accounts SET removed_at = CURRENT_TIMESTAMP WHERE account_id = 2", ps -> {});
        forbidden(() -> transfers.transfer(scoped, new TransferRequest(2L, 3L, "12.00", "denied"), null));
        forbidden(() -> transactions.getTransactions(scoped, 2, 1, 10));
        assertThat(firms.listAccounts(scoped)).isEmpty();
        assertThat(rowCount("ledger_txns")).isZero();
    }

    @Test
    void legacyFirmWideKeyStillAccessesBothAccounts() {
        assertThat(firms.listAccounts(wholeFirm)).extracting(a -> a.accountId()).containsExactlyInAnyOrder(1L, 2L);
        transfers.transfer(wholeFirm, new TransferRequest(1L, 2L, "12.00", "legacy"), null);
        assertThat(transactions.getTransactions(wholeFirm, 1, 1, 10).items()).hasSize(1);
        assertThat(transactions.getTransactions(wholeFirm, 2, 1, 10).items()).hasSize(1);
    }

    private static void forbidden(Runnable action) {
        ApiException error = catchThrowableOfType(ApiException.class, action::run);
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
