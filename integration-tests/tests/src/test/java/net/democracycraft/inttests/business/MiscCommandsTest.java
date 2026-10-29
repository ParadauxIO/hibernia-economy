package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /business balance, deposit, withdraw, transactions — operate on a firm's
 * default account via {@code MiscCommands}.
 */
class MiscCommandsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        // Keep firm names distinct from player names generated in the same millisecond.
        return "Firm" + tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposit", "pay into"})
    void outsiderCanPayWithoutGainingMembership(String command) {
        String firm = freshFirmName("Public");
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("DpO"));
             MockPlayer payer = MockPlayer.connectLocal(Usernames.fresh("DpP"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            BigDecimal before = Ledger.waitForPersonalBalance(payer.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
            });
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            BigDecimal firmBefore = BusinessLedger.firmDefaultBalance(firmId).orElseThrow();
            Barrier.settle();
            payer.drainMessages();
            payer.chatCommand("/firm " + command + " " + firm + " 12.50 Invoice 42");
            payer.awaitMessageContaining("Paid", TIMEOUT);
            Ledger.waitForPersonalBalance(payer.getUuid(), before.subtract(new BigDecimal("12.50")), TIMEOUT);
            BusinessLedger.waitForFirmDefaultBalance(firmId, firmBefore.add(new BigDecimal("12.50")), TIMEOUT);
            assertThat(BusinessLedger.isCurrentEmployee(firmId, payer.getUuid())).isFalse();
            assertThat(Ledger.initiatedPostingsAfter(payer.getUuid(), 0))
                    .filteredOn(p -> p.message().endsWith(": Invoice 42"))
                    .hasSize(2);
        }
    }

    @Test
    void balanceShowsDefaultAccountBalance() {
        String owner = Usernames.fresh("MbO");
        String firm = freshFirmName("Bal");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business balance " + firm);
                p.awaitMessageContaining(firm + " balance:", TIMEOUT);
            });
        }
    }

    @Test
    void depositDecreasesPersonalAndIncreasesFirmBalance() {
        String owner = Usernames.fresh("MdO");
        String firm = freshFirmName("Dep");
        BigDecimal amount = new BigDecimal("100");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            BigDecimal personalStart = Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                BigDecimal firmStart = BusinessLedger.firmDefaultBalance(firmId).orElse(BigDecimal.ZERO);

                Barrier.settle();
                p.chatCommand("/business deposit " + firm + " " + amount.toPlainString());
                p.awaitMessageContaining("Paid", TIMEOUT);

                Ledger.waitForPersonalBalance(p.getUuid(), personalStart.subtract(amount), TIMEOUT);
                BusinessLedger.waitForFirmDefaultBalance(firmId, firmStart.add(amount), TIMEOUT);
            });
        }
    }

    @Test
    void withdrawReversesDeposit() {
        String owner = Usernames.fresh("MwO");
        String firm = freshFirmName("Wdr");
        BigDecimal amount = new BigDecimal("50");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            BigDecimal personalStart = Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business deposit " + firm + " " + amount.toPlainString());
                p.awaitMessageContaining("Paid", TIMEOUT);
                Ledger.waitForPersonalBalance(p.getUuid(), personalStart.subtract(amount), TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business withdraw " + firm + " " + amount.toPlainString());
                p.awaitMessageContaining("Withdrew", TIMEOUT);
                Ledger.waitForPersonalBalance(p.getUuid(), personalStart, TIMEOUT);

                BusinessLedger.waitForFirmDefaultBalance(firmId, BigDecimal.ZERO, TIMEOUT);
            });
        }
    }

    @Test
    void transactionsEmptyOnFreshFirm() {
        String owner = Usernames.fresh("MtO");
        String firm = freshFirmName("Tx");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business transactions " + firm);
                p.awaitMessageContaining("No transactions for " + firm, TIMEOUT);
            });
        }
    }

    @Test
    void transactionsAfterDepositShowsHeader() {
        String owner = Usernames.fresh("MhO");
        String firm = freshFirmName("Hdr");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business deposit " + firm + " 25");
                p.awaitMessageContaining("Paid", TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business transactions " + firm);
                p.awaitMessageContaining("Transactions for " + firm, TIMEOUT);
            });
        }
    }

    @Test
    void balanceRejectsUnknownFirm() {
        String name = Usernames.fresh("Mbu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business balance NoSuchFirmEvar");
                p.awaitMessage(m -> m.contains("not found") || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void depositRejectsUnknownFirm() {
        String name = Usernames.fresh("Mdu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business deposit NoSuchFirmEvar 10");
                p.awaitMessage(m -> m.contains("not found") || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void withdrawRejectsUnknownFirm() {
        String name = Usernames.fresh("Mwu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business withdraw NoSuchFirmEvar 10");
                p.awaitMessage(m -> m.contains("not found") || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void transactionsRejectsUnknownFirm() {
        String name = Usernames.fresh("Mtu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business transactions NoSuchFirmEvar");
                p.awaitMessage(m -> m.contains("not found") || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void depositRejectsZeroAmount() {
        String owner = Usernames.fresh("MdZ");
        String firm = freshFirmName("MdZ");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business deposit " + firm + " 0");
                p.awaitMessage(m -> m.contains("Invalid")
                        || m.toLowerCase().contains("greater than"), TIMEOUT);
            });
        }
    }

    @Test
    void withdrawRejectsInsufficientFunds() {
        String owner = Usernames.fresh("MwI");
        String firm = freshFirmName("MwI");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                p.drainMessages();
                // Fresh firm — default account empty.
                p.chatCommand("/business withdraw " + firm + " 1000000");
                p.awaitMessage(m -> m.toLowerCase().contains("insufficient")
                        || m.toLowerCase().contains("doesn't have enough")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void depositRejectsNegativeAmount() {
        String owner = Usernames.fresh("MnO");
        String firm = freshFirmName("Neg");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business deposit " + firm + " -10");
                p.awaitMessageContaining("Invalid amount", TIMEOUT);
            });
        }
    }
}
