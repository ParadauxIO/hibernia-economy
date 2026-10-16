package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code /treasury admin ingest <source>} (TreasuryCommand).
 *
 * <p>The route looks up an {@link net.democracycraft.treasury.api.ingest.IngestApi}
 * provider registered as a Bukkit service whose {@code supportedSources()}
 * contains the requested source name. TreasuryIngest is the only provider
 * normally registered (sources: {@code essentialsx}); it is not part of the
 * integration stack, so the dispatch test uses DcDiag's stub provider.
 *
 * <p>This test class covers the two user-visible branches:
 * <ol>
 *   <li>Unknown source → {@code "No ingest plugin is registered for source '…'"}</li>
 *   <li>Known source dispatched → {@code "Starting … ingest…"}, the provider runs,
 *       and its report is rendered as {@code "Ingest complete — …"}.</li>
 * </ol>
 */
class TreasuryAdminTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** Registered by {@code /diag ingeststub}; must never match a real provider's source. */
    private static final String STUB_SOURCE = "dcdiagstub";

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
    void adminIngestDispatchesToARegisteredProvider() {
        // TreasuryIngest (the real "essentialsx" provider) is a separate plugin the stack
        // doesn't install, so DcDiag registers a stub IngestApi for a made-up source. That
        // still proves the happy-path branch the unknown-source tests can't reach: provider
        // lookup by lower-cased source → "Starting" → provider invoked → its IngestReport
        // rendered in the completion line.
        assertThat(RconClient.send("diag ingeststub " + STUB_SOURCE)).contains("ingeststub");
        String admin = Usernames.fresh("Idp");
        try (MockPlayer p = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                p.drainMessages();
                p.chatCommand("/treasury admin ingest " + STUB_SOURCE.toUpperCase(Locale.ROOT));
                assertThat(p.awaitMessageContaining("Starting", TIMEOUT)).contains(STUB_SOURCE);
                assertThat(p.awaitMessageContaining("Ingest complete", TIMEOUT))
                        .contains("created=0").contains("scanned=0");
            });
        }
    }
}
