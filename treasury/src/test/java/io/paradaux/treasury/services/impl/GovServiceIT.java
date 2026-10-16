package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.api.exceptions.FineAlreadyRevokedException;
import io.paradaux.treasury.api.exceptions.FineNotFoundException;
import io.paradaux.treasury.api.exceptions.GovAccountNotFoundException;
import io.paradaux.treasury.api.exceptions.InsufficientFineFundsException;
import io.paradaux.treasury.api.exceptions.PrimitiveAccountException;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.GovService;
import io.paradaux.treasury.mappers.GovernmentFineMapper;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovServiceIT extends IntegrationTestBase {

    private GovService govService;
    private AccountService accountService;
    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        govService     = injector.getInstance(GovService.class);
        accountService = injector.getInstance(AccountService.class);
        ledgerService  = injector.getInstance(LedgerService.class);
        ledgerService.bootstrapGovernmentAccounts();
    }

    // ---- Department accounts ----

    @Test
    void createDepartmentAccount_succeedsForUniqueName() {
        UUID admin = UUID.randomUUID();
        Account dept = govService.createDepartmentAccount("Treasury Office", admin);
        assertThat(dept.getDisplayName()).isEqualTo("Treasury Office");
        assertThat(accountService.getGovernmentAccountByName("Treasury Office")).isNotNull();
    }

    @Test
    void createDepartmentAccount_duplicateName_throws() {
        UUID admin = UUID.randomUUID();
        govService.createDepartmentAccount("Justice", admin);

        assertThatThrownBy(() -> govService.createDepartmentAccount("Justice", admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void archiveDepartmentAccount_marksRowArchived() {
        UUID admin = UUID.randomUUID();
        govService.createDepartmentAccount("Justice", admin);

        govService.archiveDepartmentAccount("Justice", admin);

        // listGovernmentAccounts only returns non-archived rows
        List<Account> remaining = govService.listGovernmentAccounts();
        assertThat(remaining).noneMatch(a -> "Justice".equals(a.getDisplayName()));
    }

    @Test
    void archiveDepartmentAccount_primitiveAccountIsRefused() {
        UUID admin = UUID.randomUUID();
        assertThatThrownBy(() -> govService.archiveDepartmentAccount("starting-balances", admin))
                .isInstanceOf(PrimitiveAccountException.class);
    }

    @Test
    void archiveDepartmentAccount_unknownName_throws() {
        UUID admin = UUID.randomUUID();
        assertThatThrownBy(() -> govService.archiveDepartmentAccount("DoesNotExist", admin))
                .isInstanceOf(GovAccountNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void listGovernmentAccounts_returnsBootstrappedPrimitives() {
        List<String> names = govService.listGovernmentAccounts().stream()
                .map(Account::getDisplayName)
                .toList();
        assertThat(names).contains("starting-balances", "DCGovernment", "GovernmentFines");
    }

    // ---- Fines ----

    @Test
    void issueFine_debitsPlayerAndRecordsFine() {
        UUID player = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        GovernmentFine fine = govService.issueFine(player, new BigDecimal("250.00"), "speeding", issuer);

        assertThat(fine.getFineId()).isPositive();
        assertThat(fine.getAmount()).isEqualByComparingTo("250.00");
        assertThat(fine.getReason()).isEqualTo("speeding");
        assertThat(fine.getIssuedBy()).isEqualTo(issuer);
        assertThat(fine.isRevoked()).isFalse();
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9750.00");
    }

    @Test
    void issueFine_intoSpecificAccount_routesMoneyAndRecordsThatAccount() {
        UUID player = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account police = govService.createDepartmentAccount("Police", issuer);

        GovernmentFine fine = govService.issueFine(
                player, police.getAccountId(), new BigDecimal("100.00"), "patrol fine", issuer);

        // The fine remembers the specified account, not the default fines account.
        assertThat(fine.getGovAccountId()).isEqualTo(police.getAccountId());
        // Money flowed player -> Police, leaving the default GovernmentFines untouched.
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9900.00");
        assertThat(accountService.getBalanceReadOnly(police.getAccountId())).isEqualByComparingTo("100.00");
        Account defaultFines = accountService.getGovernmentAccountByName("GovernmentFines");
        assertThat(accountService.getBalanceReadOnly(defaultFines.getAccountId())).isEqualByComparingTo("0.00");
    }

    @Test
    void issueFine_unknownAccountId_throwsGovAccountNotFound() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        assertThatThrownBy(() ->
                govService.issueFine(player, 999_999, new BigDecimal("10.00"), "x", UUID.randomUUID()))
                .isInstanceOf(GovAccountNotFoundException.class);
    }

    @Test
    void issueFine_intoANonGovernmentAccount_isRefused_andMovesNothing() {
        // The destination of a fine must be a GOVERNMENT account: otherwise a
        // fine becomes a way to move a player's money into any other player's or
        // firm's account, bypassing their authorization.
        UUID debtor = UUID.randomUUID();
        UUID beneficiary = UUID.randomUUID();
        Account debtorAccount = ledgerService.resolveOrCreatePersonal(debtor);
        Account personal = ledgerService.resolveOrCreatePersonal(beneficiary);
        Account firm = accountService.createAccount(
                io.paradaux.treasury.model.economy.AccountType.BUSINESS, UUID.randomUUID(), "Shell Co");
        BigDecimal debtorBefore = accountService.getBalanceReadOnly(debtorAccount.getAccountId());
        BigDecimal personalBefore = accountService.getBalanceReadOnly(personal.getAccountId());

        assertThatThrownBy(() -> govService.issueFine(
                debtor, personal.getAccountId(), new BigDecimal("10.00"), "x", UUID.randomUUID()))
                .isInstanceOf(GovAccountNotFoundException.class);
        assertThatThrownBy(() -> govService.issueFine(
                debtorAccount.getAccountId(), firm.getAccountId(), new BigDecimal("10.00"), "x", UUID.randomUUID()))
                .isInstanceOf(GovAccountNotFoundException.class);

        assertThat(accountService.getBalanceReadOnly(debtorAccount.getAccountId())).isEqualByComparingTo(debtorBefore);
        assertThat(accountService.getBalanceReadOnly(personal.getAccountId())).isEqualByComparingTo(personalBefore);
        assertThat(accountService.getBalanceReadOnly(firm.getAccountId())).isEqualByComparingTo("0.00");
    }

    @Test
    void revokeFine_refundsFromTheFinesOwnAccount() {
        UUID player = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account police = govService.createDepartmentAccount("Police", issuer);

        GovernmentFine fine = govService.issueFine(
                player, police.getAccountId(), new BigDecimal("100.00"), "patrol fine", issuer);
        govService.revokeFine(fine.getFineId(), issuer);

        // Refund comes back out of the same account the fine was paid into.
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10000.00");
        assertThat(accountService.getBalanceReadOnly(police.getAccountId())).isEqualByComparingTo("0.00");
    }

    // ---- Firm (BUSINESS account) fines (PAR-48) ----

    @Test
    void issueFine_againstFirmAccount_debitsFirmAndRecordsNoPlayer() {
        UUID issuer = UUID.randomUUID();
        UUID funder = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(funder);

        Account firm = accountService.createAccount(
                io.paradaux.treasury.model.economy.AccountType.BUSINESS, UUID.randomUUID(), "Acme Ltd");
        Account police = govService.createDepartmentAccount("Police", issuer);
        fund(funder, firm.getAccountId(), new BigDecimal("500.00"));

        GovernmentFine fine = govService.issueFine(
                firm.getAccountId(), police.getAccountId(), new BigDecimal("200.00"), "illegal dumping", issuer);

        // Firm fines record the debtor account, not a player.
        assertThat(fine.getPlayerUuid()).isNull();
        assertThat(fine.getDebtorAccountId()).isEqualTo(firm.getAccountId());
        assertThat(fine.getGovAccountId()).isEqualTo(police.getAccountId());
        assertThat(accountService.getBalanceReadOnly(firm.getAccountId())).isEqualByComparingTo("300.00");
        assertThat(accountService.getBalanceReadOnly(police.getAccountId())).isEqualByComparingTo("200.00");
    }

    @Test
    void issueFine_againstFirmAccount_insufficientFunds_throwsTypedException() {
        UUID issuer = UUID.randomUUID();
        Account firm = accountService.createAccount(
                io.paradaux.treasury.model.economy.AccountType.BUSINESS, UUID.randomUUID(), "Broke Inc");
        Account police = govService.createDepartmentAccount("Police", issuer);

        assertThatThrownBy(() -> govService.issueFine(
                firm.getAccountId(), police.getAccountId(), new BigDecimal("50.00"), "x", issuer))
                .isInstanceOf(InsufficientFineFundsException.class);
    }

    @Test
    void issueFine_unknownDebtorAccount_throwsIllegalArgument() {
        UUID issuer = UUID.randomUUID();
        Account police = govService.createDepartmentAccount("Police", issuer);

        assertThatThrownBy(() -> govService.issueFine(
                999_999, police.getAccountId(), new BigDecimal("10.00"), "x", issuer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No account found");
    }

    @Test
    void revokeFine_firmFine_refundsTheFirmAccount() {
        UUID issuer = UUID.randomUUID();
        UUID funder = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(funder);
        Account firm = accountService.createAccount(
                io.paradaux.treasury.model.economy.AccountType.BUSINESS, UUID.randomUUID(), "Acme Ltd");
        Account police = govService.createDepartmentAccount("Police", issuer);
        fund(funder, firm.getAccountId(), new BigDecimal("500.00"));

        GovernmentFine fine = govService.issueFine(
                firm.getAccountId(), police.getAccountId(), new BigDecimal("200.00"), "dumping", issuer);
        govService.revokeFine(fine.getFineId(), issuer);

        // Refund returns to the firm account; the gov account is emptied again.
        assertThat(accountService.getBalanceReadOnly(firm.getAccountId())).isEqualByComparingTo("500.00");
        assertThat(accountService.getBalanceReadOnly(police.getAccountId())).isEqualByComparingTo("0.00");
    }

    /** Moves {@code amount} from a player's personal account into another account, for funding test fixtures. */
    private void fund(UUID fromPlayer, int toAccountId, BigDecimal amount) {
        int fromId = accountService.getAccountByUUID(fromPlayer).getAccountId();
        ledgerService.transfer(new io.paradaux.treasury.model.economy.TransferRequest(
                fromId, toAccountId, amount, "test funding", fromPlayer, null,
                io.paradaux.treasury.utils.TreasuryConstants.TREASURY_PLUGIN_NAME, null));
    }

    @Test
    void issueFine_unknownPlayer_throws() {
        UUID nobody = UUID.randomUUID();
        assertThatThrownBy(() -> govService.issueFine(nobody, BigDecimal.TEN, "x", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No personal account");
    }

    @Test
    void issueFine_insufficientFunds_throwsTypedException() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        // Drain the player's account by overdrafting via /eco take (admin set
        // to 0) so the fine can't be debited. Service wraps the ledger's
        // IllegalStateException as InsufficientFineFundsException.
        ledgerService.adminSet(player, BigDecimal.ZERO, "test setup", UUID.randomUUID());

        assertThatThrownBy(() ->
                govService.issueFine(player, new BigDecimal("100.00"), "x", UUID.randomUUID()))
                .isInstanceOf(InsufficientFineFundsException.class);
    }

    @Test
    void issueFine_amountMustBePositive() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        assertThatThrownBy(() -> govService.issueFine(player, BigDecimal.ZERO, "x", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Validation-vs-"unknown player" regression tests ----
    // Live-server bug 2026-05-16: an admin tried `/fine issue <p> 0.001 …`,
    // `0.0001 …`, etc. against a player whose balance was already near zero.
    // The chat reply was "That player has never joined this server." — but the
    // target was online at the time. Root cause: Money.requireValidAmount
    // throws IllegalArgumentException for sub-cent / over-precise inputs, and
    // FineCommand catches IllegalArgumentException indiscriminately, routing
    // every validation failure to treasury.general.unknown-player. These tests
    // pin the *real* exception classes/messages at the service layer so the
    // command-layer fix can be written against typed conditions.
    //
    // Companion bug at the parser layer: Hibernia's BigDecimalResolver doesn't
    // strip commas, so "19,993" fails to parse and surfaces the same misleading
    // message. That one is below the service boundary — captured by a unit test
    // in hibernia-framework rather than here.

    @Test
    void issueFine_amountWithMoreThanTwoDecimalPlaces_isRejectedWithSpecificMessage() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        assertThatThrownBy(() ->
                govService.issueFine(player, new BigDecimal("0.001"), "x", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 2 decimal places");

        assertThatThrownBy(() ->
                govService.issueFine(player, new BigDecimal("0.00000000001"), "x", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 2 decimal places");
    }

    @Test
    void issueFine_zeroAmount_failsPositivityCheckBeforeMinimumCheck() {
        // Documents the validation order: requirePositive (signum > 0) fires
        // before requireValidAmount (MINIMUM_AMOUNT ≥ 0.01). 0.00 therefore
        // surfaces as "fine amount > 0", not "at least 0.01". The MINIMUM_AMOUNT
        // branch in Money.requireValidAmount is effectively unreachable from
        // this caller (any value below 0.01 either has signum 0 or scale > 2),
        // which is worth knowing if Money's checks are ever reordered.
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        assertThatThrownBy(() ->
                govService.issueFine(player, new BigDecimal("0.00"), "x", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fine amount > 0");
    }

    @Test
    void issueFine_negativeAmount_isRejected() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        assertThatThrownBy(() ->
                govService.issueFine(player, new BigDecimal("-50.00"), "x", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fine amount > 0");
    }

    @Test
    void issueFine_playerWithZeroBalance_throwsTypedInsufficientFunds_notIAE() {
        // The exact field-incident scenario: target's balance is 0, admin
        // issues a *valid* (≥ $0.01, 2dp) fine. The service should raise
        // InsufficientFineFundsException, NOT IllegalArgumentException — so the
        // command can produce the "insufficient funds" message and NOT the
        // "unknown player" message.
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        ledgerService.adminSet(player, BigDecimal.ZERO, "drain to zero", UUID.randomUUID());

        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("0.00");

        assertThatThrownBy(() ->
                govService.issueFine(player, new BigDecimal("0.01"), "any", UUID.randomUUID()))
                .isInstanceOf(InsufficientFineFundsException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issueFine_validAmountForKnownPlayer_doesNotThrowUnknownPlayerStyleIAE() {
        // Belt-and-suspenders for the catch-block in FineCommand: ensure the
        // ONLY IllegalArgumentException emitted on the happy path with a known
        // player is one whose message names amount validation — never one that
        // mentions "personal account". (Future maintainers reading the catch
        // comment in FineCommand should see this assertion fail loudly if they
        // ever rewire issueFine to throw IAE for non-validation reasons.)
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        // Sanity: valid fine succeeds, no exception.
        GovernmentFine fine = govService.issueFine(player, new BigDecimal("1.00"), "ok", UUID.randomUUID());
        assertThat(fine.getFineId()).isPositive();

        // Now an invalid amount: the message must be about the amount, not the
        // player. Today FineCommand maps this to "unknown-player" — that's the
        // bug. Service-layer message stays correct.
        assertThatThrownBy(() ->
                govService.issueFine(player, new BigDecimal("0.001"), "ok", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(t -> assertThat(t.getMessage()).doesNotContainIgnoringCase("personal account"));
    }

    @Test
    void revokeFine_creditsPlayerAndMarksFineRevoked() {
        UUID player = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        UUID revoker = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        GovernmentFine fine = govService.issueFine(player, new BigDecimal("100.00"), "wrongful", issuer);
        BigDecimal afterFine = accountService.getBalanceByOwnerUuid(player);

        GovernmentFine revoked = govService.revokeFine(fine.getFineId(), revoker);

        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.getRevokedBy()).isEqualTo(revoker);
        assertThat(accountService.getBalanceByOwnerUuid(player))
                .isEqualByComparingTo(afterFine.add(new BigDecimal("100.00")));
    }

    @Test
    void revokeFine_unknownFine_throws() {
        assertThatThrownBy(() -> govService.revokeFine(99999L, UUID.randomUUID()))
                .isInstanceOf(FineNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void revokeFine_alreadyRevoked_throws() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        GovernmentFine fine = govService.issueFine(player, BigDecimal.TEN, "x", UUID.randomUUID());
        govService.revokeFine(fine.getFineId(), UUID.randomUUID());

        assertThatThrownBy(() -> govService.revokeFine(fine.getFineId(), UUID.randomUUID()))
                .isInstanceOf(FineAlreadyRevokedException.class)
                .hasMessageContaining("already revoked");
    }

    @Test
    void issueFine_finesAccountMissing_throws() throws Exception {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        // Drop the fines account row to trigger the defensive check.
        io.paradaux.treasury.model.economy.Account fines =
                accountService.getGovernmentAccountByName("GovernmentFines");
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.execute("DELETE FROM accounts WHERE account_id = " + fines.getAccountId());
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        assertThatThrownBy(() ->
                govService.issueFine(player, BigDecimal.TEN, "x", UUID.randomUUID()))
                .isInstanceOf(GovAccountNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void revokeFine_playerAccountVanished_throws() throws Exception {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        GovernmentFine fine = govService.issueFine(player, new BigDecimal("10.00"), "x", UUID.randomUUID());

        // Drop the player's PERSONAL account row — the revoke flow looks it up by UUID.
        io.paradaux.treasury.model.economy.Account p = accountService.getAccountByUUID(player);
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.execute("DELETE FROM accounts WHERE account_id = " + p.getAccountId());
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        assertThatThrownBy(() -> govService.revokeFine(fine.getFineId(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Player account not found");
    }

    @Test
    void revokeFine_finesAccountVanished_throws() throws Exception {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        GovernmentFine fine = govService.issueFine(player, new BigDecimal("10.00"), "x", UUID.randomUUID());

        try (var conn = dataSource.getConnection();
             var st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.execute("DELETE FROM accounts WHERE account_id = " + fine.getGovAccountId());
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        assertThatThrownBy(() -> govService.revokeFine(fine.getFineId(), UUID.randomUUID()))
                .isInstanceOf(GovAccountNotFoundException.class)
                .hasMessageContaining("not found");
    }

    // ---- Audit-trail regression tests ----
    // Pre-fix the ledger txn for fine issue/revoke recorded
    // TreasuryConstants.VIRTUAL_TREASURY_INITIATOR (UUID(0,1)) — masking which
    // admin actually performed the action. Fixed in GovServiceImpl to record the
    // real issuedBy / revokedBy UUID. These tests pin that.

    @Test
    void issueFine_recordsRealAdminAsLedgerInitiator() {
        UUID admin  = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        GovernmentFine fine = govService.issueFine(player, new BigDecimal("25.00"), "speeding", admin);

        io.paradaux.treasury.model.economy.LedgerTxn txn =
                ledgerService.getTransaction(fine.getTxnId());
        assertThat(txn).isNotNull();
        assertThat(txn.getInitiatorUuid())
                .as("ledger initiator must be the admin who issued the fine")
                .isEqualTo(admin);
        assertThat(txn.getInitiatorUuid()).isNotEqualTo(
                io.paradaux.treasury.utils.TreasuryConstants.VIRTUAL_TREASURY_INITIATOR);
    }

    @Test
    void revokeFine_recordsRealRevokerAsLedgerInitiator() {
        UUID issuer  = UUID.randomUUID();
        UUID revoker = UUID.randomUUID();
        UUID player  = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        GovernmentFine fine = govService.issueFine(player, new BigDecimal("10.00"), "wrongful", issuer);
        GovernmentFine revoked = govService.revokeFine(fine.getFineId(), revoker);

        io.paradaux.treasury.model.economy.LedgerTxn revokeTxn =
                ledgerService.getTransaction(revoked.getRevokeTxnId());
        assertThat(revokeTxn).isNotNull();
        assertThat(revokeTxn.getInitiatorUuid())
                .as("ledger initiator must be the admin who revoked the fine")
                .isEqualTo(revoker);
        // And the original issue txn still attributes to the issuer (unchanged by revoke).
        io.paradaux.treasury.model.economy.LedgerTxn issueTxn =
                ledgerService.getTransaction(revoked.getTxnId());
        assertThat(issueTxn.getInitiatorUuid()).isEqualTo(issuer);
    }

    @Test
    void issueFine_distinctIssuersProduceDistinctLedgerInitiators() {
        UUID adminA = UUID.randomUUID();
        UUID adminB = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(playerA);
        ledgerService.resolveOrCreatePersonal(playerB);

        GovernmentFine fineA = govService.issueFine(playerA, BigDecimal.ONE, "x", adminA);
        GovernmentFine fineB = govService.issueFine(playerB, BigDecimal.ONE, "x", adminB);

        assertThat(ledgerService.getTransaction(fineA.getTxnId()).getInitiatorUuid()).isEqualTo(adminA);
        assertThat(ledgerService.getTransaction(fineB.getTxnId()).getInitiatorUuid()).isEqualTo(adminB);
    }

    @Test
    void getFine_andLists_returnExpectedRows() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        GovernmentFine f1 = govService.issueFine(player, new BigDecimal("10"), "a", UUID.randomUUID());
        GovernmentFine f2 = govService.issueFine(player, new BigDecimal("20"), "b", UUID.randomUUID());
        govService.revokeFine(f1.getFineId(), UUID.randomUUID());

        assertThat(govService.getFine(f2.getFineId())).isNotNull();
        assertThat(govService.getPlayerFines(player)).hasSize(2);
        assertThat(govService.getActivePlayerFines(player))
                .hasSize(1)
                .allMatch(f -> !f.isRevoked());
    }

    // ---- Double-submit: one fine row per ledger debit ----

    /** A GovService on a pinned clock, so two issues share the per-second dedup key. */
    private GovService govServiceAt(Instant now) {
        return new GovServiceImpl(accountService, ledgerService,
                injector.getInstance(GovernmentFineMapper.class),
                injector.getInstance(GovernmentConfiguration.class),
                injector.getInstance(PlayerDirectoryService.class),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private long countFines() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM government_fines")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void issueFine_doubleSubmitInTheSameSecond_recordsOneFineAndRefundsOnce() throws Exception {
        UUID player = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        GovService gov = govServiceAt(Instant.parse("2026-01-01T12:00:00.250Z"));

        GovernmentFine first  = gov.issueFine(player, new BigDecimal("250.00"), "speeding", issuer);
        GovernmentFine second = gov.issueFine(player, new BigDecimal("250.00"), "speeding", issuer);

        // The ledger collapsed the second debit; the fine must collapse with it.
        assertThat(second.getFineId()).isEqualTo(first.getFineId());
        assertThat(second.getTxnId()).isEqualTo(first.getTxnId());
        assertThat(countFines()).isEqualTo(1);
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9750.00");

        // One debit → exactly one refund. Before the fix the second row was revocable
        // too (the revoke dedup key is per fine id), refunding 500 for a 250 debit.
        gov.revokeFine(first.getFineId(), issuer);
        assertThatThrownBy(() -> gov.revokeFine(second.getFineId(), issuer))
                .isInstanceOf(FineAlreadyRevokedException.class);
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10000.00");
    }

    @Test
    void issueFine_sameSecondButDifferentReason_areTwoDistinctFines() throws Exception {
        UUID player = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        GovService gov = govServiceAt(Instant.parse("2026-01-01T12:00:00Z"));

        GovernmentFine a = gov.issueFine(player, new BigDecimal("100.00"), "speeding", issuer);
        GovernmentFine b = gov.issueFine(player, new BigDecimal("100.00"), "littering", issuer);

        assertThat(b.getFineId()).isNotEqualTo(a.getFineId());
        assertThat(countFines()).isEqualTo(2);
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9800.00");
    }

    @Test
    void governmentFines_rejectASecondRowForTheSameDebit() {
        // uq_fines_txn (V29) is the database backstop behind the service-level guard.
        UUID player = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        GovernmentFine fine = govService.issueFine(player, new BigDecimal("10.00"), "x", issuer);

        GovernmentFineMapper mapper = injector.getInstance(GovernmentFineMapper.class);
        GovernmentFine duplicate = GovernmentFine.builder()
                .playerUuid(player)
                .debtorAccountId(fine.getDebtorAccountId())
                .govAccountId(fine.getGovAccountId())
                .amount(fine.getAmount())
                .reason("x")
                .txnId(fine.getTxnId())
                .issuedBy(issuer)
                .build();

        assertThatThrownBy(() -> mapper.insertFine(duplicate))
                .hasMessageContaining("uq_fines_txn");
    }
}
