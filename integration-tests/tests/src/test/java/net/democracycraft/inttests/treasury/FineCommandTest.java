package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Treasury's {@code /fine} command — issue, revoke, list, info.
 *
 * <p>A fine is paid into a named government account:
 * {@code /fine issue <account> <player> <amount> <reason>}.
 *
 * <p>{@code treasury.gov.fine.*} permissions are {@code default: false}, so
 * tests use {@link Ops#run} which grants {@code treasury.*} via LuckPerms.
 */
class FineCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * {@code /fine issue <account> <player> <amount> <reason>} names the government
     * account the fine is paid into; this is the configured default fines account
     * ({@code government.fines-account}), which Treasury seeds at startup.
     */
    private static final String FINES_ACCOUNT = "GovernmentFines";

    @Test
    void fineIssueDebitsThePlayerAndCreatesAFineRow() {
        String admin = Usernames.fresh("FiA");
        String target = Usernames.fresh("FiT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {

            BigDecimal startBal = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            int beforeCount = Ledger.activeFinesForPlayer(t.getUuid());

            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/fine issue " + FINES_ACCOUNT + " " + target + " 500 disturbing the peace");
                a.awaitMessageContaining("Issued", TIMEOUT);
            });

            Ledger.waitForPersonalBalance(t.getUuid(),
                    startBal.subtract(new BigDecimal("500.00")), TIMEOUT);
            assertThat(Ledger.activeFinesForPlayer(t.getUuid())).isEqualTo(beforeCount + 1);
            // The fine is recorded against the account named in the command.
            long fineId = Ledger.mostRecentFineFor(t.getUuid()).orElseThrow();
            assertThat(Ledger.fineGovAccountId(fineId))
                    .isEqualTo(Ledger.governmentAccountId(FINES_ACCOUNT));
        }
    }

    @Test
    void fineRevokeRefundsThePlayerAndMarksFineRevoked() {
        String admin = Usernames.fresh("FrA");
        String target = Usernames.fresh("FrT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {

            BigDecimal startBal = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            // Use a single Ops.run for the full flow — repeated op/deop
            // cycles thrash Brigadier's per-player permission cache and the
            // second @Permission-gated command in a test can land before the
            // refresh completes.
            Ops.run(admin, () -> {
                a.chatCommand("/fine issue " + FINES_ACCOUNT + " " + target + " 200 testing revoke flow");
                a.awaitMessageContaining("Issued", TIMEOUT);

                Ledger.waitForPersonalBalance(t.getUuid(),
                        startBal.subtract(new BigDecimal("200.00")), TIMEOUT);
                long fineId = Ledger.mostRecentFineFor(t.getUuid()).orElseThrow();
                assertThat(Ledger.fineIsRevoked(fineId)).isFalse();

                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/fine revoke " + fineId);
                a.awaitMessageContaining("Revoked", TIMEOUT);

                Ledger.waitForPersonalBalance(t.getUuid(), startBal, TIMEOUT);
                assertThat(Ledger.fineIsRevoked(fineId)).isTrue();
            });
        }
    }

    @Test
    void fineListShowsRecentFines() {
        String admin = Usernames.fresh("FlA");
        String target = Usernames.fresh("FlT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {

            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.chatCommand("/fine issue " + FINES_ACCOUNT + " " + target + " 50 minor offense");
                a.awaitMessageContaining("Issued", TIMEOUT);
                a.drainMessages();
                a.chatCommand("/fine list " + target);
                String response = a.awaitMessageContaining("Fines for", TIMEOUT);
                assertThat(response).contains(target);
            });
        }
    }

    @Test
    void fineInfoShowsTheSingleFineDetail() {
        String admin = Usernames.fresh("FoA");
        String target = Usernames.fresh("FoT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {

            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.chatCommand("/fine issue " + FINES_ACCOUNT + " " + target + " 75 audit");
                a.awaitMessageContaining("Issued", TIMEOUT);

                Ledger.waitForActiveFineCount(t.getUuid(), 1, TIMEOUT);
                long fineId = Ledger.mostRecentFineFor(t.getUuid()).orElseThrow();

                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/fine info " + fineId);
                String response = a.awaitMessageContaining("Fine #", TIMEOUT);
                assertThat(response).contains(target);
            });
        }
    }

    @Test
    void identicalFinesInOneSecondAreTwoFinesAndTwoDebits() {
        // PAR-570: two identical /fine issue commands inside one second used to
        // share a ledger dedup key, so only the first debited while both reported
        // "Issued fine". Commands carry no dedup key now: each is its own fine,
        // its own debit and its own fine row (V29 uq_fines_txn keeps rows and
        // debits one-to-one, so neither can be refunded twice).
        String admin = Usernames.fresh("FdA");
        String target = Usernames.fresh("FdT");
        BigDecimal amount = new BigDecimal("30.00");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {

            BigDecimal startBal = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.drainMessages();
                String cmd = "/fine issue " + FINES_ACCOUNT + " " + target + " 30 double submit";
                // Start early in a wall-clock second so both land inside it.
                long intoSecond = System.currentTimeMillis() % 1000;
                if (intoSecond > 300) sleep(1000 - intoSecond + 20);
                a.chatCommand(cmd);
                a.chatCommand(cmd);
                a.awaitMessageContaining("Issued fine", TIMEOUT);
                a.awaitMessageContaining("Issued fine", TIMEOUT);
            });

            Barrier.settle();
            Ledger.waitForPersonalBalance(t.getUuid(), startBal.subtract(amount.multiply(BigDecimal.valueOf(2))), TIMEOUT);
            assertThat(Ledger.totalFinesForPlayer(t.getUuid())).isEqualTo(2);
            assertThat(Ledger.activeFinesForPlayer(t.getUuid())).isEqualTo(2);
        }
    }

    @Test
    void firmFineDebitsTheFirmAccountAndRevokeRefundsIt() {
        // /fine firm <account> <firm> <amount> <reason> — the firm is named by
        // its bare name (its account is "<firm> Corporate Account"). A firm
        // fine has no player UUID, so revoke must refund the firm account and
        // skip the "notify the fined player" step rather than fail on it.
        String admin = Usernames.fresh("FfA");
        String firm = "FfF" + Long.toHexString(System.currentTimeMillis());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.chatCommand("/business create " + firm);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int firmAccountId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                BigDecimal firmStart = Ledger.accountBalanceById(firmAccountId).orElse(BigDecimal.ZERO);

                // Give the firm something to be fined out of.
                Barrier.settle();
                a.chatCommand("/gov payout DCGovernment " + firm + " 200 seed");
                a.awaitMessageContaining("Paid", TIMEOUT);
                BigDecimal funded = firmStart.add(new BigDecimal("200.00"));
                Ledger.waitForAccountBalanceById(firmAccountId, funded, TIMEOUT);

                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/fine firm " + FINES_ACCOUNT + " " + firm + " 50 unpaid licence");
                String issued = a.awaitMessageContaining("Issued fine", TIMEOUT);
                assertThat(issued).contains("to firm").contains(firm);
                Ledger.waitForAccountBalanceById(firmAccountId,
                        funded.subtract(new BigDecimal("50.00")), TIMEOUT);

                long fineId = Ledger.mostRecentFineForDebtorAccount(firmAccountId).orElseThrow();
                assertThat(Ledger.fineIsRevoked(fineId)).isFalse();

                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/fine revoke " + fineId);
                String revoked = a.awaitMessageContaining("Revoked fine #" + fineId, TIMEOUT);
                assertThat(revoked).contains(firm);

                Ledger.waitForAccountBalanceById(firmAccountId, funded, TIMEOUT);
                assertThat(Ledger.fineIsRevoked(fineId)).isTrue();
            });
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
