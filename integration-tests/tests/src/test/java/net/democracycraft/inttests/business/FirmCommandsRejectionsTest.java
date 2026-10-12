package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * Negative-path coverage for /business firm-level commands. Validates the
 * firm-not-found and invalid-discord-url branches that the positive-path tests
 * don't reach.
 */
class FirmCommandsRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void infoRejectsUnknownFirm() {
        String name = Usernames.fresh("Iu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business info NoSuchFirmEver");
                p.awaitMessage(m -> m.contains("not found") || m.contains("NoSuchFirm"), TIMEOUT);
            });
        }
    }

    @Test
    void disbandRejectsUnknownFirm() {
        String name = Usernames.fresh("Du");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business disband NoSuchFirmEither");
                p.awaitMessage(m -> m.contains("not found"), TIMEOUT);
            });
        }
    }

    @Test
    void attributeSetHqRejectsUnknownFirm() {
        String name = Usernames.fresh("Hu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business attribute set hq NoSuchFirm SomeRegion");
                p.awaitMessage(m -> m.contains("not found"), TIMEOUT);
            });
        }
    }

    @Test
    void attributeSetDiscordRejectsUnknownFirm() {
        String name = Usernames.fresh("Adu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business attribute set discord NoSuchFirmEv https://discord.gg/abc");
                p.awaitMessage(m -> m.contains("not found"), TIMEOUT);
            });
        }
    }

    @Test
    void listAllRejectsNegativePage() {
        // /business list all <page> — page = 0 or negative should be clamped
        // or rejected. The handler's contract is silent on the exact behaviour
        // here, but it must not throw to the user — assert a non-blank reply.
        String name = Usernames.fresh("Lan");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business list all 0");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    @Test
    void createRejectsDuplicateFirmName() {
        // Already covered by FirmNameAttacksTest::createRejectsDuplicateNameCaseInsensitive
        // for the casing collision; this test asserts the same-casing case to
        // pin the basic duplicate guard (separately from the casing extension).
        String owner = Usernames.fresh("Dup");
        String firm = freshFirmName("Dpf");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business create " + firm);
                String r = p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                org.assertj.core.api.Assertions.assertThat(r)
                        .doesNotContainIgnoringCase("successfully created");
            });
        }
    }

    @Test
    void attributeSetDiscordRejectsInvalidUrl() {
        String owner = Usernames.fresh("Du");
        String firm = freshFirmName("Iur");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business attribute set discord " + firm + " https://example.com/notdiscord");
                p.awaitMessageContaining("Invalid URL", TIMEOUT);
            });
        }
    }
}
