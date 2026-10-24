package io.paradaux.treasuryrestapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.paradaux.treasuryrestapi.dto.AccountBalanceResponse;
import io.paradaux.treasuryrestapi.dto.AccountByPlayerResponse;
import io.paradaux.treasuryrestapi.dto.BaltopEntry;
import io.paradaux.treasuryrestapi.dto.BaltopResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.AccountMapper;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Integration tests for {@link AccountService} (finding treasury-rest-api/testing/0004):
 * getBalance (present / zero-default via the account_balances view / not-found),
 * resolvePlayerAccount by uuid and name, the exactly-one-of guard, and the PAR-144
 * player↔GOVERNMENT collision rejection, and getBaltop (PAR-335). Drives the
 * real service + mappers + SQL against the embedded MariaDB.
 */
class AccountServiceIT extends EmbeddedDbIT {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountMapper accountMapper;

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID THIRD = UUID.fromString("33333333-3333-3333-3333-333333333333");

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
        ApiException ex = catchThrowableOfType(
                ApiException.class, () -> accountService.resolvePlayerAccount(PLAYER.toString(), "Steve"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_QUERY");
    }

    @Test
    void resolve_neither_400() {
        ApiException ex =
                catchThrowableOfType(ApiException.class, () -> accountService.resolvePlayerAccount(null, "  "));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_QUERY");
    }

    @Test
    void resolve_malformedUuid_400() {
        ApiException ex =
                catchThrowableOfType(ApiException.class, () -> accountService.resolvePlayerAccount("not-a-uuid", null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_QUERY");
    }

    @Test
    void resolveByName_unknownPlayer_404() {
        ApiException ex =
                catchThrowableOfType(ApiException.class, () -> accountService.resolvePlayerAccount(null, "Ghost"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("PLAYER_NOT_FOUND");
    }

    @Test
    void resolve_playerWithoutPersonalAccount_404() {
        insertPlayer(PLAYER, "Steve"); // known player, but no PERSONAL account
        ApiException ex = catchThrowableOfType(
                ApiException.class, () -> accountService.resolvePlayerAccount(PLAYER.toString(), null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void resolveByName_rejectedGovernmentCollisionLeavesThePersonalTargetUnchanged() {
        insertPlayer(PLAYER, "Treasury");
        insertAccount(1, "PERSONAL", PLAYER, "Treasury");
        assertThatThrownBy(() -> insertAccount(2, "GOVERNMENT", null, "Treasury"))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("GLOBAL_NAME_CONFLICT");
        assertThat(accountService.resolvePlayerAccount(null, "Treasury").accountId())
                .isEqualTo(1L);
    }

    @Test
    void resolveByUuid_rejectedGovernmentCollisionDoesNotChangeTheWallet() {
        insertPlayer(PLAYER, "Treasury");
        insertAccount(1, "PERSONAL", PLAYER, "Treasury");
        assertThatThrownBy(() -> insertAccount(2, "GOVERNMENT", null, "Treasury"))
                .isInstanceOf(RuntimeException.class);
        AccountByPlayerResponse r = accountService.resolvePlayerAccount(PLAYER.toString(), null);
        assertThat(r.accountId()).isEqualTo(1L);
    }

    // ── getBaltop ─────────────────────────────────────────────────────────────────

    @Test
    void getBaltop_ranksRichestFirst() {
        insertAccount(1, "PERSONAL", PLAYER, "Steve");
        insertAccount(2, "PERSONAL", OTHER, "OldAlex");
        insertPlayer(OTHER, "Alex");
        seedBalance(1, "50.00");
        seedBalance(2, "900.00");

        BaltopResponse r = accountService.getBaltop(1, 10);
        assertThat(r.totalItems()).isEqualTo(2L);
        assertThat(r.items()).extracting(BaltopEntry::playerName).containsExactly("Alex", "Steve");
        assertThat(r.items()).extracting(BaltopEntry::balance).containsExactly("900.00", "50.00");
        assertThat(r.items()).extracting(BaltopEntry::rank).containsExactly(1L, 2L);
    }

    @Test
    void getBaltop_onlyActivePersonalAccounts() {
        insertAccount(1, "PERSONAL", PLAYER, "Steve");
        insertAccount(2, "GOVERNMENT", null, "Treasury");
        insertAccount(3, "PERSONAL", OTHER, "Alex");
        seedBalance(1, "10.00");
        seedBalance(2, "1000000.00");
        seedBalance(3, "99.00");
        accountMapper.archiveAccount(3);

        BaltopResponse r = accountService.getBaltop(1, 10);
        assertThat(r.items()).extracting(BaltopEntry::accountId).containsExactly(1L);
    }

    @Test
    void getBaltop_tieAcrossPages_ordersByAccountIdAndContinuesRank() {
        insertAccount(1, "PERSONAL", PLAYER, "Steve");
        insertAccount(2, "PERSONAL", OTHER, "Alex");
        insertAccount(3, "PERSONAL", THIRD, "Notch");
        seedBalance(1, "100.00");
        seedBalance(2, "100.00");
        seedBalance(3, "100.00");

        assertThat(accountService.getBaltop(1, 2).items())
                .extracting(BaltopEntry::accountId)
                .containsExactly(1L, 2L);

        BaltopResponse r = accountService.getBaltop(2, 2);
        assertThat(r.totalPages()).isEqualTo(2);
        assertThat(r.items()).extracting(BaltopEntry::accountId).containsExactly(3L);
        assertThat(r.items()).extracting(BaltopEntry::rank).containsExactly(3L);
    }

    @Test
    void getBaltop_badPageOrLimit_400() {
        ApiException page = catchThrowableOfType(ApiException.class, () -> accountService.getBaltop(0, 10));
        assertThat(page.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(page.getErrorCode()).isEqualTo("INVALID_PARAM");

        ApiException limit = catchThrowableOfType(ApiException.class, () -> accountService.getBaltop(1, 101));
        assertThat(limit.getErrorCode()).isEqualTo("INVALID_PARAM");
    }
}
