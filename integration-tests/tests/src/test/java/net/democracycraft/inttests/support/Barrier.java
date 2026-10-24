package net.democracycraft.inttests.support;

import io.paradaux.mockclient.MockPlayer;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Positive barriers: wait until the server has <em>provably</em> finished processing what
 * a test already sent, so the test can then assert that nothing changed.
 *
 * <p>A fixed {@code sleep} before a "nothing happened" assertion passes vacuously when the
 * server is slow — the assertion runs before the action is processed. These barriers wait
 * for evidence of processing instead, and fail loudly if it never comes.
 *
 * <h2>{@link #sync} — clicks, digs, movement</h2>
 * A protocol round trip on each player's own connection ({@link MockPlayer#sync}). World
 * interaction packets and the barrier's request share Paper's FIFO packet queue, so when
 * it returns every earlier packet has been handled on the main thread, including the
 * synchronous Bukkit events it fired. ChestShop sign clicks may open a purchase preview.
 * Its confirmation is deferred by Curator until the next tick: use {@link #settle}
 * after sending that menu action before asserting ledger or inventory results.
 *
 * <h2>{@link #settle} — slash-commands and the async work they start</h2>
 * Commands travel on a different server queue from interaction packets, and Hibernia
 * {@code @Async} handlers run on Bukkit's async pool, so {@code settle} establishes three
 * facts in order:
 * <ol>
 *   <li><b>Dispatched.</b> Each player sends {@code /dcsync <nonce>} (DcDiag) and waits for
 *       the echo. A player's commands are executed in order on the main thread, so the echo
 *       proves every earlier command from that player has run its synchronous part — and an
 *       {@code @Async} route has handed its task to the scheduler. (RCON commands are
 *       dispatched synchronously before {@link RconClient#send} returns.)</li>
 *   <li><b>Finished.</b> {@code /diag asyncidle} is polled until the economy plugins have no
 *       one-shot scheduler task queued or running. Bukkit keeps a task in
 *       {@code getPendingTasks()} from submission until after its body returns, so "idle"
 *       observed after step 1 means every handler started by those commands has returned —
 *       its DB writes committed and its chat lines handed to the connection.</li>
 *   <li><b>Delivered.</b> A {@link MockPlayer#sync} round trip per player, so the chat those
 *       handlers wrote has arrived at the client before the test drains or awaits it.</li>
 * </ol>
 * The sentinel is a chat command, so it would count toward vanilla's chat-spam kick for
 * non-op players; {@code ops/integration/run.sh} lists {@code /dcsync} in Spigot's
 * {@code commands.spam-exclusions}. A server set up some other way needs the same entry.
 *
 * <p>Not covered: work on threads a plugin manages itself (the economy plugins have none for
 * commands — checked with a grep for {@code Executors.} / {@code CompletableFuture.*Async}),
 * and the REST API process, which tests observe through the database instead.
 */
public final class Barrier {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Plugins whose scheduler work a command may start ({@code /diag asyncidle} owners). */
    private static final String ECONOMY_PLUGINS = "Treasury Business TreasuryAPI ChestShop";

    private static final AtomicLong NONCE = new AtomicLong(System.nanoTime());

    /** Packet barrier for each player: their clicks, digs and moves so far are fully handled. */
    public static void sync(MockPlayer... players) {
        for (MockPlayer p : players) {
            p.sync(TIMEOUT);
        }
    }

    /**
     * Command barrier over every connected mock player: their commands so far have been
     * dispatched, every economy-plugin task they started has finished, and the resulting
     * chat has arrived. Use it before asserting that a command changed nothing, and before
     * {@code drainMessages()} so the next command's reply can't be confused with a late
     * line from the previous one.
     */
    public static void settle() {
        settle(MockPlayer.openPlayers());
    }

    /** {@link #settle()} for the given players only. */
    public static void settle(MockPlayer... players) {
        settle(List.of(players));
    }

    private static void settle(Collection<MockPlayer> players) {
        List<MockPlayer> live = players.stream().filter(MockPlayer::isConnected).toList();
        for (MockPlayer p : live) {
            commandsDispatched(p);
        }
        Await.until("economy plugins' scheduler tasks to finish (/diag asyncidle)",
                () -> RconClient.send("diag asyncidle " + ECONOMY_PLUGINS).trim(),
                "idle"::equals, TIMEOUT);
        for (MockPlayer p : live) {
            p.sync(TIMEOUT);
        }
    }

    private static void commandsDispatched(MockPlayer p) {
        String marker = "dcsync " + Long.toHexString(NONCE.incrementAndGet());
        p.awaitMessageAfter(() -> p.chatCommand("/" + marker), line -> line.contains(marker), TIMEOUT);
    }

    private Barrier() {}
}
