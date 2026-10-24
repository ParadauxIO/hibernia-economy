package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rewrite's raison d'être (ADT-4): a buy must be an atomic unit of
 * money + items + record. This is the contention probe. Three buyers click a
 * shop stocked for exactly <b>two</b> batches at (as near as the wire allows)
 * the same instant. Whoever wins, the invariants must hold:
 *
 * <ul>
 *   <li>exactly two batches leave the chest (no oversell),</li>
 *   <li>the seller is credited exactly two prices,</li>
 *   <li>money is conserved: total buyer debits == seller credit,</li>
 *   <li>no buyer receives goods without paying, or pays without goods.</li>
 * </ul>
 *
 * A TOCTOU stock race would oversell (three sales from two batches) or leak
 * money/items; either breaks an assertion here.
 */
class ConcurrencyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;
    private static final int BATCHES = 2; // stock for two of the three buyers

    @Test
    void concurrentBuyersCannotOversell() throws Exception {
        String seller = Usernames.fresh("Cns");
        String[] buyers = { Usernames.fresh("Cc1"), Usernames.fresh("Cc2"), Usernames.fresh("Cc3") };

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b1 = MockPlayer.connectLocal(buyers[0]);
             MockPlayer b2 = MockPlayer.connectLocal(buyers[1]);
             MockPlayer b3 = MockPlayer.connectLocal(buyers[2]);
             ChestShopFixture shop = ChestShopFixture.create()) {

            MockPlayer[] mocks = { b1, b2, b3 };
            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            for (MockPlayer b : mocks) {
                Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);
            }

            shop.setupSellShop(seller, QTY, PRICE, "dirt");
            // Trim the chest to exactly BATCHES worth — two of the three can succeed.
            RconClient.send(String.format(
                    "item replace block %d %d %d container.0 with minecraft:dirt %d",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z(), QTY * BATCHES));
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(QTY * BATCHES);

            // Get all three into range (sequential; they may share the spot), then fire
            // the clicks concurrently from a barrier so they contend for the stock.
            for (int i = 0; i < buyers.length; i++) {
                shop.positionClicker(mocks[i], buyers[i]);
            }

            CountDownLatch ready = new CountDownLatch(buyers.length);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            for (MockPlayer b : mocks) {
                Thread t = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        b.useBlock(shop.signPos(), BlockFace.SOUTH);
                        Barrier.sync(b);
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                    }
                });
                t.start();
                threads.add(t);
            }
            ready.await();       // all threads parked on the barrier
            go.countDown();      // release simultaneously
            for (Thread t : threads) {
                t.join();
            }
            assertThat(failure.get()).as("a buyer thread threw").isNull();

            // Every direct buy has passed its packet barrier; read the settled result.
            Barrier.sync(mocks);

            BigDecimal sellerBalance = Ledger.personalBalance(s.getUuid()).orElseThrow();
            int chestLeft = Inv.chestCount(shop.chestPos(), "DIRT");

            BigDecimal totalDebit = BigDecimal.ZERO;
            int totalBuyerDirt = 0;
            for (int i = 0; i < buyers.length; i++) {
                BigDecimal bal = Ledger.personalBalance(mocks[i].getUuid()).orElseThrow();
                totalDebit = totalDebit.add(STARTING.subtract(bal));
                assertThat(bal).as("buyer %s below zero", buyers[i]).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                int received = Inv.playerCount(buyers[i], "DIRT");
                assertThat(received).isIn(0, QTY);
                assertThat(STARTING.subtract(bal)).as("buyer pays exactly for their received batch")
                        .isEqualByComparingTo(BigDecimal.valueOf((long) received * PRICE / QTY));
                assertThat(mocks[i].currentScreen()).as("ordinary purchases do not open a preview").isEmpty();
                totalBuyerDirt += received;
            }

            // Exactly two batches sold — no more, no fewer.
            assertThat(sellerBalance)
                    .as("seller must be credited exactly %d sales (no oversell)", BATCHES)
                    .isEqualByComparingTo(STARTING.add(BigDecimal.valueOf((long) PRICE * BATCHES)));
            assertThat(chestLeft).as("all trimmed stock sold, none oversold").isZero();
            assertThat(totalBuyerDirt).as("buyers hold exactly the sold goods").isEqualTo(QTY * BATCHES);

            // Money conserved (no tax): what the buyers lost is exactly what the seller gained.
            assertThat(totalDebit)
                    .as("money conservation: buyer debits == seller credit")
                    .isEqualByComparingTo(BigDecimal.valueOf((long) PRICE * BATCHES));
        }
    }
}
