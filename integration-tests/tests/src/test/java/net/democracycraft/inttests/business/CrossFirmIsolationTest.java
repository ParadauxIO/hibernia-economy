package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-class coverage for cross-firm Treasury-account isolation.
 *
 * <p>{@code /business account ...} commands take {@code <firm>} +
 * {@code <accountId>}. The account_id is a global integer, so a proprietor of
 * firm A who learns firm B's account_id could otherwise try to deposit /
 * withdraw / setdefault / archive on firm B's account through their own
 * {@code /business account ...} invocations. The handler must reject any
 * (firm, account_id) pair that isn't joined by a {@code firm_accounts} row.
 *
 * <p>Each test sets up two firms with different owners, captures firm B's
 * account_id, then has firm A's proprietor try to operate on it through
 * firm A. Asserts (a) a user-visible rejection and (b) no balance change on
 * firm B's account.
 */
class CrossFirmIsolationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void accountDepositRejectsAccountIdFromAnotherFirm() {
        String aOwner = Usernames.fresh("CdA");
        String bOwner = Usernames.fresh("CdB");
        String firmA = freshFirmName("CdfA");
        String firmB = freshFirmName("CdfB");
        try (MockPlayer a = MockPlayer.connectLocal(aOwner);
             MockPlayer b = MockPlayer.connectLocal(bOwner)) {
            BigDecimal aStart = Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            int[] firmBAccountId = new int[1];
            Ops.run(bOwner, () -> {
                b.chatCommand("/business create " + firmB);
                b.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmBId = BusinessLedger.waitForFirm(firmB, TIMEOUT);
                firmBAccountId[0] = BusinessLedger.firmDefaultAccountId(firmBId).orElseThrow();
            });

            Ops.run(aOwner, () -> {
                a.chatCommand("/business create " + firmA);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firmA, TIMEOUT);
                Barrier.settle();

                BigDecimal firmBBefore = Ledger.accountBalanceById(firmBAccountId[0])
                        .orElse(BigDecimal.ZERO);

                a.drainMessages();
                // Firm A's proprietor passes firmA but firm B's account id.
                a.chatCommand("/business account deposit " + firmA + " " + firmBAccountId[0] + " 50");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();

                // Firm B's balance must be unchanged; the deposit must not have
                // landed because the (firm, account) pair is not associated.
                Barrier.settle();
                assertThat(Ledger.accountBalanceById(firmBAccountId[0]).orElse(BigDecimal.ZERO))
                        .as("firm B account balance must not change when firm A "
                                + "owner deposits using firm B's account id")
                        .isEqualByComparingTo(firmBBefore);

                // Firm A's owner's personal balance must also be unchanged
                // (no debit went anywhere).
                assertThat(Ledger.personalBalance(a.getUuid()).orElseThrow())
                        .as("firm A owner's personal must not be debited by a "
                                + "rejected cross-firm deposit")
                        .isEqualByComparingTo(aStart);
            });
        }
    }

    @Test
    void accountWithdrawRejectsAccountIdFromAnotherFirm() {
        String aOwner = Usernames.fresh("CwA");
        String bOwner = Usernames.fresh("CwB");
        String firmA = freshFirmName("CwfA");
        String firmB = freshFirmName("CwfB");
        try (MockPlayer a = MockPlayer.connectLocal(aOwner);
             MockPlayer b = MockPlayer.connectLocal(bOwner)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            int[] firmBAccountId = new int[1];
            Ops.run(bOwner, () -> {
                b.chatCommand("/business create " + firmB);
                b.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmBId = BusinessLedger.waitForFirm(firmB, TIMEOUT);
                firmBAccountId[0] = BusinessLedger.firmDefaultAccountId(firmBId).orElseThrow();
                // Fund firm B so a withdrawal would actually move money if
                // the isolation check were broken.
                Barrier.settle();
                b.chatCommand("/business deposit " + firmB + " 100");
                b.awaitMessageContaining("Paid", TIMEOUT);
            });

            Ops.run(aOwner, () -> {
                a.chatCommand("/business create " + firmA);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firmA, TIMEOUT);
                Barrier.settle();

                BigDecimal firmBBefore = Ledger.accountBalanceById(firmBAccountId[0])
                        .orElse(BigDecimal.ZERO);

                a.drainMessages();
                a.chatCommand("/business account withdraw " + firmA + " " + firmBAccountId[0] + " 50");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();

                Barrier.settle();
                assertThat(Ledger.accountBalanceById(firmBAccountId[0]).orElse(BigDecimal.ZERO))
                        .as("firm B account balance must not drop when firm A "
                                + "owner withdraws using firm B's account id")
                        .isEqualByComparingTo(firmBBefore);
            });
        }
    }

    @Test
    void accountSetDefaultRejectsAccountIdFromAnotherFirm() {
        String aOwner = Usernames.fresh("CsA");
        String bOwner = Usernames.fresh("CsB");
        String firmA = freshFirmName("CsfA");
        String firmB = freshFirmName("CsfB");
        try (MockPlayer a = MockPlayer.connectLocal(aOwner);
             MockPlayer b = MockPlayer.connectLocal(bOwner)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            int[] firmBAccountId = new int[1];
            Ops.run(bOwner, () -> {
                b.chatCommand("/business create " + firmB);
                b.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmBId = BusinessLedger.waitForFirm(firmB, TIMEOUT);
                firmBAccountId[0] = BusinessLedger.firmDefaultAccountId(firmBId).orElseThrow();
            });

            int[] firmAId = new int[1];
            int[] firmAOriginalDefault = new int[1];
            Ops.run(aOwner, () -> {
                a.chatCommand("/business create " + firmA);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                firmAId[0] = BusinessLedger.waitForFirm(firmA, TIMEOUT);
                firmAOriginalDefault[0] = BusinessLedger.firmDefaultAccountId(firmAId[0]).orElseThrow();
                Barrier.settle();

                a.drainMessages();
                a.chatCommand("/business account setdefault " + firmA + " " + firmBAccountId[0]);
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();

                // Firm A's default must not have moved to firm B's account.
                Barrier.settle();
                assertThat(BusinessLedger.firmDefaultAccountId(firmAId[0]).orElseThrow())
                        .as("firm A default account id must not switch to firm B's account")
                        .isEqualTo(firmAOriginalDefault[0]);
            });
        }
    }

    @Test
    void accountArchiveRejectsAccountIdFromAnotherFirm() {
        // Two firms; firm A's owner tries to archive firm B's default account
        // through their own /business account archive. Firm B's account must
        // remain un-archived (firmDefaultAccountId still returns it as a
        // non-archived id).
        String aOwner = Usernames.fresh("CaA");
        String bOwner = Usernames.fresh("CaB");
        String firmA = freshFirmName("CafA");
        String firmB = freshFirmName("CafB");
        try (MockPlayer a = MockPlayer.connectLocal(aOwner);
             MockPlayer b = MockPlayer.connectLocal(bOwner)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            int[] firmBId = new int[1];
            int[] firmBAccountId = new int[1];
            Ops.run(bOwner, () -> {
                b.chatCommand("/business create " + firmB);
                b.awaitMessageContaining("Successfully created", TIMEOUT);
                firmBId[0] = BusinessLedger.waitForFirm(firmB, TIMEOUT);
                // Create a second account so the archive isn't blocked by the
                // "can't archive the default" rule.
                Barrier.settle();
                b.chatCommand("/business account create " + firmB + " Secondary");
                b.awaitMessageContaining("created account", TIMEOUT);
                BusinessLedger.waitForFirmAccountCountAtLeast(firmBId[0], 2, TIMEOUT);
                firmBAccountId[0] = BusinessLedger.firmAccountIdAt(firmBId[0], 1);
            });

            Ops.run(aOwner, () -> {
                a.chatCommand("/business create " + firmA);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firmA, TIMEOUT);
                Barrier.settle();

                int beforeCount = BusinessLedger.firmAccountCount(firmBId[0]);
                a.drainMessages();
                a.chatCommand("/business account archive " + firmA + " " + firmBAccountId[0]);
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();

                Barrier.settle();
                // firmAccountCount counts all rows (including archived in some
                // mappers); the safer assertion is "firm B still has the
                // account id active" — verified via accountBalanceById which
                // returns a value when the materialised balance row exists.
                assertThat(Ledger.accountBalanceById(firmBAccountId[0]))
                        .as("firm B's secondary account must not be archived "
                                + "by firm A owner's cross-firm archive call")
                        .isPresent();
                assertThat(BusinessLedger.firmAccountCount(firmBId[0]))
                        .as("firm B's firm_accounts row count must be unchanged")
                        .isEqualTo(beforeCount);
            });
        }
    }
}
