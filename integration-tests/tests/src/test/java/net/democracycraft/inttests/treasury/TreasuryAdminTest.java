package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code /treasury admin ingest <source>} (TreasuryCommand).
 *
 * <p>The route looks up an {@link net.democracycraft.treasury.api.ingest.IngestApi}
 * provider registered as a Bukkit service whose {@code supportedSources()}
 * contains the requested source name. TreasuryIngest is the only provider
 * normally registered (sources: {@code essentialsx}).
 *
 * <p>This test class covers the two user-visible branches:
 * <ol>
 *   <li>Unknown source → {@code "No ingest plugin is registered for source '…'"}</li>
 *   <li>Known source dispatched → response begins with the
 *       {@code "Starting … ingest…"} prefix (the rest depends on EssentialsX
 *       userdata, which the dedicated {@code EssentialsXIngestTest} covers in
 *       depth — here we just prove dispatch reaches the provider).</li>
 * </ol>
 */
class TreasuryAdminTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Test
    void adminIngestRejectsUnknownSource() {
        String admin = Usernames.fresh("Iur");
        try (MockPlayer p = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                p.drainMessages();
                p.chatCommand("/treasury admin ingest never-heard-of-this-source");
                String r = p.awaitMessageContaining("No ingest plugin", TIMEOUT);
                assertThat(r).contains("never-heard-of-this-source");
            });
        }
    }

    @Test
    void adminIngestRejectsUnknownSourceCaseInsensitive() {
        // Source name is lowercased before the supportedSources() lookup —
        // mixed case "Made-Up-Source" still routes through the unknown branch.
        String admin = Usernames.fresh("Iuc");
        try (MockPlayer p = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                p.drainMessages();
                p.chatCommand("/treasury admin ingest Made-Up-Source");
                p.awaitMessageContaining("No ingest plugin", TIMEOUT);
            });
        }
    }

    @Test
    @Disabled("Needs the standalone TreasuryIngest plugin, which the integration stack does not install "
            + "(same gate as EssentialsXIngestTest)")
    void adminIngestDispatchesToRegisteredEssentialsxProvider() {
        // TreasuryIngest registers an IngestApi for "essentialsx" — the
        // command should reach the provider and emit the "Starting ingest"
        // line before processing the (empty) userdata directory. Doesn't
        // assert on the post-ingest summary; that's EssentialsXIngestTest's
        // territory. Asserting that dispatch reaches the provider proves the
        // happy-path lookup branch, which the unknown-source tests cannot.
        String admin = Usernames.fresh("Idp");
        try (MockPlayer p = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                p.drainMessages();
                p.chatCommand("/treasury admin ingest essentialsx");
                String r = p.awaitMessageContaining("Starting", TIMEOUT);
                assertThat(r).containsIgnoringCase("essentialsx");
            });
        }
    }
}
