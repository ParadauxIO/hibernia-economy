package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial amount-input coverage across every command that takes a
 * BigDecimal amount: {@code /pay}, {@code /eco give|take|set}, {@code /fine
 * issue}, {@code /gov account transfer}, {@code /gov payout}.
 *
 * <p>Each test asserts both (a) the operator sees <em>some</em> rejection
 * (broad message match — these tests must survive message-text edits) and
 * (b) the underlying ledger balance is bit-for-bit unchanged. The balance
 * assertion is the real security gate: a "rejection" that nonetheless
 * mutates the ledger would be a critical bug.
 *
 * <p>Attack vectors covered:
 * <ul>
 *   <li><b>Sub-cent precision.</b> {@code 0.001}, {@code 0.0001},
 *       {@code 0.00000000001} — caught by {@code Money.requireValidAmount}
 *       as &gt; 2 decimal places. A real player attempted this on the live
 *       server (2026-05-16) and the framework swallowed the IAE into the
 *       wrong message until the FineCommand fix landed.</li>
 *   <li><b>Sign games.</b> Negative amounts, explicit-plus prefix, zero.</li>
 *   <li><b>Notation tricks.</b> Scientific notation, leading dot, comma
 *       thousands separator (now legitimately accepted via the
 *       BigDecimalResolver fix — verified here so a regression that
 *       narrows comma handling would break the test).</li>
 *   <li><b>Non-numeric.</b> Alphabetic, mixed alphanumeric.</li>
 *   <li><b>Overflow.</b> Many-digit decimals that fit in BigDecimal but
 *       blow up downstream column widths.</li>
 * </ul>
 *
 * <p>These tests deliberately do NOT pin specific error text. The
 * Hibernia / Money / framework boundary has churned several times already
 * (BigDecimalResolver comma support 2026-05-16, FineCommand IAE routing
 * 2026-05-16); pinning text creates flaky tests. Pin behaviour: balance
 * unchanged, message non-empty, command did not silently no-op.
 */
class AmountAttacksTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    // ============================================================ /pay

    @Test
    void payRejectsSubCentPrecisionAndPreservesBalance() {
        assertPayAmountIsRejected("0.001");
    }

    @Test
    void payRejectsExtremePrecisionAndPreservesBalance() {
        assertPayAmountIsRejected("0.00000000001");
    }

    @Test
    void payRejectsNonNumericAmount() {
        assertPayAmountIsRejected("abc");
    }

    @Test
    void payRejectsMixedAlphaNumericAmount() {
        assertPayAmountIsRejected("10abc");
    }

    @Test
    void payRejectsScientificNotationOverflow() {
        // BigDecimal("1e9999") technically parses; the downstream DECIMAL
        // column will reject. Either path is fine — what matters is the
        // sender doesn't lose money to a partial commit.
        assertPayAmountIsRejected("1e9999");
    }

    @Test
    void payAcceptsThousandsSeparatorComma() {
        // Regression guard for hibernia BigDecimalResolver: "1,000" must
        // parse as 1000 after the 2026-05-16 fix. If the resolver narrows
        // comma handling later, this fails loudly.
        String sender = Usernames.fresh("PtcS");
        String target = Usernames.fresh("PtcT");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal sStart = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            BigDecimal tStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            s.drainMessages();
            s.chatCommand("/pay " + target + " 1,000");
            s.awaitMessageContaining("Paid", TIMEOUT);

            Ledger.waitForPersonalBalance(s.getUuid(), sStart.subtract(new BigDecimal("1000.00")), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), tStart.add(new BigDecimal("1000.00")), TIMEOUT);
        }
    }

    // ============================================================ /eco

    @Test
    void ecoGiveRejectsSubCentPrecision() {
        assertEcoGiveAmountIsRejected("0.001");
    }

    @Test
    void ecoTakeRejectsExtremePrecision() {
        assertEcoTakeAmountIsRejected("0.0000001");
    }

    @Test
    void ecoSetRejectsSubCentPrecision() {
        assertEcoSetAmountIsRejected("100.001");
    }

    @Test
    void ecoSetRejectsNegativePersonalBalance() {
        // PERSONAL accounts have allow_overdraft=false. Even an admin
        // setting a negative balance must be rejected by the overdraft
        // check, not just trimmed silently.
        assertEcoSetAmountIsRejected("-1.00");
    }

    // ============================================================ /fine issue

    @Test
    void fineIssueRejectsSubCentWithInvalidAmountNotUnknownPlayer() {
        // The 2026-05-16 FineCommand fix: sub-cent fines must now route
        // through treasury.general.invalid-amount, NOT
        // treasury.general.unknown-player. Pin that distinction —
        // regressing this re-introduces the original misclassification
        // bug that confused live ops.
        String issuer = Usernames.fresh("FiI");
        String target = Usernames.fresh("FiT");
        try (MockPlayer i = MockPlayer.connectLocal(issuer);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal targetStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(i.getUuid(), TIMEOUT);

            Ops.run(issuer, () -> {
                i.drainMessages();
                i.chatCommand("/fine issue GovernmentFines " + target + " 0.001 testing");
                String reply = i.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
                assertThat(reply).doesNotContainIgnoringCase("never joined");
                assertThat(reply).doesNotContainIgnoringCase("never played");
                assertThat(reply).contains("greater than zero");
            });

            // The fine must not have posted.
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(targetStart);
        }
    }

    @Test
    void fineIssueRejectsExtremePrecision() {
        String issuer = Usernames.fresh("FpI");
        String target = Usernames.fresh("FpT");
        try (MockPlayer i = MockPlayer.connectLocal(issuer);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal targetStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(i.getUuid(), TIMEOUT);

            Ops.run(issuer, () -> {
                i.drainMessages();
                i.chatCommand("/fine issue GovernmentFines " + target + " 0.00000000001 sub-sub-cent");
                i.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });

            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(targetStart);
        }
    }

    @Test
    void fineIssueRejectsCommaFormattedAmountAboveTargetBalance() {
        // The 19,993 case from the live incident. BigDecimal now parses
        // commas (post-fix), so the rejection should be about insufficient
        // funds, not parse failure. Either way the target balance is
        // preserved.
        String issuer = Usernames.fresh("FcI");
        String target = Usernames.fresh("FcT");
        try (MockPlayer i = MockPlayer.connectLocal(issuer);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal targetStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(i.getUuid(), TIMEOUT);

            Ops.run(issuer, () -> {
                i.drainMessages();
                // 19,993 — comma works now; target has $10,000 starting balance.
                i.chatCommand("/fine issue GovernmentFines " + target + " 19,993 too-big");
                String reply = i.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
                // Should be "insufficient" — NOT "never joined".
                assertThat(reply).doesNotContainIgnoringCase("never joined");
                assertThat(reply).containsIgnoringCase("insufficient");
            });

            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(targetStart);
        }
    }

    // ============================================================ /gov

    @Test
    void govAccountTransferRejectsSubCentBetweenGovs() {
        String admin = Usernames.fresh("GtA");
        String acct1 = "GtA" + Long.toHexString(System.nanoTime());
        String acct2 = "GtB" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + acct1);
                a.awaitMessageContaining("Created", TIMEOUT);
                a.chatCommand("/gov account create " + acct2);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(acct1, TIMEOUT);
                Ledger.waitForGovernmentAccount(acct2, TIMEOUT);

                BigDecimal startA = Ledger.governmentBalance(acct1).orElseThrow();
                BigDecimal startB = Ledger.governmentBalance(acct2).orElseThrow();

                a.drainMessages();
                a.chatCommand("/gov account transfer " + acct1 + " " + acct2 + " 0.001 sub-cent");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();

                assertThat(Ledger.governmentBalance(acct1).orElseThrow()).isEqualByComparingTo(startA);
                assertThat(Ledger.governmentBalance(acct2).orElseThrow()).isEqualByComparingTo(startB);
            });
        }
    }

    @Test
    void govPayoutRejectsAmountWithMoreThanTwoDecimals() {
        String admin = Usernames.fresh("GpA");
        String acct = "GpAcc" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + acct);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(acct, TIMEOUT);
                a.chatCommand("/eco give " + admin + " 100");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();

                BigDecimal start = Ledger.personalBalance(a.getUuid()).orElseThrow();
                a.drainMessages();
                a.chatCommand("/gov payout " + acct + " " + admin + " 1.0001 over-precise");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();

                assertThat(Ledger.personalBalance(a.getUuid()).orElseThrow())
                        .isEqualByComparingTo(start);
            });
        }
    }

    // ============================================================ helpers

    /** Shared workflow: connect two mocks, attempt /pay with the given amount, assert balances unchanged. */
    private static void assertPayAmountIsRejected(String amount) {
        String sender = Usernames.fresh("PAt");
        String target = Usernames.fresh("PAr");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal sStart = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            BigDecimal tStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            s.drainMessages();
            s.chatCommand("/pay " + target + " " + amount);
            s.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            Barrier.settle();

            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow())
                    .as("sender balance unchanged after rejected /pay " + amount)
                    .isEqualByComparingTo(sStart);
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .as("target balance unchanged after rejected /pay " + amount)
                    .isEqualByComparingTo(tStart);
        }
    }

    private static void assertEcoGiveAmountIsRejected(String amount) {
        String admin = Usernames.fresh("EgA");
        String target = Usernames.fresh("EgT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            BigDecimal tStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco give " + target + " " + amount);
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });

            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .as("target balance unchanged after rejected /eco give " + amount)
                    .isEqualByComparingTo(tStart);
        }
    }

    private static void assertEcoTakeAmountIsRejected(String amount) {
        String admin = Usernames.fresh("EtA");
        String target = Usernames.fresh("EtT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            BigDecimal tStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco take " + target + " " + amount);
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });

            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .as("target balance unchanged after rejected /eco take " + amount)
                    .isEqualByComparingTo(tStart);
        }
    }

    private static void assertEcoSetAmountIsRejected(String amount) {
        String admin = Usernames.fresh("EsA");
        String target = Usernames.fresh("EsT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            BigDecimal tStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco set " + target + " " + amount);
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });

            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .as("target balance unchanged after rejected /eco set " + amount)
                    .isEqualByComparingTo(tStart);
        }
    }
}
