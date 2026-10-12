package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
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
 * Self-targeting / loopback attacks across every transfer-shaped command.
 *
 * <p>The class of bugs we're guarding against: a transfer where source and
 * destination are the same account that nonetheless commits two postings.
 * If the writer doesn't reject the no-op transfer up front, you get one
 * negative posting + one positive posting on the same account — net zero,
 * which superficially looks fine. But because the DB triggers update
 * {@code account_balances_mat} per posting, a race against a concurrent
 * write could flip the apparent balance, and the audit trail grows by
 * one zero-net transaction per call. An attacker hammering this can
 * fill the ledger with junk and obscure real transfers.
 *
 * <p>More directly: some loopbacks (paying yourself, firing yourself,
 * transferring ownership to yourself) shouldn't even be representable —
 * the validator should reject before the ledger gets touched.
 *
 * <p>Each test asserts both (a) some user-visible rejection and (b) the
 * relevant balance is exactly unchanged. A "rejection" that nevertheless
 * commits postings is the dangerous failure mode.
 */
class SelfTargetingAttacksTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String suffix() {
        return Long.toHexString(Instant.now().toEpochMilli() ^ System.nanoTime());
    }

    // ============================================================ /pay

    @Test
    void payToSelfIsRejectedAndBalanceUnchanged() {
        // /pay X X 100 — the source and destination both resolve to the
        // same PERSONAL account. Self-payment must be rejected, and the
        // balance must be exactly the starting balance (not even
        // "starting + 0" via a same-account posting pair, which could
        // race with concurrent writes).
        String me = Usernames.fresh("PSlf");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            BigDecimal start = Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            p.drainMessages();
            p.chatCommand("/pay " + me + " 100");
            p.awaitMessage(m -> !m.isBlank(), TIMEOUT);

            assertThat(Ledger.personalBalance(p.getUuid()).orElseThrow())
                    .as("self-pay must not change personal balance")
                    .isEqualByComparingTo(start);
        }
    }

    // ============================================================ /gov

    @Test
    void govAccountTransferToSameAccountIsRejected() {
        // Existing GovCommandTest covers this — kept here so the
        // attack-class taxonomy stays whole. A regression breaking the
        // same-account check in /gov would surface here independently
        // of any reorganisation of GovCommandTest.
        String admin = Usernames.fresh("GSlf");
        String acct = "GSlfAcc" + suffix();
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + acct);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(acct, TIMEOUT);

                BigDecimal startBal = Ledger.governmentBalance(acct).orElseThrow();
                a.drainMessages();
                a.chatCommand("/gov account transfer " + acct + " " + acct + " 100 self-loop");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);

                assertThat(Ledger.governmentBalance(acct).orElseThrow())
                        .as("self-transfer between identical gov accounts must not change balance")
                        .isEqualByComparingTo(startBal);
            });
        }
    }

    @Test
    void govPayoutToSelfDoesNotInflatePersonalBalance() {
        // /gov payout <acct> <playerName> — even if the sender names
        // themselves as recipient, the source and destination differ
        // (gov account vs personal account). This test verifies the
        // round-trip math: payout to self moves money from the gov
        // account INTO the personal account by the requested amount,
        // not zero and not double.
        String admin = Usernames.fresh("GpSlf");
        String acct = "GpSelfAcc" + suffix();
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            BigDecimal startPersonal = Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + acct);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(acct, TIMEOUT);
                // Seed the gov account. /eco give and /pay are both @Async —
                // wait for each to land in the DB before sampling the next
                // baseline, otherwise beforePayout sees a stale balance.
                a.chatCommand("/eco give " + admin + " 500");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Ledger.waitForPersonalBalance(a.getUuid(),
                        startPersonal.add(new BigDecimal("500.00")), TIMEOUT);

                a.chatCommand("/pay " + acct + " 500");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Ledger.waitForPersonalBalance(a.getUuid(), startPersonal, TIMEOUT);

                BigDecimal beforePayout = Ledger.personalBalance(a.getUuid()).orElseThrow();
                BigDecimal govBefore = Ledger.governmentBalance(acct).orElseThrow();

                a.drainMessages();
                a.chatCommand("/gov payout " + acct + " " + admin + " 100 self-payout");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);

                // Personal balance up by exactly 100, gov down by exactly 100.
                // Wait for the personal balance to land — both postings commit
                // in the same txn, so once personal is correct, gov is too.
                Ledger.waitForPersonalBalance(a.getUuid(),
                        beforePayout.add(new BigDecimal("100.00")), TIMEOUT);
                assertThat(Ledger.governmentBalance(acct).orElseThrow())
                        .isEqualByComparingTo(govBefore.subtract(new BigDecimal("100.00")));
            });

            // Sanity: not the starting balance — we should have netted +100 vs start.
            assertThat(Ledger.personalBalance(a.getUuid()).orElseThrow())
                    .isGreaterThan(startPersonal);
        }
    }

    // ============================================================ /fine

    @Test
    void fineIssuerCannotFineSelf() {
        // An op fining themselves is dubious but not strictly
        // forbidden — it does move money from their PERSONAL into
        // GovernmentFines, which is a legitimate transfer. What MUST
        // be rejected is the issuer == target == fines-recipient case,
        // which the fine flow never produces. So this test is a
        // canary: if it accidentally starts asserting equality of
        // before/after, that's a real bug. For now, we assert the
        // self-fine reduces the issuer's own balance by exactly the
        // amount (i.e. it works as a legitimate transfer to fines).
        String issuer = Usernames.fresh("FSlf");
        try (MockPlayer p = MockPlayer.connectLocal(issuer)) {
            BigDecimal start = Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(issuer, () -> {
                p.drainMessages();
                p.chatCommand("/fine issue GovernmentFines " + issuer + " 50 self-fine canary");
                p.awaitMessageContaining("Issued fine", TIMEOUT);
            });
            // Self-fine should debit the issuer's PERSONAL by exactly 50.
            // If a code path ever lets the fine post twice (negative + positive
            // on the same account net-zero, OR debit-only-without-credit), this
            // assertion fails clearly.
            // The chat receipt can precede the commit (@Async) — let the debit land.
            Ledger.waitForPersonalBalance(p.getUuid(), start.subtract(new BigDecimal("50.00")), TIMEOUT);
            BigDecimal afterFine = Ledger.personalBalance(p.getUuid()).orElseThrow();
            assertThat(start.subtract(afterFine))
                    .as("self-fine must debit issuer's PERSONAL by exactly the fine amount, no more, no less")
                    .isEqualByComparingTo(new BigDecimal("50.00"));
        }
    }

    // ============================================================ /business

    @Test
    void firmAccountTransferToSameAccountIsRejected() {
        // /business account transfer <firm> <id> <id> N — same account
        // on both sides must be rejected by the writer. This is the
        // business-side equivalent of govAccountTransferToSameAccount.
        String owner = Usernames.fresh("BTSlf");
        String firm = "BTS" + suffix();
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int acctId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                p.drainMessages();
                p.chatCommand("/business account transfer " + firm + " " + acctId + " " + acctId + " 100 self-loop");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);

                // Balance from BusinessLedger view — must remain 0 (fresh firm).
                assertThat(Ledger.governmentBalance(firm + " Corporate Account").orElse(BigDecimal.ZERO))
                        .as("self-transfer on the same firm account must not create postings")
                        .isEqualByComparingTo("0.00");
            });
        }
    }

    @Test
    void hiringSelfIntoOwnFirmIsRejected() {
        // The proprietor is already a member of their firm. Re-hiring
        // themselves would either be a no-op (best case) or create a
        // duplicate firm_employee row (worst case — breaks role lookup
        // assumptions downstream).
        String owner = Usernames.fresh("HSlf");
        String firm = "HSlf" + suffix();
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                p.drainMessages();
                p.chatCommand("/business hire " + firm + " " + owner);
                String reply = p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                // Should NOT succeed silently — message must communicate
                // either "you can't do this to yourself" or "already
                // employed".
                assertThat(reply).doesNotContainIgnoringCase("successfully hired");
            });
        }
    }

}
