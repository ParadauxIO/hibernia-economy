package net.democracycraft.inttests.chestshop;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.mockclient.MockPlayer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * One PAR-523 benchmark sample, driven by {@code ops/integration/hopper-bench.mjs} through the
 * runner's hopper-bench profile. Not a pass/fail test: it builds the workload, warms it up,
 * records one window and leaves the result JSON at {@code IT_BENCH_OUT}.
 */
@EnabledIfEnvironmentVariable(named = "IT_HOPPER_BENCH", matches = "true")
class HopperBenchmarkRun {

    @Test
    void sample() throws Exception {
        String variant = System.getenv("IT_BENCH_VARIANT");
        String scenario = System.getenv("IT_BENCH_SCENARIO");
        int warmup = Integer.parseInt(System.getenv("IT_BENCH_WARMUP_S"));
        int seconds = Integer.parseInt(System.getenv("IT_BENCH_SAMPLE_S"));
        Path out = Path.of(System.getenv("IT_BENCH_OUT")).toAbsolutePath();
        Files.deleteIfExists(out);

        // Shop signs name a real, online owner with an account, as on a live server.
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("Bench"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(30));
            assertThat(RconClient.send("diag bench build " + owner.getUsername() + " " + scenario))
                    .startsWith("bench built");
            // Production's ListenerControl removes WorldGuard's move handler; leaving it here would
            // charge its per-event cost to every variant that keeps events on.
            String detach = switch (variant) {
                case "event-off", "legacy", "worker" -> "WorldGuard";
                case "no-listener" -> "WorldGuard,ChestShop";
                // Production's ListenerControl (2026-06) also removes ChestShop's protection handler only.
                case "worker-monitor-only" -> "WorldGuard,ChestShop/HIGHEST";
                default -> throw new IllegalArgumentException("unknown variant " + variant);
            };
            assertThat(RconClient.send("diag bench detach " + detach)).startsWith("bench detached");
            Thread.sleep(Duration.ofSeconds(warmup));
            String started = RconClient.send("diag bench start " + variant + " " + scenario + " " + seconds + " " + out);
            assertThat(started).startsWith("bench started");
            System.out.println(started);
            Await.until("benchmark sample", () -> RconClient.send("diag bench status"),
                    status -> status.startsWith("bench done") || status.startsWith("bench failed"),
                    Duration.ofSeconds(seconds + 300L));
            assertThat(RconClient.send("diag bench status")).startsWith("bench done");
            assertThat(out).exists();
        }
    }
}
