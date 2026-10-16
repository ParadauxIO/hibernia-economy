package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business plugin's firm-lifecycle commands.
 *
 * <ul>
 *   <li>{@code /business create &lt;firm&gt;}</li>
 *   <li>{@code /business disband &lt;firm&gt;} + {@code disband &lt;firm&gt; confirm}</li>
 *   <li>{@code /business list} / {@code list &lt;user&gt;} / {@code list all} /
 *       {@code list all &lt;page&gt;}</li>
 *   <li>{@code /business info &lt;firm&gt;}</li>
 *   <li>{@code /business attribute set hq &lt;firm&gt; &lt;plot&gt;}</li>
 *   <li>{@code /business attribute set discord &lt;firm&gt; &lt;url&gt;}</li>
 * </ul>
 */
class FirmCommandsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        // Firm display names allow more characters than usernames; just keep
        // them unique-per-test and Mojang-friendly.
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void createFirmRecordsRowAndProprietor() {
        String owner = Usernames.fresh("FcO");
        String firm = freshFirmName("Acme");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.drainMessages();
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
            });

            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            assertThat(BusinessLedger.firmProprietor(firmId)).hasValue(p.getUuid());
        }
    }

    @Test
    void disbandRemovesFirmAndReturnsFundsToProprietor() {
        String owner = Usernames.fresh("FdO");
        String firm = freshFirmName("Disb");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                // Disbanding is destructive, so it is two-step (PAR-93/PAR-86): the bare
                // command only warns and arms a 60s confirmation…
                p.chatCommand("/business disband " + firm);
                p.awaitMessageContaining("is permanent", TIMEOUT);
                Barrier.settle();
                assertThat(BusinessLedger.firmIsArchived(firm))
                        .as("the prompt alone must not disband the firm").isFalse();

                // …and only the explicit confirm actually disbands.
                p.drainMessages();
                p.chatCommand("/business disband " + firm + " confirm");
                p.awaitMessageContaining("has been disbanded, and funds have been returned", TIMEOUT);
            });

            // Wait for the disband transaction to commit, then assert state.
            Await.untilTrue("firm " + firm + " to be archived", () -> BusinessLedger.firmIsArchived(firm), TIMEOUT);
        }
    }

    @Test
    void listShowsOwnFirms() {
        String owner = Usernames.fresh("FlO");
        String firm = freshFirmName("Lst");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business list");
                String response = p.awaitMessageContaining("Firms for", TIMEOUT);
                assertThat(response).contains(owner);
            });
        }
    }

    @Test
    void listAllShowsAllFirmsPaged() {
        String owner = Usernames.fresh("FaO");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.drainMessages();
                p.chatCommand("/business list all");
                p.awaitMessageContaining("Firms (page", TIMEOUT);
            });
        }
    }

    @Test
    void infoShowsFirmDetails() {
        String owner = Usernames.fresh("FiO");
        String firm = freshFirmName("Inf");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business info " + firm);
                p.awaitMessageContaining(firm, TIMEOUT);
            });
        }
    }

    @Test
    void attributeSetHqStoresTheRegionName() {
        String owner = Usernames.fresh("FhO");
        String firm = freshFirmName("Hq");
        String region = "plot42";

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business attribute set hq " + firm + " " + region);
                p.awaitMessageContaining("HQ", TIMEOUT);

                // Settle then verify DB column.
                Await.untilEquals("HQ region of firm " + firmId,
                        () -> BusinessLedger.firmHqRegion(firmId).orElse(null), region, TIMEOUT);
            });
        }
    }

    @Test
    void attributeSetDiscordStoresTheUrl() {
        String owner = Usernames.fresh("FdO");
        String firm = freshFirmName("Dsc");
        String url = "https://discord.gg/example";

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business attribute set discord " + firm + " " + url);
                p.awaitMessageContaining("Discord", TIMEOUT);

                Await.untilEquals("Discord URL of firm " + firmId,
                        () -> BusinessLedger.firmDiscordUrl(firmId).orElse(null), url, TIMEOUT);
            });
        }
    }
}
