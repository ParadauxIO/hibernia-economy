package net.democracycraft.inttests.lifecycle;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

/** Dedicated clean profile: tax configuration affects all firms on the server. */
@EnabledIfEnvironmentVariable(named = "IT_TAX_PROFILE", matches = "true")
class TaxCycleLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void manualCyclesCollectExactAmountsAndRespectReloadedConfigAndExemptions() throws Exception {
        Path config = Path.of(System.getenv("IT_SERVER_DIR"), "plugins", "Business", "config.yml");
        String original = Files.readString(config);
        int taxStart = original.indexOf("\ntax:\n");
        assertThat(taxStart).as("fixture config contains tax section").isGreaterThanOrEqualTo(0);
        String prefix = original.substring(0, taxStart);
        String firm = Usernames.fresh("Tax");
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Own"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
                owner.chatCommand("/business deposit " + firm + " 1000");
                Barrier.settle();
            });
            int firmId = BusinessLedger.firmId(firm).orElseThrow();
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("1000");
            Perms.grant(owner.getUsername(), "business.admin.reload");
            Perms.grant(owner.getUsername(), "treasury.admin.tax");
            Perms.grant(owner.getUsername(), "business.tax.exempt");
            // This is an administrative sequence, not a permission-denial test.
            // Keep the fixture operator-enabled so vanilla chat-spam throttling
            // does not interrupt repeated tax/configuration commands.
            RconClient.send("op " + owner.getUsername());
            try {
                Files.writeString(config, prefix + "\ntax:\n  balance:\n    enabled: true\n    government-account: DCGovernment\n    brackets:\n      0: 0.10\n");
                reload(owner);
                int government = Ledger.governmentAccountId("DCGovernment").orElseThrow();
                BigDecimal governmentBefore = Ledger.accountBalanceById(government).orElseThrow();
                int postings = Ledger.postingsCountForAccount(account);
                trigger(owner, "daily");
                trigger(owner, "monthly");
                assertBalances(account, "1000", government, governmentBefore, postings);
                trigger(owner, "weekly");
                assertBalances(account, "900", government, governmentBefore.add(new BigDecimal("100")), postings + 1);
                trigger(owner, "weekly");
                assertBalances(account, "810", government, governmentBefore.add(new BigDecimal("190")), postings + 2);
                owner.chatCommand("/business tax exempt " + firm + " true");
                owner.awaitMessageContaining("is now exempt", TIMEOUT);
                Barrier.settle();
                trigger(owner, "weekly");
                assertBalances(account, "810", government, governmentBefore.add(new BigDecimal("190")), postings + 2);
                owner.chatCommand("/business tax exempt " + firm + " false");
                owner.awaitMessageContaining("is now not exempt", TIMEOUT);
                Barrier.settle();
                trigger(owner, "weekly");
                assertBalances(account, "729", government, governmentBefore.add(new BigDecimal("271")), postings + 3);
                long period = java.time.Instant.parse("2025-01-06T00:00:00Z").getEpochSecond();
                scheduled(period);
                assertBalances(account, "656.10", government, governmentBefore.add(new BigDecimal("343.90")), postings + 4);
                scheduled(period);
                assertBalances(account, "656.10", government, governmentBefore.add(new BigDecimal("343.90")), postings + 4);
                scheduled(period + 7 * 86400);
                assertBalances(account, "590.49", government, governmentBefore.add(new BigDecimal("409.51")), postings + 5);
                Files.writeString(config, prefix + "\ntax:\n  balance:\n    enabled: false\n    government-account: DCGovernment\n    brackets:\n      0: 0.10\n");
                reload(owner);
                trigger(owner, "weekly");
                assertBalances(account, "590.49", government, governmentBefore.add(new BigDecimal("409.51")), postings + 5);
            } finally {
                try {
                    Files.writeString(config, original);
                    reload(owner);
                } finally {
                    RconClient.send("deop " + owner.getUsername());
                }
            }
        }
    }

    private static void scheduled(long period) {
        String task = RconClient.send("diag taxschedule " + period).trim();
        assertThat(task).startsWith("task=");
        int id = Integer.parseInt(task.substring(5));
        String state = Await.until("real tax task finishes and schedules its next run",
                () -> RconClient.send("diag taxscheduledstate " + id).trim(),
                reply -> reply.startsWith("queued=false running=false next=") && !reply.endsWith("none"), TIMEOUT);
        java.time.Instant next = java.time.Instant.parse(state.substring(state.indexOf("next=") + 5));
        assertThat(next).isAfter(java.time.Instant.now());
        Barrier.settle();
    }

    private static void reload(MockPlayer p) {
        p.chatCommand("/business reload");
        p.awaitMessageContaining("Reloaded messages.properties", TIMEOUT);
        Barrier.settle();
    }
    private static void trigger(MockPlayer p, String cycle) {
        p.chatCommand("/tax trigger " + cycle);
        p.awaitMessageContaining("Done.", TIMEOUT);
        Barrier.settle();
    }
    private static void assertBalances(int firm, String balance, int government, BigDecimal expected, int postings) {
        assertThat(Ledger.accountBalanceById(firm).orElseThrow()).isEqualByComparingTo(balance);
        assertThat(Ledger.accountBalanceById(government).orElseThrow()).isEqualByComparingTo(expected);
        assertThat(Ledger.postingsCountForAccount(firm)).isEqualTo(postings);
    }
}
