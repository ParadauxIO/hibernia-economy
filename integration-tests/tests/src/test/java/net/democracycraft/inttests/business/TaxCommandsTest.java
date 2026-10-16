package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

class TaxCommandsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void taxInfoShowsHeader() {
        String owner = Usernames.fresh("TiO");
        String firm = freshFirmName("Tin");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business tax info " + firm);
                p.awaitMessageContaining("Tax Information for " + firm, TIMEOUT);
            });
        }
    }

    @Test
    void taxExemptTrueMarksFirmExempt() {
        String owner = Usernames.fresh("TeO");
        String firm = freshFirmName("Tex");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business tax exempt " + firm + " true");
                p.awaitMessageContaining(firm + " is now exempt", TIMEOUT);
            });
        }
    }

    @Test
    void taxExemptFalseClearsExemption() {
        String owner = Usernames.fresh("TfO");
        String firm = freshFirmName("Tfx");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business tax exempt " + firm + " yes");
                p.awaitMessageContaining(firm + " is now exempt", TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business tax exempt " + firm + " no");
                p.awaitMessageContaining(firm + " is now not exempt", TIMEOUT);
            });
        }
    }

    @Test
    void taxInfoRejectsUnknownFirm() {
        String name = Usernames.fresh("Tiu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business tax info NoSuchFirmEvar");
                p.awaitMessage(m -> m.contains("not found") || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void taxExemptRejectsUnknownFirm() {
        String name = Usernames.fresh("Teu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business tax exempt NoSuchFirmEvar true");
                p.awaitMessage(m -> m.contains("not found") || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void taxExemptRejectsInvalidValue() {
        String owner = Usernames.fresh("TbO");
        String firm = freshFirmName("Tbg");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business tax exempt " + firm + " maybe");
                p.awaitMessageContaining("Invalid value 'maybe'", TIMEOUT);
            });
        }
    }
}
