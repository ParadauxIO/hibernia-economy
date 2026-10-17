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
 * Negative-path coverage for /business account ... commands. Mirror of
 * {@code MiscCommandsTest::depositRejectsNegativeAmount} but for the
 * explicit-account variants and for additional rejection branches.
 */
class AccountCommandsRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void accountDepositRejectsNegativeAmount() {
        String owner = Usernames.fresh("AdN");
        String firm = freshFirmName("AdN");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            BigDecimal start = Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account deposit " + firm + " " + defaultId + " -10");
                p.awaitMessageContaining("Invalid amount", TIMEOUT);

                // Personal balance unchanged.
                assertThat(Ledger.personalBalance(p.getUuid()).orElseThrow())
                        .isEqualByComparingTo(start);
            });
        }
    }

    @Test
    void accountWithdrawRejectsNegativeAmount() {
        String owner = Usernames.fresh("AwN");
        String firm = freshFirmName("AwN");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account withdraw " + firm + " " + defaultId + " -10");
                p.awaitMessageContaining("Invalid amount", TIMEOUT);
            });
        }
    }

    @Test
    void accountWithdrawRejectsInsufficientFunds() {
        String owner = Usernames.fresh("AwI");
        String firm = freshFirmName("AwI");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.drainMessages();
                // Brand-new firm — default account empty.
                p.chatCommand("/business account withdraw " + firm + " " + defaultId + " 1000000");
                p.awaitMessage(m -> m.toLowerCase().contains("doesn't have enough")
                        || m.toLowerCase().contains("insufficient"), TIMEOUT);
            });
        }
    }

    @Test
    void accountCommandsRejectUnknownFirm() {
        String name = Usernames.fresh("Au");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business account list NoSuchFirmEvar");
                p.awaitMessage(m -> m.contains("not found") || m.contains("firm-not-found"), TIMEOUT);
            });
        }
    }

    @Test
    void accountDepositRejectsZeroAmount() {
        String owner = Usernames.fresh("AdZ");
        String firm = freshFirmName("AdZ");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account deposit " + firm + " " + defaultId + " 0");
                p.awaitMessage(m -> m.contains("Invalid")
                        || m.toLowerCase().contains("greater than"), TIMEOUT);
            });
        }
    }

    @Test
    void accountWithdrawRejectsZeroAmount() {
        String owner = Usernames.fresh("AwZ");
        String firm = freshFirmName("AwZ");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account withdraw " + firm + " " + defaultId + " 0");
                p.awaitMessage(m -> m.contains("Invalid")
                        || m.toLowerCase().contains("greater than"), TIMEOUT);
            });
        }
    }

    @Test
    void accountBalanceRejectsUnknownAccountId() {
        // Account ID that's structurally valid but doesn't exist (or doesn't
        // belong to the firm) should surface a not-found / not-associated
        // error rather than 0 or a phantom balance row.
        String owner = Usernames.fresh("AbU");
        String firm = freshFirmName("AbU");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                p.drainMessages();
                // 99999999 is well above any account_id likely to exist.
                p.chatCommand("/business account balance " + firm + " 99999999");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void accountArchiveRejectsUnknownAccountId() {
        String owner = Usernames.fresh("AaU");
        String firm = freshFirmName("AaU");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account archive " + firm + " 99999999");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void accountSetDefaultRejectsUnknownAccountId() {
        String owner = Usernames.fresh("AsU");
        String firm = freshFirmName("AsU");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account setdefault " + firm + " 99999999");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void accountSyncRejectsUnknownAccountId() {
        String owner = Usernames.fresh("AyU");
        String firm = freshFirmName("AyU");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account sync " + firm + " 99999999");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void accountAddMemberRejectsUnknownAccountId() {
        String owner = Usernames.fresh("AmaU");
        String tgt = Usernames.fresh("AmaT");
        String firm = freshFirmName("AmaU");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer t = MockPlayer.connectLocal(tgt)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account addmember " + firm + " 99999999 " + tgt);
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }
}
