package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.AccountBalanceResponse;
import io.paradaux.treasuryrestapi.dto.AccountByPlayerResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Integration tests for {@link AccountService} (finding treasury-rest-api/testing/0004):
 * getBalance (present / zero-default via the account_balances view / not-found),
 * resolvePlayerAccount by uuid and name, the exactly-one-of guard, and the PAR-144
 * player↔GOVERNMENT AMBIGUOUS_NAME collision. Drives the real service + mappers +
 * SQL against the embedded MariaDB.
 */
class AccountServiceIT extends EmbeddedDbIT {

    @Autowired
    private AccountService accountService;

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // ── getBalance ────────────────────────────────────────────────────────────────

    @Test
    void getBalance_returnsSeededBalance() {
        insertAccount(1, "PERSONAL", PLAYER, "Steve");
        seedBalance(1, "1234.56");

        AccountBalanceResponse r = accountService.getBalance(1);
        assertThat(r.accountId()).isEqualTo(1L);
        assertThat(r.balance()).isEqualTo("1234.56");
    }

    @Test
    void getBalance_accountWithNoMatRow_defaultsToZeroViaView() {
        // account exists but no account_balances_mat row → the view COALESCEs to 0.00.
        insertAccount(1, "PERSONAL", PLAYER, "Steve");

        AccountBalanceResponse r = accountService.getBalance(1);
        assertThat(r.balance()).isEqualTo("0.00");
    }

    @Test
    void getBalance_unknownAccount_404() {
        ApiException ex = catchThrowableOfType(ApiException.class, () -> accountService.getBalance(999));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    // ── resolvePlayerAccount ───────────────────────────────────────────────────────

    @Test
    void resolveByUuid_returnsAccountAndName() {
        insertAccount(1, "PERSONAL", PLAYER, "Steve");
        insertPlayer(PLAYER, "Steve");

        AccountByPlayerResponse r = accountService.resolvePlayerAccount(PLAYER.toString(), null);
        assertThat(r.accountId()).isEqualTo(1L);
        assertThat(r.playerUuid()).isEqualTo(PLAYER.toString());
        assertThat(r.playerName()).isEqualTo("Steve");
    }

    @Test
    void resolveByName_isCaseInsensitive() {
        insertAccount(1, "PERSONAL", PLAYER, "Steve");
        insertPlayer(PLAYER, "Steve");

        AccountByPlayerResponse r = accountService.resolvePlayerAccount(null, "steve");
        assertThat(r.accountId()).isEqualTo(1L);
        assertThat(r.playerUuid()).isEqualTo(PLAYER.toString());
    }

    @Test
    void resolve_bothUuidAndName_400() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> accountService.resolvePlayerAccount(PLAYER.toString(), "Steve"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_QUERY");
    }

    @Test
    void resolve_neither_400() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> accountService.resolvePlayerAccount(null, "  "));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_QUERY");
    }

    @Test
    void resolve_malformedUuid_400() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> accountService.resolvePlayerAccount("not-a-uuid", null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_QUERY");
    }

    @Test
    void resolveByName_unknownPlayer_404() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> accountService.resolvePlayerAccount(null, "Ghost"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("PLAYER_NOT_FOUND");
    }

    @Test
    void resolve_playerWithoutPersonalAccount_404() {
        insertPlayer(PLAYER, "Steve"); // known player, but no PERSONAL account
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> accountService.resolvePlayerAccount(PLAYER.toString(), null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void resolveByName_playerAndGovernmentSameName_conflictAmbiguous() {
        // PAR-144: a bare name that is both a player and a non-archived GOVERNMENT
        // account must refuse resolution rather than silently return the personal one.
        insertPlayer(PLAYER, "Treasury");
        insertAccount(1, "PERSONAL", PLAYER, "Treasury");
        insertAccount(2, "GOVERNMENT", null, "Treasury");

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> accountService.resolvePlayerAccount(null, "Treasury"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("AMBIGUOUS_NAME");
    }

    @Test
    void resolveByUuid_notBlockedByGovernmentCollision() {
        // The collision guard is bare-name only; a UUID query resolves cleanly even
        // when a same-named GOVERNMENT account exists.
        insertPlayer(PLAYER, "Treasury");
        insertAccount(1, "PERSONAL", PLAYER, "Treasury");
        insertAccount(2, "GOVERNMENT", null, "Treasury");

        AccountByPlayerResponse r = accountService.resolvePlayerAccount(PLAYER.toString(), null);
        assertThat(r.accountId()).isEqualTo(1L);
    }
}
