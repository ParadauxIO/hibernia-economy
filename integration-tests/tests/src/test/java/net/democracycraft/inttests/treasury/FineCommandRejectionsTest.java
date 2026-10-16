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
 * Negative-path coverage for /fine issue|revoke|info. The "already-revoked"
 * and "fine not-found" branches in particular validate brittle service-message
 * substring parsing in FineCommand — locking in current behavior.
 */
class FineCommandRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * {@code /fine issue <account> <player> <amount> <reason>} names the government
     * account the fine is paid into; this is the configured default fines account
     * ({@code government.fines-account}), which Treasury seeds at startup.
     */
    private static final String FINES_ACCOUNT = "GovernmentFines";

    @Test
    void fineIssueRejectsUnknownPlayer() {
        String issuer = Usernames.fresh("FiU");
        try (MockPlayer i = MockPlayer.connectLocal(issuer)) {
            Ops.run(issuer, () -> {
                i.drainMessages();
                // A name that never joined isn't in the server's player cache, so
                // the framework's OfflinePlayer resolver rejects the <player>
                // argument before FineCommand runs ("Invalid player: <name>").
                i.chatCommand("/fine issue " + FINES_ACCOUNT + " NotARealName 100 some reason");
                String r = i.awaitMessageContaining("Invalid player", TIMEOUT);
                assertThat(r).contains("NotARealName");
            });
        }
    }

    @Test
    void fineIssueRejectsUnknownGovernmentAccount() {
        // The first argument must name an existing, non-archived government account.
        String issuer = Usernames.fresh("FiG");
        String target = Usernames.fresh("FiGt");
        try (MockPlayer i = MockPlayer.connectLocal(issuer);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(issuer, () -> {
                i.drainMessages();
                i.chatCommand("/fine issue NoSuchFinesAcct " + target + " 100 some reason");
                String r = i.awaitMessageContaining("not found", TIMEOUT);
                assertThat(r).contains("NoSuchFinesAcct");
            });
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
            assertThat(Ledger.activeFinesForPlayer(t.getUuid())).isZero();
        }
    }

    @Test
    void fineIssueRejectsZeroAmount() {
        String issuer = Usernames.fresh("FiZ");
        String target = Usernames.fresh("FiZt");
        try (MockPlayer i = MockPlayer.connectLocal(issuer);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(issuer, () -> {
                i.drainMessages();
                i.chatCommand("/fine issue " + FINES_ACCOUNT + " " + target + " 0 disorderly");
                i.awaitMessageContaining("greater than zero", TIMEOUT);
            });
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void fineIssueRejectsNegativeAmount() {
        String issuer = Usernames.fresh("FiN");
        String target = Usernames.fresh("FiNt");
        try (MockPlayer i = MockPlayer.connectLocal(issuer);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(issuer, () -> {
                i.drainMessages();
                i.chatCommand("/fine issue " + FINES_ACCOUNT + " " + target + " -25 something");
                i.awaitMessageContaining("greater than zero", TIMEOUT);
            });
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void fineRevokeRejectsUnknownId() {
        String issuer = Usernames.fresh("FrU");
        try (MockPlayer i = MockPlayer.connectLocal(issuer)) {
            Ops.run(issuer, () -> {
                i.drainMessages();
                i.chatCommand("/fine revoke 999999999");
                i.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void fineInfoRejectsUnknownId() {
        String issuer = Usernames.fresh("FoU");
        try (MockPlayer i = MockPlayer.connectLocal(issuer)) {
            Ops.run(issuer, () -> {
                i.drainMessages();
                i.chatCommand("/fine info 999999998");
                i.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void fineRevokeRejectsAlreadyRevoked() {
        // Issue → revoke → revoke again. Second revoke should hit the
        // already-revoked branch (brittle service-message substring check).
        String issuer = Usernames.fresh("FaR");
        String target = Usernames.fresh("FaRt");
        try (MockPlayer i = MockPlayer.connectLocal(issuer);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(issuer, () -> {
                i.drainMessages();
                i.chatCommand("/fine issue " + FINES_ACCOUNT + " " + target + " 50 testing");
                i.awaitMessageContaining("Issued fine", TIMEOUT);

                long fineId = pollForFineId(t.getUuid());

                Barrier.settle();
                i.drainMessages();
                i.chatCommand("/fine revoke " + fineId);
                i.awaitMessageContaining("Revoked", TIMEOUT);

                Barrier.settle();
                i.drainMessages();
                i.chatCommand("/fine revoke " + fineId);
                i.awaitMessageContaining("already revoked", TIMEOUT);
            });
        }
    }

    private static long pollForFineId(java.util.UUID playerUuid) {
        return Ledger.waitForMostRecentFine(playerUuid, TIMEOUT);
    }
}
